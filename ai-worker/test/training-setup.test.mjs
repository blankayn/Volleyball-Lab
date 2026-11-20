import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { createWorker } from "../src/worker.mjs";
import { SETUP_QUESTIONS, setupData, validateSetupOutput, localSetup, generateSetup, trainingSetupRequest } from "../src/training-setup.mjs";

const startingTime = Date.UTC(2026, 10, 14);
const answers = () => ({ goalSkill: "Serving", experience: "Developing", note: "Madalas lumalabas ang serve ko.",
  followUpQuestionId: null, followUpOptionId: null, weeklySessionGoal: null });
const next = () => ({ version: 1, mode: "next_card", answers: answers() });
const summary = () => ({ version: 1, mode: "summary", answers: { ...answers(), followUpQuestionId: "serving_focus", followUpOptionId: "accuracy", weeklySessionGoal: 3 } });
const questionOutput = () => ({ focusSkill: "Serving", questionId: "serving_focus", acknowledgment: "Let's find what makes serving feel inconsistent for you." });
const summaryOutput = () => ({ focusSkill: "Serving", summary: "Build a serving routine that fits the practice time you chose.", reason: "You want to keep more serves in play." });
const token = (uid = "athlete-identity") => {
  const encode = value => Buffer.from(JSON.stringify(value)).toString("base64url");
  return `${encode({ alg: "RS256", kid: "test" })}.${encode({ sub: uid, aud: "volleyball-lab", iss: "https://securetoken.google.com/volleyball-lab", exp: startingTime / 1000 + 172800, iat: startingTime / 1000 - 10 })}.test-signature`;
};
function fixture() {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec(readFileSync(new URL("../migrations/0002_live_coaching.sql", import.meta.url), "utf8"));
  const db = { prepare(sql) { let args; return { bind(...value) { args = value; return this; }, async run() { return { meta: { changes: Number(sqlite.prepare(sql).run(...args).changes) } }; } }; } };
  const env = { AI_DB: db, GROQ_API_KEY: "private-server-key" };
  let currentTime = startingTime; let allowed = true; let generationCalls = 0; let authCalls = 0; let providerResponse = null;
  const fetchImpl = async (url, options) => {
    if (new URL(url).hostname === "firestore.googleapis.com") {
      authCalls++;
      assert.equal(new URL(url).pathname.endsWith("/users/athlete-identity"), true);
      assert.equal(options.headers.Authorization, `Bearer ${token()}`);
      return allowed ? Response.json({ fields: {} }) : Response.json({ error: { status: "PERMISSION_DENIED" } }, { status: 403 });
    }
    assert.equal(url, "https://api.groq.com/openai/v1/chat/completions");
    generationCalls++;
    return providerResponse ?? Response.json({ choices: [{ message: { content: JSON.stringify(questionOutput()) } }] });
  };
  const generate = async ({ data }) => { generationCalls++; return data.mode === "next_card" ? questionOutput() : summaryOutput(); };
  const request = (data = next(), bearer = token(), edit = {}) => new Request("https://worker.example/personalizeTrainingSetup", {
    method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${bearer}` }, body: JSON.stringify({ data }), ...edit,
  });
  const run = (req = request(), generator = generate) => trainingSetupRequest(req, env, {}, { fetchImpl, now: () => currentTime, generate: generator });
  return { sqlite, env, request, run, fetchImpl, now: () => currentTime,
    deny: () => { allowed = false; }, advance: ms => { currentTime += ms; },
    calls: () => ({ generation: generationCalls, auth: authCalls }), response: value => { providerResponse = value; } };
}

test("the card contract accepts bounded Taglish notes, known preferences, and one follow-up", () => {
  assert.deepEqual(setupData({ data: next() }), next());
  assert.deepEqual(setupData({ data: summary() }), summary());
  assert.equal(Object.keys(SETUP_QUESTIONS).length, 11);
  for (const id of Object.keys(SETUP_QUESTIONS).filter(id => id !== "general_focus")) {
    assert.equal(SETUP_QUESTIONS[id].options.length, 4);
  }
});

test("unknown fields, scores, arbitrary question IDs, impossible schedules, and invalid answers fail closed", () => {
  const invalidAnswers = [
    { note: "x".repeat(161) }, { note: 7 }, { goalSkill: "Football" }, { experience: "Expert" },
    { followUpQuestionId: "serving_focus" }, { weeklySessionGoal: 3 }, { scores: [99] },
  ];
  for (const edit of invalidAnswers) assert.throws(() => setupData({ data: { ...next(), answers: { ...answers(), ...edit } } }));
  for (const edit of [{ followUpQuestionId: "arbitrary" }, { followUpOptionId: "hurt" }, { weeklySessionGoal: 0 },
    { weeklySessionGoal: 15 }, { weeklySessionGoal: 1.5 }, { followUpQuestionId: "blocking_focus", followUpOptionId: "timing" }]) {
    assert.throws(() => setupData({ data: { ...summary(), answers: { ...summary().answers, ...edit } } }));
  }
  assert.throws(() => setupData({ data: { ...next(), uid: "other" } }));
  assert.throws(() => setupData({ data: { ...next(), version: 2 } }));
  assert.throws(() => setupData({ data: next(), unknown: true }));
});

test("NotSure uses the focus answer or AI-selected skill question without inventing a rating", () => {
  const generic = { ...summary(), answers: { ...summary().answers, goalSkill: "NotSure", followUpQuestionId: "general_focus", followUpOptionId: "Receiving" } };
  assert.equal(localSetup(setupData({ data: generic })).focusSkill, "Receiving");
  assert.deepEqual(validateSetupOutput({ focusSkill: "Receiving", summary: "Start with receiving practice.", reason: "Receiving is the focus you selected." }, generic).focusSkill, "Receiving");
  const selected = { ...generic, answers: { ...generic.answers, followUpQuestionId: "spiking_focus", followUpOptionId: "timing" } };
  assert.equal(localSetup(selected).focusSkill, "Spiking");
  const unspecified = { ...generic, answers: { ...generic.answers, followUpOptionId: "not_sure" } };
  assert.equal(localSetup(unspecified).focusSkill, "Serving");
  assert.ok(localSetup(unspecified).reason.includes("suggested"));
});

test("AI cannot replace an explicit goal, invent question schemas, scores, or measured ability", () => {
  assert.deepEqual(validateSetupOutput(questionOutput(), next()), questionOutput());
  for (const edit of [{ focusSkill: "Blocking", questionId: "blocking_focus" }, { questionId: "general_focus" },
    { options: ["custom"] }, { acknowledgment: "Your score is excellent." }, { acknowledgment: "You are advanced." },
    { acknowledgment: "I watched your video." }, { acknowledgment: "Practice 100 times." }, { acknowledgment: "x".repeat(161) }]) {
    assert.throws(() => validateSetupOutput({ ...questionOutput(), ...edit }, next()));
  }
  assert.throws(() => validateSetupOutput({ ...summaryOutput(), focusSkill: "Setting" }, summary()));
});

test("Groq sees only setup preferences, understands supported languages, and uses strict bounded output", async () => {
  let called = false;
  const output = await generateSetup({ apiKey: "private-secret", data: next(), fetchImpl: async (url, options) => {
    called = true;
    assert.equal(url, "https://api.groq.com/openai/v1/chat/completions");
    assert.equal(options.redirect, "manual");
    const body = JSON.parse(options.body);
    assert.equal(body.response_format.json_schema.strict, true);
    assert.deepEqual(body.response_format.json_schema.schema.properties.questionId.enum, ["serving_focus", "serving_consistency"]);
    assert.match(body.messages[0].content, /English, Filipino and Taglish/);
    assert.match(body.messages[0].content, /untrusted self-report/);
    assert.ok(body.messages[1].content.includes(next().answers.note));
    for (const secret of ["athlete-identity", "private-secret", "sessionId", "recording", "displayName", "email"]) assert.ok(!body.messages[1].content.includes(secret));
    return Response.json({ choices: [{ message: { content: JSON.stringify(questionOutput()) } }] });
  } });
  assert.equal(called, true); assert.deepEqual(output, questionOutput());
});

test("a stated issue can select a targeted same-skill card while preserving the bounded answer contract", async () => {
  const data = { ...next(), answers: { ...answers(), note: "Hindi consistent ang toss ko kahit matagal na akong naglalaro.", experience: "Experienced" } };
  const output = await generateSetup({ apiKey: "private-secret", data, fetchImpl: async (_url, options) => {
    const body = JSON.parse(options.body);
    const context = JSON.parse(body.messages[1].content);
    assert.equal(context.preferences.note, data.answers.note);
    assert.equal(context.preferences.experience, "Experienced");
    assert.deepEqual(context.allowedQuestions, ["serving_focus", "serving_consistency"]);
    assert.match(context.questionPurposes.serving_consistency, /inconsistent toss/);
    assert.match(body.messages[0].content, /using the note and stated experience/);
    return Response.json({ choices: [{ message: { content: JSON.stringify({ ...questionOutput(), questionId: "serving_consistency" }) } }] });
  } });
  assert.equal(output.questionId, "serving_consistency");
  assert.deepEqual(SETUP_QUESTIONS.serving_consistency.options, SETUP_QUESTIONS.serving_focus.options);
  const completed = { ...summary(), answers: { ...summary().answers, followUpQuestionId: output.questionId, followUpOptionId: "toss" } };
  assert.equal(setupData({ data: completed }).answers.followUpQuestionId, "serving_consistency");
  assert.equal(localSetup(completed).focusSkill, "Serving");
  assert.equal(localSetup(data).questionId, "serving_focus");
  assert.throws(() => validateSetupOutput({ ...output, focusSkill: "Blocking", questionId: "blocking_timing" }, data));
});

test("all targeted variants retain their skill's options and cannot cross the explicit goal boundary", () => {
  for (const [variant, base] of [
    ["serving_consistency", "serving_focus"], ["receiving_control", "receiving_focus"],
    ["setting_control", "setting_focus"], ["spiking_timing", "spiking_focus"], ["blocking_timing", "blocking_focus"],
  ]) {
    const skill = SETUP_QUESTIONS[base].skill;
    const data = { ...next(), answers: { ...answers(), goalSkill: skill } };
    assert.deepEqual(SETUP_QUESTIONS[variant], SETUP_QUESTIONS[base]);
    assert.equal(validateSetupOutput({ ...questionOutput(), focusSkill: skill, questionId: variant }, data).questionId, variant);
    assert.equal(localSetup(data).questionId, base);
    const foreignSkill = skill === "Serving" ? "Blocking" : "Serving";
    assert.throws(() => validateSetupOutput({ ...questionOutput(), focusSkill: foreignSkill, questionId: variant }, data));
  }
});

test("actual Firebase authorization precedes quotas/inference and is rechecked before responding", async () => {
  const f = fixture(); f.deny();
  assert.equal((await f.run()).status, 403);
  assert.equal(f.calls().generation, 0);
  assert.equal(f.sqlite.prepare("SELECT count(*) n FROM live_coaching_usage").get().n, 0);
  assert.equal((await f.run(f.request(next(), "forged"))).status, 401);
  const success = fixture();
  assert.equal((await success.run()).status, 200);
  assert.deepEqual(success.calls(), { generation: 1, auth: 2 });
});

test("revocation while generating blocks the answer instead of returning generated personal guidance", async () => {
  const f = fixture();
  const response = await f.run(f.request(), async () => { f.deny(); return questionOutput(); });
  assert.equal(response.status, 403);
  assert.ok(!(await response.text()).includes(questionOutput().acknowledgment));
});

test("provider errors, malformed output, and missing configuration return a disclosed local setup", async () => {
  for (const generator of [async () => { throw Error("provider private details"); }, async () => ({ ...questionOutput(), score: 90 }),
    async () => ({ ...questionOutput(), questionId: "arbitrary" })]) {
    const f = fixture(); const result = await (await f.run(f.request(), generator)).json();
    assert.equal(result.result.source, "local"); assert.equal(result.result.status, "question");
    assert.equal(result.result.questionId, "serving_focus");
    assert.ok(!JSON.stringify(result).includes("provider private details"));
  }
  const f = fixture(); delete f.env.GROQ_API_KEY;
  const result = await (await f.run()).json();
  assert.equal(result.result.source, "local"); assert.equal(f.calls().generation, 0);
  assert.equal(f.sqlite.prepare("SELECT count(*) n FROM live_coaching_usage").get().n, 0);
});

test("atomic quota limits both modes to ten caller attempts daily and keeps only hashed usage", async () => {
  const f = fixture();
  for (let index = 0; index < 12; index++) {
    const result = await (await f.run(f.request(index % 2 ? summary() : next()))).json();
    assert.equal(result.result.source, index < 10 ? "groq" : "local");
    f.advance(1_001);
  }
  assert.equal(f.calls().generation, 10);
  const rows = f.sqlite.prepare("SELECT * FROM live_coaching_usage").all();
  assert.equal(rows.length, 2);
  assert.ok(rows.some(row => /^[a-f0-9]{64}$/.test(row.actor)));
  const serialized = JSON.stringify(rows);
  for (const privateValue of ["athlete-identity", answers().note, questionOutput().acknowledgment, "private-server-key"]) assert.ok(!serialized.includes(privateValue));
  f.advance(24 * 60 * 60 * 1_000);
  assert.equal((await (await f.run()).json()).result.source, "groq");
});

test("caller cooldown and the organization budget avoid extra provider calls without blocking setup", async () => {
  const f = fixture();
  assert.equal((await (await f.run()).json()).result.source, "groq");
  assert.equal((await (await f.run()).json()).result.source, "local");
  const organization = fixture();
  organization.sqlite.prepare("INSERT INTO live_coaching_usage VALUES ('organization','onboarding_text',?,200,?)")
    .run(new Date(startingTime).toISOString().slice(0, 10), startingTime - 60_000);
  assert.equal((await (await organization.run()).json()).result.source, "local");
  assert.equal(organization.calls().generation, 0);
});

test("bad protocol, large streamed requests, and unsupported methods never call inference", async () => {
  const f = fixture();
  assert.equal((await f.run(f.request({ ...next(), answers: { ...answers(), score: 99 } }))).status, 400);
  assert.equal((await f.run(f.request(next(), token(), { body: "x".repeat(8_193) }))).status, 400);
  assert.equal((await f.run(new Request("https://worker.example/personalizeTrainingSetup", { method: "GET" }))).status, 405);
  assert.equal(f.calls().generation, 0);
});

test("Worker routes the new callable endpoint with normal origin protection and no caching", async () => {
  const f = fixture();
  const worker = createWorker({ fetchImpl: f.fetchImpl, now: f.now });
  const response = await worker.fetch(f.request(), f.env);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("Cache-Control"), "no-store");
  assert.equal((await response.json()).result.source, "groq");
  const denied = f.request(next(), token(), { headers: { "Content-Type": "application/json", Authorization: `Bearer ${token()}`, Origin: "https://unapproved.example" } });
  assert.equal((await worker.fetch(denied, f.env)).status, 403);
});
