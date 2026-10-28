import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { createWorker } from "../src/worker.mjs";
import { ExplanationStore, CACHE_TTL_MS } from "../src/store.mjs";
import { authorizedSession } from "../src/firebase.mjs";

function database() {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec(readFileSync(new URL("../migrations/0001_session_ai.sql", import.meta.url), "utf8"));
  const prepare = sql => {
    let args = [];
    const result = {
      bind(...next) { args = next; return result; },
      async first() { return sqlite.prepare(sql).get(...args) ?? null; },
      async run() { const info = sqlite.prepare(sql).run(...args); return { success: true, results: [], meta: { changes: Number(info.changes) } }; },
      sql: () => sql, args: () => args,
    };
    return result;
  };
  return { sqlite, prepare, async batch(statements) {
    sqlite.exec("BEGIN");
    try {
      const results = statements.map(statement => {
        const sql = statement.sql(); const args = statement.args();
        if (/^\s*SELECT/i.test(sql)) return { success: true, results: sqlite.prepare(sql).all(...args), meta: { changes: 0 } };
        const info = sqlite.prepare(sql).run(...args);
        return { success: true, results: [], meta: { changes: Number(info.changes) } };
      });
      sqlite.exec("COMMIT"); return results;
    } catch (error) { sqlite.exec("ROLLBACK"); throw error; }
  } };
}

const startingTime = Date.UTC(2026, 9, 27);
const token = (uid = "coach", edits = {}) => {
  const encoded = value => Buffer.from(JSON.stringify(value)).toString("base64url");
  return `${encoded({ alg: "RS256", kid: "firebase-test-key" })}.${encoded({ sub: uid, user_id: uid, aud: "volleyball-lab", iss: "https://securetoken.google.com/volleyball-lab", iat: startingTime / 1000 - 5, exp: startingTime / 1000 + 3600, ...edits })}.firebase-signature`;
};
function source() {
  return { ownerUid: "athlete", sharedWith: ["coach"], skill: "Serving", repCount: 4, formScore: 65,
    reps: Array.from({ length: 4 }, () => ({ cueScores: [
      { cueId: "ServeContactArmExtension", measured: 116, score: .3 },
      { cueId: "ServeTrunkLean", measured: 9, score: 1 },
    ] })) };
}
const value = input => input === null ? { nullValue: null } : Array.isArray(input) ? { arrayValue: input.length ? { values: input.map(value) } : {} }
  : typeof input === "object" ? { mapValue: { fields: fields(input) } }
  : typeof input === "string" ? { stringValue: input } : typeof input === "number" ? { doubleValue: input } : { booleanValue: input };
const fields = input => Object.fromEntries(Object.entries(input).map(([key, entry]) => [key, value(entry)]));
const valid = evidence => ({ overview: "Use the recorded positions to choose a focused next practice.",
  strengths: evidence.strengths.map(cueId => ({ cueId, explanation: "The recorded position matches the app reference for this cue." })),
  priorities: evidence.priorities.map(cueId => ({ cueId, explanation: "The recorded position is below the app reference. Use its approved practice tip." })) });

