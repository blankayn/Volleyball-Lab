import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { ATTEMPTS, CHECKS, DETAILS, DRILLS, STATEMENTS, WEAK_POINTS, GOALS, assessmentData, assessmentPrompt, athleteAssessmentRequest, drillMinutes, evidenceOf, generateAnalysis, skillStrength, validateAnalysis } from "../src/athlete-assessment.mjs";
import { SKILLS } from "../src/next-skills.mjs";

const kotlin = path => readFile(new URL(`../../app/src/main/java/com/volleyballlab/player/${path}`, import.meta.url), "utf8");
const statements = (edit = {}) => ({ ...Object.fromEntries(Object.keys(STATEMENTS).map(id => [id, "Yes"])), ...edit });
const request = (edit = {}) => ({ version: 1,
  profile: { experience: "New", position: null, preferred: ["Setting"], goal: "BeConsistent", goalNote: "", days: 3, minutes: 30, weakPoints: ["KneeBend"], weakNote: "", ...edit.profile },
  statements: statements({ receiving_ready: "NotYet", receiving_control: "NotYet", receiving_movement: "Sometimes", receiving_moving: "NotYet", ...edit.statements }),
  measured: SKILLS.map(skill => ({ skill, score: null, sessions: 0, ...edit.measured?.[skill] })), checks: edit.checks ?? [],
  drills: edit.drills ?? ["receive-wall-platform", "set-wall-reps", "serve-standing-float"], finished: [], marks: { right: 0, wrong: 0 }, previousFocus: [] });
const analysis = (edit = {}) => ({ summary: "Your answers show a steady serve, and receiving has the most room to grow.",
  strengths: [{ skill: "Serving", text: "You start from a balanced stance.", evidence: ["say:serving_ready"] }],
  weaknesses: [{ skill: "Receiving", text: "Getting low before the ball comes is still new.", evidence: ["say:receiving_ready", "weak:KneeBend"] }],
  improvements: [{ skill: "Receiving", text: "Keep your arms together more often.", evidence: ["say:receiving_movement"] }],
  focus: ["Receiving", "Setting"],
  practices: [{ title: "Low and ready", drills: [{ drillId: "receive-wall-platform", sets: 2, reps: 15, why: "It builds the low start you said is hard." }] }],
  weeklyGoals: [{ skill: "Receiving", text: "Bend your knees before every pass." }], ...edit });

test("statements, drills, checks and choices match the app", async () => {
  const check = await kotlin("domain/model/SkillCheck.kt");
  for (const { text } of Object.values(STATEMENTS)) assert.ok(check.includes(`"${text}"`), text);
  assert.equal(Object.keys(STATEMENTS).length, 20);
  for (const [id, details] of Object.entries(DETAILS))
    assert.ok(check.includes(`Card("`) && check.includes(`"${STATEMENTS[id].text}", ${Object.values(details).map(text => `"${text}"`).join(", ")})`), id);
  assert.deepEqual(Object.keys(DETAILS), Object.keys(STATEMENTS));
  const drills = await kotlin("data/skill/StaticDrillRepository.kt");
  for (const d of Object.values(DRILLS)) {
    const at = drills.indexOf(`id = "${d.id}"`);
    const block = drills.slice(at, drills.indexOf("level =", at) + 40);
    assert.ok(at > 0 && block.includes(`Skill.${d.skill}`) && block.includes(`"${d.name}"`) && block.includes(`targetReps = ${d.reps}`)
      && block.includes(`estimatedMinutes = ${d.minutes}`) && block.includes(`DrillLevel.${d.level}`), d.id);
  }
  const cues = (await kotlin("domain/model/FormCue.kt")).split("enum class CueId {")[1].split("\n}")[0];
  for (const id of Object.keys(CHECKS)) assert.match(cues, new RegExp(`\\b${id},`));
  const model = await kotlin("domain/model/AthleteAssessment.kt");
  for (const id of [...Object.keys(WEAK_POINTS), ...Object.keys(GOALS)]) assert.match(model, new RegExp(`\\b${id}\\(`));
});

