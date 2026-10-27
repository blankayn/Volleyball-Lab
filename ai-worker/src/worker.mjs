import { createHash, randomUUID } from "node:crypto";
import { DEFAULT_GROQ_MODEL, EXPLANATION_VERSION, explanationHash, requestGroqExplanation, sessionEvidence, validateExplanation } from "../../functions/session-explanation-policy.mjs";
import { ApiError, authorizedSession, candidateIdentity } from "./firebase.mjs";
import { ExplanationStore } from "./store.mjs";

const HTTP = { INVALID_ARGUMENT: 400, UNAUTHENTICATED: 401, PERMISSION_DENIED: 403, NOT_FOUND: 404, FAILED_PRECONDITION: 400, ABORTED: 409, RESOURCE_EXHAUSTED: 429, UNAVAILABLE: 503, INTERNAL: 500 };
const hashKey = (type, value) => createHash("sha256").update(`volleyball-lab:${type}:${value}`).digest("hex");
function cachedResult(row, fingerprint, evidence, now) {
  if (!row?.payload || row.fingerprint !== fingerprint || row.expires_at <= now) return null;
  try { return { status: "ready", evidence, explanation: validateExplanation(row.payload, evidence), generatedAt: row.generated_at, cached: true }; }
  catch { return null; }
}

async function requestData(request) {
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers.get("content-type") ?? "")) throw new ApiError("INVALID_ARGUMENT", "Send a JSON request.");
  if (Number(request.headers.get("content-length")) > 2048) throw new ApiError("INVALID_ARGUMENT", "The request is too large.");
  const reader = request.body?.getReader();
  if (!reader) throw new ApiError("INVALID_ARGUMENT", "Choose a saved session.");
  let size = 0; const chunks = [];
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > 2048) { await reader.cancel(); throw new ApiError("INVALID_ARGUMENT", "The request is too large."); }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  try {
    const body = JSON.parse(new TextDecoder().decode(bytes));
    const data = body?.data;
    if (!body || Object.keys(body).join() !== "data" || !data || Object.keys(data).some(key => !["sessionId", "generate"].includes(key)) ||
      typeof data.sessionId !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(data.sessionId) || typeof data.generate !== "boolean") throw Error();
    return data;
  } catch { throw new ApiError("INVALID_ARGUMENT", "Choose a saved session."); }
}

export function createWorker({ fetchImpl = fetch, generate = requestGroqExplanation, now = Date.now } = {}) {
  return {
    async fetch(request, env) {
      const origin = request.headers.get("origin");
      const allowed = (env.ALLOWED_ORIGINS ?? "https://volleyball-lab.web.app,https://volleyball-lab.firebaseapp.com").split(",").map(value => value.trim());
      const headers = { "Cache-Control": "no-store", "Content-Type": "application/json; charset=utf-8", "Vary": "Origin", "X-Content-Type-Options": "nosniff" };
      if (origin && !allowed.includes(origin)) return Response.json({ error: { status: "PERMISSION_DENIED", message: "This origin is unavailable." } }, { status: 403, headers });
      if (origin) headers["Access-Control-Allow-Origin"] = origin;
      if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: { ...headers, "Access-Control-Allow-Methods": "POST, OPTIONS", "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Firebase-AppCheck, Firebase-Instance-ID-Token", "Access-Control-Max-Age": "3600" } });
      const url = new URL(request.url);
      if (url.pathname === "/health" && request.method === "GET") return Response.json({ ok: true, service: "volleyball-session-ai", version: EXPLANATION_VERSION, configured: Boolean(env.GROQ_API_KEY && env.AI_DB) }, { headers });
      if (url.pathname !== "/explainTrainingSession") return Response.json({ error: { status: "NOT_FOUND", message: "Endpoint unavailable." } }, { status: 404, headers });
      if (request.method !== "POST") return Response.json({ error: { status: "INVALID_ARGUMENT", message: "Use POST." } }, { status: 405, headers: { ...headers, Allow: "POST, OPTIONS" } });
      let store, key, lease;
      try {
        const identity = candidateIdentity(request.headers.get("authorization"), now());
        const data = await requestData(request);
        const session = await authorizedSession(data.sessionId, identity, fetchImpl);
        // Nothing from unverified claims reaches D1: Firestore has now verified
        // the signed ID token for this fixed project and authorized this session.
        let evidence;
        try { evidence = sessionEvidence(session); } catch { throw new ApiError("FAILED_PRECONDITION", "This session has no supported measurements."); }
        if (!env.AI_DB) throw new ApiError("UNAVAILABLE", "Session explanations are being configured.");
        const model = env.GROQ_SESSION_MODEL || DEFAULT_GROQ_MODEL;
        const fingerprint = explanationHash(evidence, model);
        store = new ExplanationStore(env.AI_DB);
        key = hashKey("session", data.sessionId);
        const cached = cachedResult(await store.get(key), fingerprint, evidence, now());
        if (cached) return Response.json({ result: cached }, { headers });
        if (!evidence.strengths.length && !evidence.priorities.length) return Response.json({ result: { status: "insufficient", evidence } }, { headers });
        if (!data.generate) return Response.json({ result: { status: "empty", evidence } }, { headers });
        if (!env.GROQ_API_KEY) throw new ApiError("UNAVAILABLE", "Session explanations are being configured.");
        lease = randomUUID();
        const reservation = await store.reserve({ key, owner: hashKey("owner", session.ownerUid), fingerprint, actor: hashKey("actor", identity.uid), lease, now: now() });
        if (reservation.status === "limited") throw new ApiError("RESOURCE_EXHAUSTED", "Wait before trying again, or try tomorrow if your daily limit was reached.");
        if (reservation.status === "occupied") {
          const racedCache = cachedResult(reservation.row, fingerprint, evidence, now());
          if (racedCache) return Response.json({ result: racedCache }, { headers });
          throw new ApiError("ABORTED", "An explanation is being prepared. Try again shortly.");
        }
        const explanation = await generate({ apiKey: env.GROQ_API_KEY, model, evidence, fetchImpl });
        // Enforce the same output policy even when the provider adapter changes.
        const validated = validateExplanation(JSON.stringify(explanation), evidence);
        const latest = await authorizedSession(data.sessionId, identity, fetchImpl);
        if (latest.ownerUid !== session.ownerUid || explanationHash(sessionEvidence(latest), model) !== fingerprint) throw new ApiError("ABORTED", "This session changed. Generate its updated explanation.");
        const generatedAt = now();
        if (!await store.complete(key, lease, fingerprint, validated, generatedAt)) throw new ApiError("ABORTED", "Generate the explanation again.");
        return Response.json({ result: { status: "ready", evidence, explanation: validated, generatedAt, cached: false } }, { headers });
      } catch (error) {
        if (store && key && lease) { try { await store.release(key, lease); } catch { /* The lease expires without exposing its payload. */ } }
        const known = error instanceof ApiError;
        const status = known ? error.status : error?.message === "groq_rate_limited" ? "RESOURCE_EXHAUSTED" : "UNAVAILABLE";
        return Response.json({ error: { status, message: known ? error.message : "AI explanation is unavailable. Your measured feedback is still available." } }, { status: HTTP[status] ?? 500, headers });
      }
    },
    async scheduled(_event, env) { if (env.AI_DB) await new ExplanationStore(env.AI_DB).purge(now()); },
  };
}

export default createWorker();
