import { createHash } from "node:crypto";
import { Buffer } from "node:buffer";
import { SESSION_CUES, DEFAULT_GROQ_MODEL } from "../../functions/session-explanation-policy.mjs";
import { ApiError, candidateIdentity, firestoreRead } from "./firebase.mjs";

const VOICE_MODEL = "canopylabs/orpheus-v1-english";
const PREVIEW = "I'm here to coach you. Keep your whole body in view, and listen for one clear tip at a time.";
const hash = value => createHash("sha256").update(value).digest("hex");
const tolerances = {
  Serving: [30, 35, 20, 35], Receiving: [25, 30, 20, 15],
  Setting: [0.15, 30, 30, 35], Spiking: [35, 30, 0.2, 25], Blocking: [30, 0.2, 0.15, 35],
};
// A break explains one repeatedly measured cue. These are local app summaries,
// not server-verified saved-session results; they cannot write scores or sharing.
export function liveEvidence(data) {
  if (!data || Object.keys(data).sort().join() !== "angle,reps,skill,voice" ||
      !["Front", "Side", "Rear"].includes(data.angle) || typeof data.voice !== "boolean" ||
      !SESSION_CUES[data.skill] || !Array.isArray(data.reps) || data.reps.length < 3 || data.reps.length > 10) {
    throw new ApiError("INVALID_ARGUMENT", "Send a completed practice angle.");
  }
  const definitions = SESSION_CUES[data.skill];
  for (const rep of data.reps) {
    if (!Array.isArray(rep) || rep.length > 4 || new Set(rep.map(c => c?.cueId)).size !== rep.length) throw new ApiError("INVALID_ARGUMENT", "Invalid cue evidence.");
    for (const item of rep) {
      const definition = definitions.find(c => c.id === item?.cueId);
      if (!definition || Object.keys(item).sort().join() !== "cueId,measured" ||
          typeof item.measured !== "number" || !Number.isFinite(item.measured) ||
          Math.abs(item.measured) > (definition.unit === "deg" ? 180 : 5)) throw new ApiError("INVALID_ARGUMENT", "Invalid cue evidence.");
    }
  }
  const cues = definitions.map((definition, index) => {
    const values = data.reps.flatMap(rep => rep.filter(c => c.cueId === definition.id).map(c => c.measured));
    const distance = v => Math.max(definition.target[0] - v, v - definition.target[1], 0);
    const misses = values.filter(v => distance(v) > tolerances[data.skill][index] * .2);
    const average = values.reduce((a, b) => a + b, 0) / values.length;
    return { ...definition, measuredReps: values.length, missedReps: misses.length,
      severity: values.length ? values.reduce((a, b) => a + Math.min(1, distance(b) / tolerances[data.skill][index]), 0) / values.length : 0,
      position: average < definition.target[0] ? "below" : average > definition.target[1] ? "above" : "within" };
  });
  return cues.filter(c => c.measuredReps >= 3 && c.missedReps >= 2 && c.position !== "within")
    .sort((a, b) => b.severity - a.severity)[0] ?? null;
}

export function validateLiveAdvice(raw, cueId) {
  const value = typeof raw === "string" ? JSON.parse(raw) : raw;
  if (!value || Object.keys(value).sort().join() !== "cueId,text" || value.cueId !== cueId ||
      typeof value.text !== "string") throw Error("invalid_live_advice");
  const text = value.text.trim().replace(/\s+/g, " ");
  if (!text || text.length > 200 || /\p{N}|[\[\]<>]/u.test(text) ||
      /diagnos|injur|medical|\bpain\b|\bI (?:saw|watched)\b|video|always|every rep|improv(?:ed|ing)|ball (?:flight|speed)/i.test(text)) throw Error("ungrounded_live_advice");
  return { cueId, text };
}

async function boundedBytes(response, maximum) {
  if (response.ok === false || Number(response.headers.get("content-length")) > maximum) throw Error("provider_unavailable");
  const reader = response.body?.getReader();
  if (!reader) throw Error("provider_unavailable");
  const chunks = []; let size = 0;
  while (true) {
    const { value, done } = await reader.read(); if (done) break;
    size += value.length;
    if (size > maximum) { await reader.cancel(); throw Error("provider_too_large"); }
    chunks.push(value);
  }
  return Buffer.concat(chunks);
}

export async function generateLiveAdvice({ apiKey, cue, skill, fetchImpl = fetch }) {
  const response = await fetchImpl("https://api.groq.com/openai/v1/chat/completions", {
    method: "POST", redirect: "manual", signal: AbortSignal.timeout(3_500),
    headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
    body: JSON.stringify({ model: DEFAULT_GROQ_MODEL, reasoning_effort: "low", max_completion_tokens: 500,
      response_format: { type: "json_schema", json_schema: { name: "live_coach", strict: true,
        schema: { type: "object", additionalProperties: false, required: ["cueId", "text"], properties: {
          cueId: { type: "string", enum: [cue.id] }, text: { type: "string" },
        } } } },
      messages: [{ role: "system", content: "You coach a volleyball player during a break. Explain only the supplied repeated, locally measured cue and its approved tip. Write two calm, natural English sentences, at most 200 characters total: one observation and one action for the next attempt. No digits, other mistakes, causes, trends, praise, medical claims, or claims of watching video. Reference ranges are app practice references, not universal standards. Do not invent scores or change the approved tip. Return the required JSON." },
        { role: "user", content: JSON.stringify({ skill, cueId: cue.id, label: cue.label, position: cue.position, tip: cue.tip }) }],
    }),
  });
  const body = JSON.parse((await boundedBytes(response, 12_000)).toString("utf8"));
  return validateLiveAdvice(body.choices?.[0]?.message?.content, cue.id);
}