test("only the agreed shape is accepted", () => {
  assert.ok(assessmentData({ data: request() }));
  for (const bad of [{ name: "Ana" }, { version: 2 }, { profile: {} }, { drills: [] }, { drills: ["made-up"] }, { statements: {} },
    { measured: request().measured.slice(1) }, { checks: [{ cueId: "SpikeJumpHeight", trend: "Solid" }] }, { marks: { right: -1, wrong: 0 } }, { previousFocus: ["Libero"] }])
    assert.equal(assessmentData({ data: { ...request(), ...bad } }), null, JSON.stringify(bad));
  for (const bad of [{ experience: "Pro" }, { position: "Coach" }, { days: 8 }, { minutes: 20 }, { goalNote: "x".repeat(161) }, { weakPoints: ["KneeBend", "KneeBend"] }])
    assert.equal(assessmentData({ data: request({ profile: bad }) }), null, JSON.stringify(bad));
  assert.equal(assessmentData({ data: request({ measured: { Serving: { score: 50 } } }) }), null, "a score needs a session");
});

test("strength is measured first, then answers, and untried statements say nothing", () => {
  assert.equal(skillStrength(request(), "Receiving"), 13);
  assert.equal(skillStrength(request({ measured: { Receiving: { score: 88, sessions: 2 } } }), "Receiving"), 88);
  const untried = request({ statements: { blocking_ready: "NotTried", blocking_movement: "NotTried", blocking_control: "NotTried" } });
  assert.equal(skillStrength(untried, "Blocking"), null);
  assert.equal(evidenceOf(untried)["say:blocking_ready"].lean, 0);
  const item = { skill: "Blocking", text: "Try blocking for the first time.", evidence: ["say:blocking_ready"] };
  assert.equal(validateAnalysis(analysis({ improvements: [item] }), untried).improvements.length, 1, "something new to try");
  assert.equal(validateAnalysis(analysis({ weaknesses: [item] }), untried).weaknesses.length, 0, "not trying is not a weakness");
});

test("a point without supporting evidence is dropped, so performance cannot be invented", () => {
  const data = request();
  const keep = validateAnalysis(analysis(), data);
  assert.equal(keep.strengths.length, 1);
  const strength = edit => validateAnalysis(analysis({ strengths: [{ skill: "Serving", text: "You swing high.", evidence: ["say:serving_movement"], ...edit }] }), data).strengths.length;
  assert.equal(strength({}), 1);
  assert.equal(strength({ evidence: [] }), 0);
  assert.equal(strength({ evidence: ["score:Serving"] }), 0, "no camera score exists");
  assert.equal(strength({ evidence: ["say:receiving_ready"] }), 0, "another skill's answer");
  assert.equal(strength({ skill: "Receiving", evidence: ["say:receiving_ready"] }), 0, "the answer says the opposite");
  assert.equal(strength({ evidence: ["weak:KneeBend"] }), 0, "a general weak point alone");
  const weakness = validateAnalysis(analysis({ weaknesses: [{ skill: "Serving", text: "Serving is hard for you.", evidence: ["say:serving_ready"] }] }), data);
  assert.equal(weakness.weaknesses.length, 0);
  const measured = request({ measured: { Serving: { score: 45, sessions: 3 } }, checks: [{ cueId: "PassKneeFlexion", trend: "Slipping" }] });
  assert.equal(validateAnalysis(analysis({ weaknesses: [{ skill: "Receiving", text: "Your knee bend has slipped.", evidence: ["check:PassKneeFlexion"] }] }), measured).weaknesses.length, 1);
  assert.throws(() => validateAnalysis(analysis({ strengths: [], weaknesses: [], improvements: [] }), data), /nothing_supported/);
});

test("numbers, labels, medical words and claims of watching are refused", () => {
  for (const text of ["You scored 88.", "Your elbow is ninety degrees.", "You are a beginner.", "I watched your serve.", "Rest your injured knee.", "Good for your height.", "See https://x.y", "<b>hi</b>"]) {
    assert.throws(() => validateAnalysis(analysis({ summary: text }), request()), undefined, text);
    assert.equal(validateAnalysis(analysis({ strengths: [{ skill: "Serving", text, evidence: ["say:serving_ready"] }] }), request()).strengths.length, 0, text);
  }
});

