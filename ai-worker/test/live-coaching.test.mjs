import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { liveEvidence, liveStage, fallbackAdvice, validateLiveAdvice, generateLiveAdvice, liveCoachingRequest,
  livePrompt, LIVE_DIRECTION, LIVE_FEW_SHOTS, LIVE_SYSTEM_PROMPT } from "../src/live-coaching.mjs";
import { SESSION_CUES } from "../../functions/session-explanation-policy.mjs";

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
  let lastGenerate = null; let failStorage = false;
  const stored = db.prepare.bind(db);
  db.prepare = sql => { if (failStorage) throw Error("d1 unavailable"); return stored(sql); };
  const generate = async input => { textCalls++; lastGenerate = input; return advice; };
  const request = (body = data(), path = "/coachLiveStage", bearer = token()) => new Request(`https://worker.example${path}`, {
    method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${bearer}` }, body: JSON.stringify({ data: body }),
  });
  const run = (req = request(), generator = generate) => liveCoachingRequest(req, env, {}, { fetchImpl, now: () => currentTime, generate: generator });
  return { env, run, request, sqlite, deny: () => { authenticated = false; }, failSpeech: (status = 429, code) => { speechFailure = { status, code }; },
    calls: () => ({ text: textCalls, speech: speechCalls }), advance: ms => { currentTime += ms; },
    generated: () => lastGenerate, breakStorage: () => { failStorage = true; } };
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

test("equipment advice falls back to posture copy before speech is generated", async () => {
  for (const text of ["Meet the ball above your head.", "Try WALL rebounds.", "Toss above your forehead.", "Ask for partner feeds."]) {
    assert.throws(() => validateLiveAdvice({ ...advice, text }, advice.cueId));
  }
  const f = fixture();
  const result = (await (await f.run(f.request(), async () => ({ ...advice, text: "Meet the ball above your head." }))).json()).result;
  assert.equal(result.source, "local");
  assert.match(result.text, /extend your elbow/i);
  assert.doesNotMatch(result.text, /\b(ball|wall|toss|feed)\b/i);
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
  assert.equal(result.result.source, "local"); assert.ok(result.result.text.includes("extend your elbow"));
  const empty = await (await f.run(f.request({ ...data(), reps: [[], [], []] }))).json();
  assert.equal(empty.result.status, "insufficient");
});

test("voice preview only speaks the fixed sample and cannot become an arbitrary text proxy", async () => {
  const f = fixture(); f.env.GROQ_VOICE_FREE_TIER_CONFIRMED = "true";
  const response = await (await f.run(f.request({}, "/coachVoicePreview"))).json();
  assert.ok(response.result.text.startsWith("I'm here to coach you.")); assert.equal(f.calls().speech, 1);
  assert.equal((await f.run(f.request({ text: "arbitrary" }, "/coachVoicePreview"))).status, 400);
});

const context = (edit = {}) => ({ v: 1, turn: "break", breakIndex: 0, experience: "New", isAthleteFocus: true, corrected: [], ...edit });
const kneeReps = (value, count = 5) => Array.from({ length: count }, () => [{ cueId: "PassKneeFlexion", measured: value }]);

test("a tip always points the way the measurements missed", () => {
  const deep = liveEvidence({ skill: "Receiving", angle: "Side", voice: false, reps: kneeReps(80) });
  const straight = liveEvidence({ skill: "Receiving", angle: "Side", voice: false, reps: kneeReps(175) });
  assert.equal(deep.position, "below"); assert.match(deep.tip, /Rise/);
  assert.equal(straight.position, "above"); assert.match(straight.tip, /Bend your knees/);
  assert.notEqual(deep.tip, straight.tip);
  // Every catalog cue has wording for both directions, and nothing was added to the shared catalog.
  for (const cues of Object.values(SESSION_CUES)) for (const cue of cues) {
    assert.deepEqual(Object.keys(cue).sort(), ["id", "label", "target", "tip", "unit"]);
    const wording = LIVE_DIRECTION[cue.id];
    for (const side of ["below", "above"]) assert.ok(wording.noun && wording[side].saw && wording[side].tip, cue.id);
  }
});

test("every local fallback reads as a sentence and passes the same validator as the provider", () => {
  const seen = new Set();
  for (const [skill, cues] of Object.entries(SESSION_CUES)) cues.forEach((cue, index) => {
    for (const [position, value] of [["below", cue.target[0] - (cue.unit === "deg" ? 40 : 0.5)], ["above", cue.target[1] + (cue.unit === "deg" ? 40 : 0.5)]]) {
      if (Math.abs(value) > (cue.unit === "deg" ? 180 : 5)) continue;
      for (const breakIndex of [0, 1, 2]) {
        const stage = liveStage({ skill, angle: "Front", voice: false, context: context({ breakIndex }),
          reps: Array.from({ length: 4 }, () => [{ cueId: cue.id, measured: value }]) });
        assert.equal(stage.fault.id, cue.id); assert.equal(stage.fault.position, position);
        const fallback = fallbackAdvice(stage);
        assert.equal(fallback.kind, "correction");
        assert.deepEqual(validateLiveAdvice({ cueId: cue.id, text: fallback.text }, cue.id), { cueId: cue.id, text: fallback.text });
        assert.match(fallback.text, /^(On that view your|Your|From that angle your) [a-z ]+\. [A-Z]/);
        assert.doesNotMatch(fallback.text, /was (below|above) the practice range/);
        seen.add(fallback.text.split(" ")[0]);
      }
    }
  });
  assert.deepEqual([...seen].sort(), ["From", "On", "Your"]);
});

test("only the legacy four keys or five keys with a closed context are accepted", () => {
  const base = { skill: "Receiving", angle: "Side", voice: false, reps: kneeReps(175) };
  assert.equal(liveStage(base).context, null);
  assert.deepEqual(liveStage({ ...base, context: context({ corrected: ["PassKneeFlexion"] }) }).context,
    { turn: "break", breakIndex: 0, experience: "New", isAthleteFocus: true, corrected: ["PassKneeFlexion"] });
  assert.equal(liveStage({ ...base, context: context({ turn: "closing", experience: null, breakIndex: 2 }) }).context.turn, "closing");
  for (const bad of [{ v: 2 }, { turn: "opening" }, { breakIndex: 3 }, { breakIndex: 0.5 }, { experience: "Elite" },
    { isAthleteFocus: "yes" }, { corrected: ["ServeReachHeight"] }, { corrected: ["PassKneeFlexion", "PassKneeFlexion"] },
    { corrected: ["PassKneeFlexion", "PassTrunkAngle", "PassShouldersLevel", "PassPlatformStraight", "PassKneeFlexion"] },
    { name: "Maya" }, { goal: "Make the team" }, { note: "Coach said bend more" }]) {
    assert.throws(() => liveStage({ ...base, context: context(bad) }), JSON.stringify(bad));
  }
  assert.throws(() => liveStage({ ...base, context: null }));
  assert.throws(() => liveStage({ ...base, context: "break" }));
  assert.throws(() => liveStage({ ...base, profile: { name: "Maya" } }));
  const { v, ...missing } = context();
  assert.throws(() => liveStage({ ...base, context: missing }));
});

test("the server derives trend, miss share, late drop and a strength from the reps it validated", () => {
  const reps = values => values.map(v => [{ cueId: "PassKneeFlexion", measured: v }, { cueId: "PassPlatformStraight", measured: 172 }]);
  const closer = liveStage({ skill: "Receiving", angle: "Side", voice: false, context: context(), reps: reps([180, 180, 180, 155, 155, 155]) });
  assert.equal(closer.fault.trend, "closer"); assert.equal(closer.fault.missShare, "half");
  assert.equal(closer.strength.id, "PassPlatformStraight"); assert.equal(closer.strength.noun, "elbows");
  const further = liveStage({ skill: "Receiving", angle: "Side", voice: false, context: context(), reps: reps([150, 150, 150, 175, 178, 180]) });
  assert.equal(further.fault.trend, "further"); assert.equal(further.fault.lateDrop, true);
  const steady = liveStage({ skill: "Receiving", angle: "Side", voice: false, context: context(), reps: reps([175, 175, 175, 175]) });
  assert.equal(steady.fault.trend, "steady"); assert.equal(steady.fault.missShare, "most"); assert.equal(steady.fault.lateDrop, false);
  assert.equal(liveStage({ skill: "Receiving", angle: "Side", voice: false, context: context(), reps: reps([175, 175, 175]) }).fault.trend, "unknown");
  // A jump-height proxy is never offered as a strength.
  const jump = liveStage({ skill: "Spiking", angle: "Front", voice: false, context: context({ corrected: [] }),
    reps: Array.from({ length: 4 }, () => [{ cueId: "SpikeJumpHeight", measured: 0.5 }]) });
  assert.equal(jump.strength, null); assert.equal(fallbackAdvice(jump), null);
});

test("the provider receives closed summaries only: no measurements, names or free text", async () => {
  const stage = liveStage({ skill: "Receiving", angle: "Side", voice: false,
    context: context({ corrected: ["PassKneeFlexion"], experience: "Developing" }),
    reps: [173.25, 174.5, 176.75, 171.5].map(v => [{ cueId: "PassKneeFlexion", measured: v }, { cueId: "PassPlatformStraight", measured: 171.125 }]) });
  let sent;
  await generateLiveAdvice({ apiKey: "secret", cue: stage.fault, skill: "Receiving", strength: stage.strength, context: stage.context,
    fetchImpl: async (url, options) => { sent = options.body;
      return Response.json({ choices: [{ message: { content: JSON.stringify({ cueId: "PassKneeFlexion", text: "Sit a little lower." }) } }] }); } });
  const body = JSON.parse(sent);
  const summary = JSON.parse(body.messages.at(-1).content);
  assert.deepEqual(Object.keys(summary).sort(), ["alreadyHeard", "breakIndex", "cueId", "experience", "isAthleteFocus", "kind",
    "lateDrop", "missShare", "noun", "position", "saw", "skill", "strengthNoun", "tip", "trend", "turn"]);
  assert.deepEqual(summary, livePrompt({ cue: stage.fault, skill: "Receiving", strength: stage.strength, context: stage.context }));
  assert.equal(summary.alreadyHeard, true); assert.equal(summary.strengthNoun, "elbows"); assert.equal(summary.experience, "Developing");
  for (const raw of ["173.25", "174.5", "176.75", "171.5", "171.125", "secret", "measuredReps", "severity", "target\":"]) assert.ok(!sent.includes(raw), raw);
  // The only number is the closed break index.
  assert.doesNotMatch(body.messages.at(-1).content.replace(/"breakIndex":[0-2],/, ""), /\d/);
  // Persona, few-shot pairs, then the summary.
  assert.equal(body.messages[0].content, LIVE_SYSTEM_PROMPT);
  assert.match(LIVE_SYSTEM_PROMPT, /^You are a volleyball coach talking to your athlete at a short break\./);
  assert.equal(body.messages.length, 2 + LIVE_FEW_SHOTS.length * 2);
});

test("few-shot examples obey the same rules the provider is held to", () => {
  assert.deepEqual(LIVE_FEW_SHOTS.map(shot => shot.input.kind), ["correction", "correction", "praise"]);
  assert.ok(LIVE_FEW_SHOTS[1].input.strengthNoun);
  for (const shot of LIVE_FEW_SHOTS) {
    assert.deepEqual(validateLiveAdvice(shot.output, shot.input.cueId, { allowProgress: shot.input.trend === "closer" }), shot.output);
    assert.deepEqual(Object.keys(shot.input).sort(), Object.keys(livePrompt({ cue: { id: shot.input.cueId, position: "below", tip: "" }, skill: shot.input.skill })).sort());
  }
});

test("progress wording needs a server-derived closer trend and stays banned otherwise", () => {
  const cueId = "PassKneeFlexion";
  const progress = { cueId, text: "Your knees are getting closer. Sit a little lower." };
  assert.throws(() => validateLiveAdvice(progress, cueId));
  assert.deepEqual(validateLiveAdvice(progress, cueId, { allowProgress: true }), progress);
  // "closer" as a direction is ordinary coaching, not a progress claim.
  assert.doesNotThrow(() => validateLiveAdvice({ cueId, text: "Bring your hands slightly closer." }, cueId));
  for (const text of ["You fixed it.", "That was perfect.", "You always do this.", "Good on every rep.", "Same as last session.",
    "You have improved.", "Your score is 9."]) {
    assert.throws(() => validateLiveAdvice({ cueId, text }, cueId, { allowProgress: true }), text);
  }
});

test("a clean stage earns a praise turn only for a client that sent context", async () => {
  const clean = { skill: "Receiving", angle: "Front", voice: false, reps: Array.from({ length: 4 }, () => [{ cueId: "PassPlatformStraight", measured: 172 }]) };
  const f = fixture();
  assert.equal((await (await f.run(f.request(clean))).json()).result.status, "insufficient");
  assert.equal(f.calls().text, 0);
  const praise = { cueId: "PassPlatformStraight", text: "Your elbows stayed long through that view. Keep that same shape." };
  const result = (await (await f.run(f.request({ ...clean, context: context() }), async input => {
    assert.equal(input.kind, "praise"); assert.equal(input.cue.id, "PassPlatformStraight"); return praise; })).json()).result;
  assert.equal(result.status, "ready"); assert.equal(result.kind, "praise"); assert.equal(result.source, "groq");
  assert.equal(result.text, praise.text);
  const local = fixture(); delete local.env.GROQ_API_KEY;
  const fallback = (await (await local.run(local.request({ ...clean, context: context() }))).json()).result;
  assert.equal(fallback.kind, "praise"); assert.equal(fallback.source, "local");
  assert.equal(fallback.text, "Your elbows held in range through that view. Keep that shape.");
});

test("legacy four-key requests still succeed and now report their kind", async () => {
  const f = fixture(); const result = (await (await f.run()).json()).result;
  assert.equal(result.status, "ready"); assert.equal(result.kind, "correction"); assert.equal(result.source, "groq");
  assert.equal(f.generated().context, null);
});

test("a closing turn and an already-heard tip reach the provider as closed flags", async () => {
  const f = fixture();
  const body = { skill: "Receiving", angle: "Side", voice: false, reps: kneeReps(175),
    context: context({ turn: "closing", breakIndex: 2, corrected: ["PassKneeFlexion"] }) };
  const result = (await (await f.run(f.request(body), async input => {
    const summary = livePrompt(input);
    assert.equal(summary.turn, "closing"); assert.equal(summary.alreadyHeard, true); assert.equal(summary.breakIndex, 2);
    return { cueId: "PassKneeFlexion", text: "Next time, sit a little lower before you set your platform." };
  })).json()).result;
  assert.equal(result.source, "groq"); assert.equal(result.kind, "correction");
});

test("a storage failure degrades to the local tip and unknown failures are an outage, not a bad request", async () => {
  const f = fixture(); f.breakStorage();
  const response = await f.run();
  assert.equal(response.status, 200);
  const result = (await response.json()).result;
  assert.equal(result.source, "local"); assert.match(result.text, /extend your elbow/i);
  assert.equal(f.calls().text, 0);
  const g = fixture();
  const malformed = new Request("https://worker.example/coachLiveStage", { method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token()}` }, body: "{not json" });
  assert.equal((await g.run(malformed)).status, 400);
  const outage = await liveCoachingRequest(g.request(), g.env, {}, { now: () => now,
    fetchImpl: async () => { throw new TypeError("network down"); } });
  assert.equal(outage.status, 503);
  assert.equal((await outage.json()).error.status, "UNAVAILABLE");
});

