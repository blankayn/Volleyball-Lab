import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { liveEvidence, validateLiveAdvice, generateLiveAdvice, liveCoachingRequest } from "../src/live-coaching.mjs";

const now = Date.UTC(2026, 10, 8);
const data = () => ({ skill: "Serving", angle: "Front", voice: true,
  reps: Array.from({ length: 5 }, () => [{ cueId: "ServeContactArmExtension", measured: 120 }]) });
const advice = { cueId: "ServeContactArmExtension", text: "Your hitting arm was below the practice range. Reach through contact with a straighter arm." };
const token = () => {
  const encode = value => Buffer.from(JSON.stringify(value)).toString("base64url");
  return `${encode({ alg: "RS256", kid: "test" })}.${encode({ sub: "athlete", aud: "volleyball-lab", iss: "https://securetoken.google.com/volleyball-lab", exp: now / 1000 + 3600, iat: now / 1000 - 10 })}.test-signature`;
};
function fixture() {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec(readFileSync(new URL("../migrations/0002_live_coaching.sql", import.meta.url), "utf8"));
  const db = { prepare(sql) { let args; return { bind(...value) { args = value; return this; }, async run() { return { meta: { changes: Number(sqlite.prepare(sql).run(...args).changes) } }; } }; } };
  const env = { AI_DB: db, GROQ_API_KEY: "server-only-test-key" };
  let authenticated = true; let speechCalls = 0; let textCalls = 0; let speechFailure = null; let currentTime = now;
  const fetchImpl = async (url, options) => {
    if (new URL(url).hostname === "firestore.googleapis.com") {
      assert.equal(new URL(url).pathname.endsWith("/users/athlete"), true);
      return authenticated ? Response.json({ fields: {} }) : Response.json({ error: { status: "PERMISSION_DENIED" } }, { status: 403 });
    }
    assert.equal(url, "https://api.groq.com/openai/v1/audio/speech");
    speechCalls++;
    const body = JSON.parse(options.body);
    assert.equal(body.model, "canopylabs/orpheus-v1-english"); assert.equal(body.voice, "troy");
    assert.ok(body.input.length <= 200); assert.equal(body.response_format, "wav");
    if (speechFailure) return Response.json({ error: { code: speechFailure.code, message: "provider-private-message" } }, { status: speechFailure.status });
    const wav = Buffer.alloc(44); wav.write("RIFF"); wav.write("WAVE", 8);
    return new Response(wav, { headers: { "Content-Type": "audio/wav" } });
  };
  const generate = async () => { textCalls++; return advice; };
  const request = (body = data(), path = "/coachLiveStage", bearer = token()) => new Request(`https://worker.example${path}`, {
    method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${bearer}` }, body: JSON.stringify({ data: body }),
  });
  const run = (req = request(), generator = generate) => liveCoachingRequest(req, env, {}, { fetchImpl, now: () => currentTime, generate: generator });
  return { env, run, request, sqlite, deny: () => { authenticated = false; }, failSpeech: (status = 429, code) => { speechFailure = { status, code }; },
    calls: () => ({ text: textCalls, speech: speechCalls }), advance: ms => { currentTime += ms; } };
}

test("live evidence requires repeated finite completed measurements and a catalog cue", () => {
  assert.equal(liveEvidence(data()).id, advice.cueId);
  assert.equal(liveEvidence({ ...data(), reps: [[], [], [], [], [{ cueId: advice.cueId, measured: 100 }]] }), null);
  assert.equal(liveEvidence({ ...data(), reps: Array.from({ length: 5 }, () => [{ cueId: advice.cueId, measured: 170 }]) }), null);
  for (const edit of [{ skill: "invented" }, { angle: "Unknown" }, { voice: "yes" }, { extra: "prompt" },
    { reps: [[{ cueId: advice.cueId, measured: Infinity }], [], []] },
    { reps: [[{ cueId: "SpikeArmExtension", measured: 80 }], [], []] },
    { reps: [[{ cueId: advice.cueId, measured: 100 }, { cueId: advice.cueId, measured: 100 }], [], []] }]) {
    assert.throws(() => liveEvidence({ ...data(), ...edit }));
  }
});

test("advice validator rejects wrong cues, fabricated numbers and unsolicited observations", () => {
  assert.deepEqual(validateLiveAdvice(advice, advice.cueId), advice);
  for (const edit of [{ cueId: "SpikeArmExtension" }, { text: "Your score is 99." }, { text: "I watched your video." },
    { text: "You improved every rep." }, { text: "[cheerful] You did well." }, { text: "x".repeat(201) }, { unknown: true }]) {
    assert.throws(() => validateLiveAdvice({ ...advice, ...edit }, advice.cueId));
  }
});

test("Groq receives only the selected approved cue, never raw rep measurements", async () => {
  const cue = liveEvidence(data()); let called = false;
  const result = await generateLiveAdvice({ apiKey: "secret", cue, skill: "Serving", fetchImpl: async (url, options) => {
    called = true; assert.equal(url, "https://api.groq.com/openai/v1/chat/completions");
    const body = JSON.parse(options.body);
    assert.equal(body.response_format.json_schema.strict, true);
    assert.equal(options.headers.Authorization, "Bearer secret");
    assert.ok(!options.body.includes("120")); assert.ok(!options.body.includes("secret"));
    return Response.json({ choices: [{ message: { content: JSON.stringify(advice) } }] });
  } });
  assert.equal(called, true); assert.deepEqual(result, advice);
});

test("verified Firebase access precedes both quotas and inference", async () => {
  const f = fixture(); f.deny();
  assert.equal((await f.run()).status, 403);
  assert.deepEqual(f.calls(), { text: 0, speech: 0 });
  assert.equal(f.sqlite.prepare("SELECT count(*) n FROM live_coaching_usage").get().n, 0);
  assert.equal((await f.run(f.request(data(), "/coachLiveStage", "forged"))).status, 401);
});

test("free-tier voice is opt-in at the operator and never inferred from a Groq key", async () => {
  const f = fixture(); const result = await (await f.run()).json();
  assert.equal(result.result.source, "groq"); assert.equal(result.result.speechBase64, null);
  assert.deepEqual(f.calls(), { text: 1, speech: 0 });
});

test("an authorized free voice produces one buffered WAV and no parallel speech fragments", async () => {
  const f = fixture(); f.env.GROQ_VOICE_FREE_TIER_CONFIRMED = "true";
  const result = await (await f.run()).json();
  assert.equal(Buffer.from(result.result.speechBase64, "base64").toString("ascii", 0, 4), "RIFF");
  assert.deepEqual(f.calls(), { text: 1, speech: 1 });
});

test("voice quota and provider failure immediately fall back without retry or losing text", async () => {
  const f = fixture(); f.env.GROQ_VOICE_FREE_TIER_CONFIRMED = "true"; f.failSpeech();
  assert.equal((await (await f.run()).json()).result.speechBase64, null);
  assert.equal((await (await f.run()).json()).result.speechBase64, null);
  assert.equal(f.calls().speech, 1);
  assert.equal(f.calls().text, 1);
});

test("shared organization speech guard prevents exceeding the app free request budget", async () => {
  const f = fixture(); f.env.GROQ_VOICE_FREE_TIER_CONFIRMED = "true";
  const day = new Date(now).toISOString().slice(0, 10);
  f.sqlite.prepare("INSERT INTO live_coaching_usage VALUES ('organization','voice',?,90,?)").run(day, now - 60_000);
  const result = await (await f.run()).json();
  assert.equal(result.result.speechBase64, null); assert.equal(f.calls().speech, 0);
});

test("provider model-terms denial retains advice without exposing provider messages or credentials", async () => {
  const f = fixture(); f.env.GROQ_VOICE_FREE_TIER_CONFIRMED = "true"; f.failSpeech(400, "model_terms_required");
  const result = await (await f.run()).json();
  assert.equal(result.result.voiceStatus, "provider_400_model_terms_required");
  assert.equal(result.result.speechBase64, null); assert.equal(result.result.source, "groq");
  assert.ok(!JSON.stringify(result).includes("provider-private-message"));
  assert.ok(!JSON.stringify(result).includes(f.env.GROQ_API_KEY));
});

test("invalid AI output keeps a local approved tip and sparse evidence never calls a provider", async () => {
  const f = fixture();
  const result = await (await f.run(f.request(), async () => ({ ...advice, text: "Your score is 100." }))).json();
  assert.equal(result.result.source, "local"); assert.ok(result.result.text.includes("straight hitting arm"));
  const empty = await (await f.run(f.request({ ...data(), reps: [[], [], []] }))).json();
  assert.equal(empty.result.status, "insufficient");
});

test("voice preview only speaks the fixed sample and cannot become an arbitrary text proxy", async () => {
  const f = fixture(); f.env.GROQ_VOICE_FREE_TIER_CONFIRMED = "true";
  const response = await (await f.run(f.request({}, "/coachVoicePreview"))).json();
  assert.ok(response.result.text.startsWith("I'm here to coach you.")); assert.equal(f.calls().speech, 1);
  assert.equal((await f.run(f.request({ text: "arbitrary" }, "/coachVoicePreview"))).status, 400);
});