async function reserve(db, actor, kind, now, limit, interval) {
  const day = new Date(now).toISOString().slice(0, 10);
  const result = await db.prepare(`INSERT INTO live_coaching_usage(actor,kind,day,count,last_requested_at)
    VALUES (?,?,?,1,?) ON CONFLICT(actor,kind) DO UPDATE SET day=excluded.day,
    count=CASE WHEN live_coaching_usage.day=excluded.day THEN live_coaching_usage.count+1 ELSE 1 END,
    last_requested_at=excluded.last_requested_at
    WHERE (live_coaching_usage.day!=excluded.day OR live_coaching_usage.count<?)
      AND live_coaching_usage.last_requested_at<=?`)
    .bind(actor, kind, day, now, limit, now - interval).run();
  return result.meta.changes === 1;
}

async function voice(text, env, actor, now, fetchImpl) {
  const unavailable = voiceStatus => ({ speechBase64: null, voiceStatus });
  // Explicit operator gate: never assume an existing Groq account is free.
  if (env.GROQ_VOICE_FREE_TIER_CONFIRMED !== "true" || !env.GROQ_API_KEY || text.length > 200) return unavailable("not_enabled");
  if (!await reserve(env.AI_DB, actor, "voice", now, 10, 8_000)) return unavailable("limited");
  // Shared organization guard, below the documented free request quota. Groq's
  // token quota remains authoritative; 429 immediately falls back to phone TTS.
  if (!await reserve(env.AI_DB, "organization", "voice", now, 90, 7_000)) return unavailable("limited");
  try {
    const response = await fetchImpl("https://api.groq.com/openai/v1/audio/speech", {
      method: "POST", redirect: "manual", signal: AbortSignal.timeout(3_500),
      headers: { Authorization: `Bearer ${env.GROQ_API_KEY}`, "Content-Type": "application/json" },
      body: JSON.stringify({ model: VOICE_MODEL, voice: "troy", input: text, response_format: "wav" }),
    });
    if (!response.ok) {
      let code;
      try { code = (await response.json()).error?.code; } catch { /* No provider text is returned to clients. */ }
      return unavailable(`provider_${response.status}${typeof code === "string" && /^[a-z_]{1,60}$/.test(code) ? `_${code}` : ""}`);
    }
    const wav = await boundedBytes(response, 1_500_000);
    if (wav.length < 44 || wav.toString("ascii", 0, 4) !== "RIFF" || wav.toString("ascii", 8, 12) !== "WAVE") return unavailable("invalid_audio");
    return { speechBase64: wav.toString("base64"), voiceStatus: "ready" };
  } catch { return unavailable("unavailable"); }
}

export async function liveCoachingRequest(request, env, headers, { fetchImpl = fetch, now = Date.now, generate = generateLiveAdvice } = {}) {
  try {
    if (request.method !== "POST") return Response.json({ error: { status: "INVALID_ARGUMENT", message: "Use POST." } }, { status: 405, headers });
    const identity = candidateIdentity(request.headers.get("authorization"), now());
    // Firestore, not decoded JWT claims, verifies the signed token and deletion fence.
    await firestoreRead(`users/${identity.uid}`, identity.token, fetchImpl, ["role"]);
    if (!env.AI_DB) throw new ApiError("UNAVAILABLE", "Live coaching is unavailable.");
    if (!/^application\/json(?:\s*;|$)/i.test(request.headers.get("content-type") ?? "")) throw new ApiError("INVALID_ARGUMENT", "Send JSON.");
    const raw = await boundedBytes(request, 8_192);
    const body = JSON.parse(raw.toString("utf8"));
    if (!body || Object.keys(body).join() !== "data") throw new ApiError("INVALID_ARGUMENT", "Invalid request.");
    const actor = hash(identity.uid);
    if (new URL(request.url).pathname === "/coachVoicePreview") {
      if (!body.data || Object.keys(body.data).length) throw new ApiError("INVALID_ARGUMENT", "Invalid preview.");
      return Response.json({ result: { text: PREVIEW, ...await voice(PREVIEW, env, actor, now(), fetchImpl) } }, { headers });
    }
    const cue = liveEvidence(body.data);
    if (!cue) return Response.json({ result: { status: "insufficient" } }, { headers });
    const fallback = { cueId: cue.id, text: `${cue.label} was ${cue.position} the practice range. ${cue.tip}` };
    let advice = fallback; let source = "local";
    if (env.GROQ_API_KEY && await reserve(env.AI_DB, actor, "text", now(), 20, 15_000) &&
        await reserve(env.AI_DB, "organization", "text", now(), 500, 2_200)) {
      try { advice = validateLiveAdvice(await generate({ apiKey: env.GROQ_API_KEY, cue, skill: body.data.skill, fetchImpl }), cue.id); source = "groq"; }
      catch { /* Keep the approved explanation; no retries during a live break. */ }
    }
    return Response.json({ result: { status: "ready", ...advice, source,
      ...body.data.voice ? await voice(advice.text, env, actor, now(), fetchImpl) : { speechBase64: null, voiceStatus: "disabled" } } }, { headers });
  } catch (error) {
    const known = error instanceof ApiError;
    const status = known ? error.status : "INVALID_ARGUMENT";
    return Response.json({ error: { status, message: known ? error.message : "Live coaching is unavailable. Your local cues still work." } },
      { status: ({ UNAUTHENTICATED: 401, PERMISSION_DENIED: 403, UNAVAILABLE: 503, RESOURCE_EXHAUSTED: 429 })[status] ?? 400, headers });
  }
}
