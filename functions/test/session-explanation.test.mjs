import test from "node:test";
import assert from "node:assert/strict";
import { createSessionExplainer } from "../session-explanation.mjs";
import { DEFAULT_NVIDIA_MODEL, explanationHash, requestNvidiaExplanation, sessionEvidence, validateExplanation } from "../session-explanation-policy.mjs";

function session() {
  return { id: "session", ownerUid: "athlete", sharedWith: ["coach"], skill: "Serving", repCount: 5, formScore: 66,
    reps: Array.from({ length: 5 }, (_, index) => ({ cueScores: [
      ...(index < 3 ? [{ cueId: "ServeContactArmExtension", measured: 116, score: .3 }, { cueId: "ServeReachHeight", measured: 98, score: .34 }] : []),
      { cueId: "ServeTrunkLean", measured: 9, score: 1 },
      ...(index < 2 ? [{ cueId: "ServeTossArmLift", measured: 47, score: .1 }] : []),
    ] })) };
}
function valid(evidence) {
  return { overview: "The recorded posture provides a useful starting point for focused practice.",
    strengths: evidence.strengths.map(cueId => ({ cueId, explanation: "The recorded position matches the app reference for this cue." })),
    priorities: evidence.priorities.map(cueId => ({ cueId, explanation: "This cue sits below the app reference. Use its approved tip to guide the next attempt." })) };
}
function harness() {
  const rows = new Map([["sessions/session", session()], ["users/coach", { role: "COACH" }], ["roster/coach/members/athlete", { status: "Active" }]]);
  let time = 1_800_000_000_000;
  let calls = 0;
  let fail = false;
  let beforeTransaction = () => {};
  let afterGenerate = () => {};
  const reference = path => ({ path, collection: name => ({ doc: id => reference(`${path}/${name}/${id}`) }), get: async () => snapshot(path) });
  const snapshot = path => ({ exists: rows.has(path), data: () => rows.has(path) ? structuredClone(rows.get(path)) : undefined });
  const db = { doc: reference, async runTransaction(action) {
    beforeTransaction();
    const mutations = [];
    const result = await action({ get: ref => Promise.resolve(snapshot(ref.path)), set: (ref, data) => mutations.push(() => rows.set(ref.path, structuredClone(data))), delete: ref => mutations.push(() => rows.delete(ref.path)) });
    mutations.forEach(apply => apply()); return result;
  } };
  const run = createSessionExplainer({ db, apiKey: () => "server-only-test-key", modelName: () => DEFAULT_NVIDIA_MODEL, now: () => time,
    generate: async ({ evidence }) => { calls++; if (fail) throw Error("nvidia_unavailable"); afterGenerate(); return valid(evidence); } });
  return { rows, run, calls: () => calls, advance: value => { time += value; }, fail: value => { fail = value; },
    beforeTransaction: action => { beforeTransaction = action; }, afterGenerate: action => { afterGenerate = action; } };
}
const request = (uid = "coach", generate = true) => ({ auth: { uid }, data: { sessionId: "session", generate } });
const rejectsCode = (promise, code) => assert.rejects(promise, error => error.code === code);