test("a session's four text turns fit the spacing, and a shared-limit miss never spends the athlete's quota", async () => {
  const f = fixture();
  for (let turn = 0; turn < 4; turn++) {
    assert.equal((await (await f.run()).json()).result.source, "groq", `turn ${turn}`);
    f.advance(8_000);
  }
  assert.equal(f.calls().text, 4);
  const g = fixture();
  const day = new Date(now).toISOString().slice(0, 10);
  g.sqlite.prepare("INSERT INTO live_coaching_usage VALUES ('organization','text',?,500,?)").run(day, now - 60_000);
  assert.equal((await (await g.run()).json()).result.source, "local");
  assert.equal(g.sqlite.prepare("SELECT count(*) n FROM live_coaching_usage WHERE kind='text' AND actor!='organization'").get().n, 0);
});

test("the app and the worker choose the same focus from the shared parity fixture", () => {
  const fixtureFile = JSON.parse(readFileSync(new URL("./fixtures/live-coaching-parity.json", import.meta.url), "utf8"));
  assert.ok(fixtureFile.cases.length >= 6);
  for (const item of fixtureFile.cases) {
    const cue = liveEvidence({ skill: item.skill, angle: item.angle, voice: false, reps: item.reps });
    assert.deepEqual(cue ? { cueId: cue.id, position: cue.position } : null, item.expect, item.name);
  }
});