test("the plan uses only offered drills and fits the days and minutes given", () => {
  const drill = (drillId, sets, reps) => ({ drillId, sets, reps, why: "It fits your goal." });
  const plan = (practices, edit) => validateAnalysis(analysis({ practices }), request(edit)).practices;
  assert.equal(drillMinutes(DRILLS["receive-wall-platform"], 2, 15), 10);
  const fitted = plan([{ title: "Long", drills: [drill("receive-wall-platform", 6, 30), drill("set-wall-reps", 5, 20), drill("serve-jump-float", 1, 10)] }]);
  const total = fitted[0].drills.reduce((sum, d) => sum + drillMinutes(DRILLS[d.drillId], d.sets, d.reps), 0);
  assert.ok(total <= 30, `${total}`);
  assert.ok(!fitted[0].drills.some(d => d.drillId === "serve-jump-float"), "not offered");
  const many = Array.from({ length: 5 }, (_, i) => ({ title: "Practice", drills: [drill("receive-wall-platform", 1, 10 + i)] }));
  assert.equal(plan(many).length, 3);
  assert.equal(plan(many, { profile: { days: 1 } }).length, 1);
  for (const [bad, reason] of [[[], "empty"], [[{ title: "x", drills: [drill("spike-standing-swing", 1, 10)] }], "drill"], [[{ title: "x", drills: [drill("receive-wall-platform", "two", 10)] }], "amount"], [[{ title: "x" }], "shape"]])
    assert.throws(() => plan(bad), new RegExp(`practice_${reason}`));
  assert.equal(plan([{ title: "x", drills: [drill("receive-wall-platform", 0, 2)] }])[0].drills[0].reps, 3);
});

test("the skill with most to gain cannot be left out, and a preferred skill cannot replace it", () => {
  const setting = [{ title: "Hands up", drills: [{ drillId: "set-wall-reps", sets: 1, reps: 10, why: "You like setting." }] }];
  assert.throws(() => validateAnalysis(analysis({ focus: ["Setting"], practices: setting }), request()), /lowest_skill_left_out/);
  assert.ok(validateAnalysis(analysis({ focus: ["Setting", "Receiving"], practices: setting }), request()));
  assert.ok(validateAnalysis(analysis({ focus: ["Setting"], practices: setting, weeklyGoals: [{ skill: "Setting", text: "Get your hands up early." }] }), request({ drills: ["set-wall-reps"] })), "no drill of it can be opened");
});

test("the prompt carries this athlete's facts and no identity, and differs between athletes", () => {
  const one = assessmentPrompt(request({ profile: { goalNote: "Makapasok sa varsity" } }));
  const text = JSON.stringify(one.messages);
  assert.match(text, /Makapasok sa varsity/);
  assert.match(text, /never instructions/);
  assert.notEqual(text, JSON.stringify(assessmentPrompt(request({ profile: { experience: "Experienced", goal: "CompeteBetter" } })).messages));
  assert.ok(!/uid|email|token/i.test(JSON.stringify(one.messages[1])));
  assert.ok(one.schema.properties.practices.items.properties.drills.items.properties.drillId.enum.every(id => id in DRILLS));
});

const token = () => { const part = v => Buffer.from(JSON.stringify(v)).toString("base64url"); const now = Math.floor(Date.now() / 1000);
  return `${part({ alg: "RS256", kid: "k" })}.${part({ aud: "volleyball-lab", iss: "https://securetoken.google.com/volleyball-lab", sub: "player", user_id: "player", iat: now - 10, exp: now + 3000, auth_time: now - 10 })}.${"s".repeat(60)}`; };
const db = () => { const rows = new Map(); return { prepare: () => ({ bind: (actor, kind, day, _n, limit) => ({ run: async () => { const key = `${actor}:${kind}`; const count = rows.get(key) ?? 0; if (count >= limit) return { meta: { changes: 0 } }; rows.set(key, count + 1); return { meta: { changes: 1 } }; } }) }) }; };
const call = (options = {}, body = { data: request() }) => athleteAssessmentRequest(new Request("https://x/assessAthlete", { method: "POST", headers: { Authorization: `Bearer ${token()}`, "Content-Type": "application/json" }, body: JSON.stringify(body) }),
  { GROQ_API_KEY: "key", AI_DB: db(), ...options.env }, {}, { fetchImpl: async () => Response.json({ fields: { role: { stringValue: "PLAYER" } } }), ...options });