test("evidence selects supported priorities and keeps sparse cues out of findings", () => {
  const evidence = sessionEvidence(session());
  assert.deepEqual(evidence.strengths, ["ServeTrunkLean"]);
  assert.deepEqual(evidence.priorities, ["ServeContactArmExtension", "ServeReachHeight"]);
  assert.ok(evidence.limitedCues.includes("ServeTossArmLift"));
  assert.equal(evidence.cues[0].measured, 116);
  const serialized = JSON.stringify(evidence);
  for (const privateValue of ['"ownerUid"', '"sharedWith"', '"athlete"', '"sessionId"']) assert.ok(!serialized.includes(privateValue));
});
test("null and nonfinite measurements are not silently averaged as zero", () => {
  const source = session();
  source.reps[0].cueScores[0].measured = null;
  source.reps[1].cueScores[0].measured = NaN;
  const evidence = sessionEvidence(source);
  assert.equal(evidence.cues[0].measured, 116);
  assert.equal(evidence.cues[0].measuredReps, 1);
  assert.ok(!evidence.priorities.includes("ServeContactArmExtension"));
});
test("validation rejects invented digits, changed cue ids, duplicates and medical claims", () => {
  const evidence = sessionEvidence(session());
  assert.deepEqual(validateExplanation(JSON.stringify(valid(evidence)), evidence), valid(evidence));
  for (const mutate of [value => { value.overview = "Your score is 99."; }, value => { value.priorities[0].cueId = "ServeTossArmLift"; }, value => { value.priorities[1] = value.priorities[0]; }, value => { value.overview = "This prevents an injury."; }, value => { value.overview = "I watched the athlete serve."; }, value => { value.extra = "unapproved"; }]) {
    const output = valid(evidence); mutate(output); assert.throws(() => validateExplanation(JSON.stringify(output), evidence));
  }
});
test("provider request uses server key and anonymous evidence, then validates response", async () => {
  const evidence = sessionEvidence(session());
  let captured;
  const output = await requestNvidiaExplanation({ apiKey: "test-key", model: DEFAULT_NVIDIA_MODEL, evidence, fetchImpl: async (url, init) => { captured = { url, ...init }; return { ok: true, json: async () => ({ choices: [{ finish_reason: "stop", message: { content: JSON.stringify(valid(evidence)) } }] }) }; } });
  assert.equal(captured.url, "https://integrate.api.nvidia.com/v1/chat/completions");
  assert.equal(captured.headers.Authorization, "Bearer test-key");
  const payload = JSON.parse(captured.body);
  assert.equal(payload.messages[0].role, "system");
  assert.deepEqual(JSON.parse(payload.messages[1].content), evidence);
  assert.equal(output.priorities.length, 2);
});
test("provider throttling and truncated output never return a fabricated explanation", async () => {
  const args = { apiKey: "test", model: DEFAULT_NVIDIA_MODEL, evidence: sessionEvidence(session()) };
  await assert.rejects(requestNvidiaExplanation({ ...args, fetchImpl: async () => ({ ok: false, status: 429 }) }), /nvidia_rate_limited/);
  await assert.rejects(requestNvidiaExplanation({ ...args, fetchImpl: async () => ({ ok: true, json: async () => ({ choices: [{ finish_reason: "length", message: { content: "{}" } }] }) }) }), /incomplete_explanation/);
});
test("signed-out callers, forged fields and unrelated accounts are blocked before inference", async () => {
  const h = harness();
  await rejectsCode(h.run({ data: { sessionId: "session", generate: true } }), "unauthenticated");
  await rejectsCode(h.run({ ...request(), data: { sessionId: "session", generate: true, score: 100 } }), "invalid-argument");
  await rejectsCode(h.run(request("stranger")), "permission-denied");
  assert.equal(h.calls(), 0);
});
test("session owners and actively connected shared coaches can generate", async () => {
  for (const uid of ["athlete", "coach"]) { const h = harness(); assert.equal((await h.run(request(uid))).status, "ready"); assert.equal(h.calls(), 1); }
});
test("ended connections and deleting accounts cannot generate or read cache", async () => {
  const ended = harness(); ended.rows.set("roster/coach/members/athlete", { status: "Ended" });
  await rejectsCode(ended.run(request()), "permission-denied");
  const deleted = harness(); deleted.rows.set("accountDeletions/coach", { status: "deleting" });
  await rejectsCode(deleted.run(request()), "not-found");
  assert.equal(ended.calls() + deleted.calls(), 0);
});
test("opening a session never invokes the provider; valid cached output is reused", async () => {
  const h = harness();
  assert.equal((await h.run(request("coach", false))).status, "empty");
  assert.equal(h.calls(), 0);
  await h.run(request());
  assert.equal((await h.run(request("athlete", false))).cached, true);
  assert.equal((await h.run(request())).cached, true);
  assert.equal(h.calls(), 1);
  assert.ok(!JSON.stringify([...h.rows]).includes("server-only-test-key"));
});
test("changing measured data invalidates the cached explanation", async () => {
  const h = harness(); await h.run(request());
  const changed = h.rows.get("sessions/session"); changed.reps[0].cueScores[0].score = .8;
  assert.equal((await h.run(request("coach", false))).status, "empty");
  assert.notEqual(explanationHash(sessionEvidence(changed), DEFAULT_NVIDIA_MODEL), explanationHash(sessionEvidence(session()), DEFAULT_NVIDIA_MODEL));
});
test("sparse sessions return insufficient without using quota or provider", async () => {
  const h = harness(); h.rows.get("sessions/session").reps = session().reps.slice(0, 1);
  assert.equal((await h.run(request())).status, "insufficient");
  assert.equal(h.calls(), 0);
  assert.equal(h.rows.has("aiUsage/coach"), false);
});
test("daily limit and in-flight leases prevent extra provider calls", async () => {
  const h = harness(); h.rows.set("aiUsage/coach", { day: new Date(1_800_000_000_000).toISOString().slice(0, 10), count: 20 });
  await rejectsCode(h.run(request()), "resource-exhausted");
  assert.equal(h.calls(), 0);
  const busy = harness(); busy.rows.set("sessions/session/aiExplanations/session-explanation-v1", { leaseUntil: 1_800_000_010_000 });
  await rejectsCode(busy.run(request()), "aborted"); assert.equal(busy.calls(), 0);
});
test("provider failure clears the lease and supports a later retry", async () => {
  const h = harness(); h.fail(true);
  await rejectsCode(h.run(request()), "unavailable");
  assert.equal(h.rows.has("sessions/session/aiExplanations/session-explanation-v1"), false);
  h.fail(false); h.advance(16_000);
  assert.equal((await h.run(request())).status, "ready");
});

test("deletion starting before the lease transaction cannot recreate usage or caches", async () => {
  const h = harness();
  h.beforeTransaction(() => h.rows.set("accountDeletions/coach", { status: "deleting" }));
  await rejectsCode(h.run(request()), "permission-denied");
  assert.equal(h.calls(), 0);
  assert.equal(h.rows.has("aiUsage/coach"), false);
  assert.equal(h.rows.has("sessions/session/aiExplanations/session-explanation-v1"), false);
});

test("session deletion or revoked access during inference prevents a ready cache", async () => {
  for (const change of [
    h => h.rows.delete("sessions/session"),
    h => h.rows.set("roster/coach/members/athlete", { status: "Ended" }),
    h => h.rows.set("accountDeletions/athlete", { status: "deleting" }),
    h => h.rows.set("users/coach", { role: "PLAYER" }),
  ]) {
    const h = harness(); h.afterGenerate(() => change(h));
    await assert.rejects(h.run(request()));
    assert.equal(h.rows.has("sessions/session/aiExplanations/session-explanation-v1"), false);
  }
});
