import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { CHECKS, DRILLS, STATEMENTS, WEAK_POINTS, GOALS, assessmentData, assessmentPrompt, athleteAssessmentRequest, drillMinutes, evidenceOf, generateAnalysis, skillStrength, validateAnalysis } from "../src/athlete-assessment.mjs";
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
  for (const bad of [[], [{ title: "x", drills: [drill("spike-standing-swing", 1, 10)] }], [{ title: "x", drills: [drill("receive-wall-platform", 0, 10)] }]])
    assert.throws(() => plan(bad), /no_practice_left/);
});

test("the skill with most to gain cannot be left out, and a preferred skill cannot replace it", () => {
  const setting = [{ title: "Hands up", drills: [{ drillId: "set-wall-reps", sets: 1, reps: 10, why: "You like setting." }] }];
  assert.throws(() => validateAnalysis(analysis({ focus: ["Setting"], practices: setting }), request()), /lowest_skill_left_out/);
  assert.ok(validateAnalysis(analysis({ focus: ["Setting", "Receiving"], practices: setting }), request()));
  assert.ok(validateAnalysis(analysis({ focus: ["Setting"], practices: setting }), request({ drills: ["set-wall-reps"] })), "no drill of it can be opened");
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

test("a rejected answer is tried once more, and then there is no made-up result", async () => {
  const seen = [];
  const ready = await (await call({ generate: async ({ problem }) => { seen.push(problem); if (seen.length === 1) throw Error("lowest_skill_left_out"); return { ok: true }; } })).json();
  assert.deepEqual(seen, ["", "lowest skill left out"]);
  assert.equal(ready.result.status, "ready");
  const failed = await (await call({ generate: async () => { throw Error("boom"); } })).json();
  assert.deepEqual(failed.result, { status: "unavailable", reason: "ai" });
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