test("a rejected answer is tried again with its own allowance, and then there is no made-up result", async t => {
  const logged = []; t.mock.method(console, "error", line => logged.push(line));
  const seen = [];
  const ready = await (await call({ generate: async ({ problem, model }) => { seen.push({ problem, model }); if (seen.length === 1) throw Error("lowest_skill_left_out"); return { ok: true }; } })).json();
  assert.deepEqual(seen.map(s => s.problem), ["", "lowest skill left out"]);
  assert.notEqual(seen[0].model, seen[1].model);
  assert.equal(ready.result.status, "ready");
  const problems = [];
  const failed = await (await call({ generate: async ({ problem }) => { problems.push(problem); throw Error(problems.length === 1 ? "groq_429_rate_limit_exceeded" : "Player said: secret"); } })).json();
  assert.deepEqual(failed.result, { status: "unavailable", reason: "ai" });
  assert.deepEqual(problems, ["", "", ""], "a refusal by the provider is not the answer's fault");
  assert.deepEqual(logged, ["assessment attempt 1: lowest_skill_left_out", "assessment attempt 1: groq_429_rate_limit_exceeded", "assessment attempt 2: unexpected", "assessment attempt 3: unexpected"]);
  let clock = Date.now(), tries = 0;
  await call({ now: () => clock, generate: async () => { tries++; clock += 19_000; throw Error("timeout"); } });
  assert.equal(tries, 1, "no new try once the phone is about to stop waiting");
  assert.deepEqual((await (await call({ env: { GROQ_API_KEY: "" } })).json()).result, { status: "unavailable", reason: "ai" });
  const limited = { env: { AI_DB: { prepare: () => ({ bind: () => ({ run: async () => ({ meta: { changes: 0 } }) }) }) } } };
  assert.deepEqual((await (await call(limited)).json()).result, { status: "unavailable", reason: "limit" });
  assert.equal((await call({}, { data: { ...request(), name: "Ana" } })).status, 400);
});

test("the provider is asked for strict JSON and its answer is checked", async () => {
  let sent;
  const result = await generateAnalysis({ apiKey: "key", data: request(), fetchImpl: async (_url, init) => { sent = JSON.parse(init.body);
    return Response.json({ choices: [{ message: { content: JSON.stringify(analysis()) } }] }); } });
  assert.equal(sent.response_format.json_schema.strict, true);
  assert.equal(result.practices[0].drills[0].drillId, "receive-wall-platform");
});

test("Groq's reason for refusing is kept as a code only", async () => {
  const ask = response => generateAnalysis({ apiKey: "key", data: request(), fetchImpl: async () => response });
  await assert.rejects(ask(Response.json({ error: { code: "rate_limit_exceeded", message: "secret" } }, { status: 429 })), /^Error: groq_429_rate_limit_exceeded$/);
  await assert.rejects(ask(new Response("<html>", { status: 502 })), /^Error: groq_502$/);
  await assert.rejects(ask(Response.json({ choices: [{ message: { content: "{" } }] })), /^Error: invalid_json$/);
});

test("short evidence names are turned back into the real ones", async () => {
  const { names } = assessmentPrompt(request());
  const short = id => Object.keys(names).find(key => names[key] === id);
  const answer = analysis({ strengths: [{ skill: "Serving", text: "You start from a balanced stance.", evidence: [short("say:serving_ready")] }],
    weaknesses: [{ skill: "Receiving", text: "Getting low is new.", evidence: ["e999"] }] });
  for (const attempt of ATTEMPTS) {
    let sent;
    const result = await generateAnalysis({ apiKey: "key", data: request(), ...attempt, fetchImpl: async (_url, init) => { sent = JSON.parse(init.body);
      return Response.json({ choices: [{ message: { content: JSON.stringify(answer) } }] }); } });
    assert.deepEqual(result.strengths[0].evidence, ["say:serving_ready"]);
    assert.deepEqual(result.weaknesses, [], "an invented id is dropped");
    assert.equal(sent.model, attempt.model);
    assert.equal(sent.response_format.type, attempt.strict ? "json_schema" : "json_object");
  }
});

test("a request stays well inside Groq's eight thousand tokens a minute", () => {
  const largest = request({ profile: { goalNote: "x".repeat(160), weakNote: "y".repeat(160), weakPoints: Object.keys(WEAK_POINTS), preferred: SKILLS },
    drills: Object.keys(DRILLS), checks: Object.keys(CHECKS).map(cueId => ({ cueId, trend: "WorkOn" })), measured: Object.fromEntries(SKILLS.map(s => [s, { score: 50, sessions: 9 }])) });
  const size = data => { const { schema, messages } = assessmentPrompt(data); return JSON.stringify({ schema, messages }).length / 4 + 2_600; };
  assert.ok(size(largest) < 6_000, `${size(largest)}`);
  assert.ok(size(request()) < 5_000, `${size(request())}`);
});