function fixture() {
  const db = database(); let time = startingTime; let calls = 0;
  let session = source(); let authorized = true; let fail = false;
  let effect = async () => {}; let rawSourceBody;
  const firestoreRequests = [];
  const currentToken = uid => token(uid, { iat: Math.floor(time / 1000) - 5, exp: Math.floor(time / 1000) + 3600 });
  const fetchImpl = async (url, init) => {
    const parsed = new URL(url); firestoreRequests.push({ url, authorization: init.headers.Authorization });
    assert.equal(parsed.hostname, "firestore.googleapis.com");
    assert.equal(parsed.searchParams.get("prettyPrint"), "false");
    assert.ok(parsed.pathname.startsWith("/v1/projects/volleyball-lab/databases/(default)/documents/"));
    if (![currentToken("coach"), currentToken("athlete")].includes(init.headers.Authorization.slice(7))) return Response.json({ error: { status: "UNAUTHENTICATED" } }, { status: 401 });
    if (parsed.pathname.includes("/aiSessionAccess/")) return Response.json({ error: { status: authorized ? "NOT_FOUND" : "PERMISSION_DENIED" } }, { status: authorized ? 404 : 403 });
    if (!session) return Response.json({ error: { status: "NOT_FOUND" } }, { status: 404 });
    const includesReps = parsed.searchParams.getAll("mask.fieldPaths").includes("reps");
    if (includesReps && rawSourceBody !== undefined) return new Response(rawSourceBody);
    const masked = includesReps ? session : Object.fromEntries(Object.entries(session).filter(([key]) => key !== "reps"));
    return Response.json({ fields: fields(masked) });
  };
  const worker = createWorker({ now: () => time, fetchImpl, generate: async ({ evidence, apiKey }) => {
    assert.equal(apiKey, "private-server-key"); calls++; await effect(); if (fail) throw Error("provider-private-error-key"); return valid(evidence);
  } });
  const env = { AI_DB: db, GROQ_API_KEY: "private-server-key", ALLOWED_ORIGINS: "https://volleyball-lab.web.app,http://localhost:5173" };
  const request = ({ generate = true, uid = "coach", auth = currentToken(uid), origin, data, ...extra } = {}) => new Request("https://ai.example/explainTrainingSession", {
    method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${auth}`, ...(origin ? { Origin: origin } : {}) },
    body: JSON.stringify({ data: data ?? { sessionId: "session", generate } }), ...extra,
  });
  return { db, env, worker, request, firestoreRequests, calls: () => calls, time: () => time,
    advance: ms => { time += ms; }, authorize: value => { authorized = value; }, replace: value => { session = value; }, rawBody: value => { rawSourceBody = value; }, fail: value => { fail = value; }, effect: action => { effect = action; } };
}
const body = response => response.json();
const rows = db => db.sqlite.prepare("SELECT * FROM ai_explanations").all();

test("Firestore uses edge-supported manual redirects and refuses redirected responses", async () => {
  let requests = 0;
  const fetchImpl = async (_url, init) => {
    requests++;
    if (init.redirect !== "manual") throw new TypeError("Workers supports manual or follow redirects only");
    return new Response(null, { status: 302, headers: { Location: "https://untrusted.example" } });
  };
  await assert.rejects(authorizedSession("session", { uid: "athlete", token: token("athlete") }, fetchImpl), error => error.status === "UNAVAILABLE");
  assert.equal(requests, 2);
});

test("callable protocol generates measured feedback and cached reads never call Groq", async () => {
  const f = fixture();
  const opening = await body(await f.worker.fetch(f.request({ generate: false }), f.env));
  assert.equal(opening.result.status, "empty"); assert.equal(f.calls(), 0);
  const first = await body(await f.worker.fetch(f.request(), f.env));
  assert.equal(first.result.status, "ready"); assert.equal(first.result.cached, false);
  assert.equal(first.result.evidence.cues[0].measuredReps, 4);
  const cached = await body(await f.worker.fetch(f.request({ uid: "athlete", generate: false }), f.env));
  assert.equal(cached.result.cached, true); assert.equal(f.calls(), 1);
  const stored = JSON.stringify(rows(f.db));
  for (const secret of ["private-server-key", "firebase-signature", '"ownerUid"', '"reps"', '"athlete"', '"coach"']) assert.ok(!stored.includes(secret));
});

test("forged, expired, wrong-project and absent tokens never access D1 or inference", async () => {
  for (const auth of ["missing", token("coach", { exp: startingTime / 1000 - 1 }), token("coach", { aud: "other-project" }), token("coach", { sub: "forged" })]) {
    const f = fixture(); const response = await f.worker.fetch(f.request({ auth }), f.env);
    assert.equal(response.status, 401); assert.equal(f.calls(), 0); assert.equal(rows(f.db).length, 0);
    assert.equal(f.db.sqlite.prepare("SELECT count(*) as n FROM ai_usage").get().n, 0);
  }
});

test("the virtual access gate blocks a coach before cached output or provider access", async () => {
  const f = fixture(); await f.worker.fetch(f.request(), f.env); f.authorize(false);
  const response = await f.worker.fetch(f.request(), f.env);
  assert.equal(response.status, 403); assert.equal(f.calls(), 1);
  assert.equal((await body(response)).error.status, "PERMISSION_DENIED");
});

test("source changes invalidate cache; sparse cues never trigger generation", async () => {
  const f = fixture(); await f.worker.fetch(f.request(), f.env);
  const changed = source(); changed.reps[0].cueScores[0].measured = 130; f.replace(changed);
  assert.equal((await body(await f.worker.fetch(f.request({ generate: false }), f.env))).result.status, "empty");
  changed.reps = changed.reps.slice(0, 1); changed.repCount = 1; f.replace(changed);
  assert.equal((await body(await f.worker.fetch(f.request(), f.env))).result.status, "insufficient");
  assert.equal(f.calls(), 1);
});

test("concurrent generation claims only one session lease and one quota attempt", async () => {
  const f = fixture(); let release; let started;
  const entered = new Promise(resolve => { started = resolve; });
  f.effect(() => new Promise(resolve => { release = resolve; started(); }));
  const first = f.worker.fetch(f.request(), f.env); await entered;
  const second = await f.worker.fetch(f.request({ uid: "athlete" }), f.env);
  assert.equal(second.status, 409); assert.equal(f.calls(), 1);
  release(); assert.equal((await first).status, 200);
  assert.equal(f.db.sqlite.prepare("SELECT sum(count) as n FROM ai_usage").get().n, 1);
});

test("daily quota and cooldown are atomic and do not leave abandoned leases", async () => {
  const f = fixture(); const store = new ExplanationStore(f.db);
  for (let index = 0; index < 20; index++) {
    const result = await store.reserve({ key: `session-${index}`, owner: "hashed-owner", actor: "hashed-caller", fingerprint: "fingerprint", lease: `lease-${index}`, now: f.time() });
    assert.equal(result.status, "reserved"); f.advance(15_000);
  }
  const denied = await store.reserve({ key: "extra", owner: "hashed-owner", actor: "hashed-caller", fingerprint: "fingerprint", lease: "extra-lease", now: f.time() });
  assert.equal(denied.status, "limited"); assert.equal(await store.get("extra"), null);
  f.advance(24 * 60 * 60 * 1000);
  assert.equal((await store.reserve({ key: "new-day", owner: "hashed-owner", actor: "hashed-caller", fingerprint: "fingerprint", lease: "tomorrow", now: f.time() })).status, "reserved");
  const cooldown = await store.reserve({ key: "too-fast", owner: "hashed-owner", actor: "hashed-caller", fingerprint: "fingerprint", lease: "too-fast", now: f.time() });
  assert.equal(cooldown.status, "limited"); assert.equal(await store.get("too-fast"), null);
});

test("provider errors release the lease, retain attempt quota, and hide internal details", async () => {
  const f = fixture(); f.fail(true);
  const response = await f.worker.fetch(f.request(), f.env);
  assert.equal(response.status, 503); assert.ok(!JSON.stringify(await body(response)).includes("private-error"));
  assert.equal(rows(f.db).length, 0); assert.equal(f.db.sqlite.prepare("SELECT count FROM ai_usage").get().count, 1);
  f.fail(false); f.advance(15_000);
  assert.equal((await f.worker.fetch(f.request(), f.env)).status, 200);
});

test("revocation, deletion or changed evidence during inference never returns an explanation", async () => {
  for (const change of [f => f.authorize(false), f => f.replace(null), f => { const changed = source(); changed.reps[0].cueScores[0].score = .7; f.replace(changed); }]) {
    const f = fixture(); f.effect(async () => change(f));
    const response = await f.worker.fetch(f.request(), f.env);
    assert.notEqual(response.status, 200); assert.equal(rows(f.db).length, 0);
  }
});

test("CORS allows the hosted portal and Android, while rejecting unknown websites", async () => {
  const f = fixture();
  const allowed = await f.worker.fetch(f.request({ generate: false, origin: "https://volleyball-lab.web.app" }), f.env);
  assert.equal(allowed.headers.get("access-control-allow-origin"), "https://volleyball-lab.web.app");
  const preflight = await f.worker.fetch(new Request("https://ai.example/explainTrainingSession", { method: "OPTIONS", headers: { Origin: "https://volleyball-lab.web.app" } }), f.env);
  assert.equal(preflight.status, 204); assert.ok(preflight.headers.get("access-control-allow-headers").includes("X-Firebase-AppCheck"));
  const denied = await f.worker.fetch(f.request({ origin: "https://untrusted.example" }), f.env);
  assert.equal(denied.status, 403); assert.equal(denied.headers.get("access-control-allow-origin"), null);
});

test("forged fields, oversized bodies, wrong routes and missing secret fail without inference", async () => {
  const f = fixture();
  for (const data of [{ sessionId: "session", generate: true, score: 100 }, { sessionId: "../other", generate: true }, { sessionId: "session", generate: "true" }]) assert.equal((await f.worker.fetch(f.request({ data }), f.env)).status, 400);
  assert.equal((await f.worker.fetch(f.request({ body: JSON.stringify({ data: "x".repeat(2100) }) }), f.env)).status, 400);
  assert.equal((await f.worker.fetch(new Request("https://ai.example/other"), f.env)).status, 404);
  delete f.env.GROQ_API_KEY;
  assert.equal((await f.worker.fetch(f.request(), f.env)).status, 503);
  assert.equal(f.calls(), 0); assert.equal(rows(f.db).length, 0);
});

test("cache TTL invalidates reads and scheduled purge removes expired anonymous rows", async () => {
  const f = fixture(); await f.worker.fetch(f.request(), f.env); f.advance(CACHE_TTL_MS + 1);
  assert.equal((await body(await f.worker.fetch(f.request({ generate: false }), f.env))).result.status, "empty");
  await f.worker.scheduled({}, f.env); assert.equal(rows(f.db).length, 0);
  f.advance(CACHE_TTL_MS); await f.worker.scheduled({}, f.env);
  assert.equal(f.db.sqlite.prepare("SELECT count(*) as n FROM ai_usage").get().n, 0);
});

test("invalid provider prose is rejected even when a replacement adapter returns it", async () => {
  const f = fixture();
  const worker = createWorker({ now: f.time, fetchImpl: async (url, init) => {
    assert.equal(init.headers.Authorization, `Bearer ${token()}`);
    return url.includes("/aiSessionAccess/") ? Response.json({ error: { status: "NOT_FOUND" } }, { status: 404 }) : Response.json({ fields: fields(source()) });
  }, generate: async ({ evidence }) => ({ ...valid(evidence), overview: "Your score is 999." }) });
  assert.equal((await worker.fetch(f.request(), f.env)).status, 503); assert.equal(rows(f.db).length, 0);
});

test("over-100-rep metadata is rejected before full source transfer or generation", async () => {
  const f = fixture(); const large = source(); large.repCount = 101; f.replace(large);
  const response = await f.worker.fetch(f.request(), f.env);
  assert.equal(response.status, 400);
  assert.equal((await body(response)).error.status, "FAILED_PRECONDITION");
  assert.equal(f.calls(), 0);
  assert.ok(!f.firestoreRequests.some(request => new URL(request.url).searchParams.getAll("mask.fieldPaths").includes("reps")));
});

test("forged small repCount cannot bypass actual rep-array or source-byte bounds", async () => {
  const tooMany = fixture(); const forged = source(); forged.repCount = 1; forged.reps = Array.from({ length: 101 }, () => source().reps[0]); tooMany.replace(forged);
  const countResponse = await tooMany.worker.fetch(tooMany.request(), tooMany.env);
  assert.equal(countResponse.status, 400); assert.equal((await body(countResponse)).error.status, "FAILED_PRECONDITION");
  assert.equal(tooMany.calls(), 0);
  const huge = fixture(); huge.rawBody("x".repeat(128 * 1024 + 1));
  const bytesResponse = await huge.worker.fetch(huge.request(), huge.env);
  assert.equal(bytesResponse.status, 400);
  assert.equal((await body(bytesResponse)).error.message, "This session is too large for an AI explanation. Try a shorter practice set.");
  assert.equal(huge.calls(), 0); assert.equal(rows(huge.db).length, 0);
});

test("concurrent different sessions still grant only one caller attempt in the cooldown", async () => {
  const f = fixture(); const store = new ExplanationStore(f.db);
  const reserve = key => store.reserve({ key, owner: "hashed-owner", actor: "same-caller", fingerprint: "fingerprint", lease: key, now: f.time() });
  const results = await Promise.all([reserve("one"), reserve("two")]);
  assert.deepEqual(results.map(result => result.status).sort(), ["limited", "reserved"]);
  assert.equal(f.db.sqlite.prepare("SELECT count FROM ai_usage").get().count, 1);
  assert.equal(rows(f.db).length, 1);
});

test("an empty saved session returns insufficient instead of attempting inference", async () => {
  const f = fixture(); const empty = source(); empty.repCount = 0; empty.reps = []; f.replace(empty);
  const response = await f.worker.fetch(f.request(), f.env);
  assert.equal(response.status, 200); assert.equal((await body(response)).result.status, "insufficient");
  assert.equal(f.calls(), 0); assert.equal(rows(f.db).length, 0);
});