test("one sentence that may not be shown does not throw away the rest", () => {
  const summary = text => validateAnalysis(analysis({ summary: text }), request()).summary;
  assert.equal(summary("You scored 88. Your serve is steady."), "Your serve is steady.");
  assert.equal(summary("With 3 days a week and 30 minutes each, start low."), "With three days a week and thirty minutes each, start low.", "their own answer, in words");
  assert.throws(() => summary("With 4 days a week, start low."), /text_number/, "not what they answered");
  assert.equal(summary("Shift your weight forward as you serve."), "Shift your weight forward as you serve.");
  assert.equal(summary(`Keep going. ${"a".repeat(420)}.`), "Keep going.");
  for (const [text, reason] of [["You are a beginner.", "label"], ["Rest your injured knee.", "word"], ["I watched you.", "claim"], ["<b>x</b>", "markup"], ["", "missing"]])
    assert.throws(() => summary(text), new RegExp(`text_${reason}`));
});

test("a numbered title or an unusable reason does not cost the athlete the practice", () => {
  const plan = (title, why, drillId = "receive-wall-platform") => validateAnalysis(analysis({ practices: [{ title, drills: [{ drillId, sets: 1, reps: 10, why }] }] }), request()).practices[0];
  assert.equal(plan("Day 1: Low and ready", "It fits.").title, "Low and ready");
  assert.equal(plan("Practice 2 – Passing", "It fits.").title, "Passing");
  assert.equal(plan("Day 1", "It fits.").title, "Receiving");
  assert.equal(plan("Low", "Do 15 reps.").drills[0].why, "Getting low before the ball comes is still new.", "what it already said about the skill");
  assert.throws(() => plan("Low", "Do 15 reps.", "set-wall-reps"), /practice_why/, "nothing was said about setting");
});

const only = (skill, answer = "NotYet") => Object.fromEntries(Object.keys(STATEMENTS).filter(id => STATEMENTS[id].skill === skill).map(id => [id, answer]));

test("only the chosen skills are asked about, each of them completely", () => {
  const ask = edit => assessmentData({ data: { ...request(), ...edit } });
  assert.ok(ask({ statements: only("Setting") }));
  assert.ok(ask({ statements: { ...only("Setting"), ...only("Blocking") } }));
  const { setting_ready: _, ...partial } = only("Setting");
  for (const bad of [{}, partial, [], null, { ...only("Setting"), made_up: "Yes" }]) assert.equal(ask({ statements: bad }), null);
  assert.ok(ask({ statements: only("Setting"), details: {} }));
  assert.ok(ask({ statements: only("Setting"), details: { setting_control: ["setting_control_1", "setting_control_3"] } }));
  for (const bad of [{ setting_control: [] }, { setting_control: ["receiving_control_1"] }, { receiving_ready: ["receiving_ready_1"] }, { setting_control: ["setting_control_1", "setting_control_1"] }, [], "x"])
    assert.equal(ask({ statements: only("Setting"), details: bad }), null, JSON.stringify(bad));
  assert.equal(ask({ statements: only("Setting", "Yes"), details: { setting_control: ["setting_control_1"] } }), null, "nothing happens in what is already there");
});

test("what the player says happens reaches the AI and needs no new evidence ids", () => {
  const data = { ...request({ drills: ["set-wall-reps"] }), statements: only("Setting"), details: { setting_control: ["setting_control_3"] } };
  assert.match(evidenceOf(data)["say:setting_control"].text, /not yet\. What happens: My sets drift\./);
  assert.match(JSON.stringify(assessmentPrompt(data).messages), /My sets drift/);
  assert.ok(Object.keys(evidenceOf(data)).filter(id => id.startsWith("say:")).every(id => id.startsWith("say:setting")));
});

test("the plan stays with the chosen skills, whatever the camera measured elsewhere", () => {
  const data = { ...request({ drills: ["set-wall-reps"], measured: { Blocking: { score: 20, sessions: 4 } } }), statements: only("Setting") };
  const setting = { summary: "Your answers are about setting.", strengths: [], improvements: [], weaknesses: [{ skill: "Setting", text: "Getting your hands up is new.", evidence: ["say:setting_ready"] }],
    focus: ["Blocking", "Setting"], practices: [{ title: "Hands up", drills: [{ drillId: "set-wall-reps", sets: 1, reps: 10, why: "You chose setting." }] }],
    weeklyGoals: [{ skill: "Blocking", text: "Reach earlier." }, { skill: "Setting", text: "Get your hands up early." }] };
  const result = validateAnalysis(setting, data);
  assert.deepEqual(result.focus, ["Setting"]);
  assert.deepEqual(result.weeklyGoals.map(g => g.skill), ["Setting"]);
  assert.throws(() => validateAnalysis({ ...setting, weaknesses: [{ skill: "Receiving", text: "Passing is new.", evidence: ["say:receiving_ready"] }] }, data), /nothing_supported/);
});
