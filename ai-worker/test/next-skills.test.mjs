import test from "node:test";
import assert from "node:assert/strict";
import { SKILLS, localNextSkills, nextSkillsData, nextSkillsPrompt, rankSkills, strength, validateNextSkills } from "../src/next-skills.mjs";
import { generateSetup, localSetup, setupData, trainingSetupRequest } from "../src/training-setup.mjs";

const row = (skill, edit = {}) => ({ skill, measuredScore: null, scoredSessions: 0, yes: 4, sometimes: 0, notYet: 0, notTried: 0, ...edit });
const data = (edits = {}) => ({ version: 1, mode: "next_skills", evidence: SKILLS.map(skill => row(skill, edits[skill])) });
const weakSetting = () => data({ Setting: { yes: 0, notYet: 4 }, Blocking: { yes: 2, sometimes: 2 } });

test("only five bounded rows of evidence are accepted", () => {
  assert.deepEqual(setupData({ data: weakSetting() }), weakSetting());
  for (const bad of [{ ...data(), name: "Ana" }, { ...data(), version: 2 }, { ...data(), evidence: data().evidence.slice(1) },
    data({ Setting: { skill: "Serving" } }), data({ Setting: { yes: 5 } }), data({ Setting: { measuredScore: 101, scoredSessions: 1 } }),
    data({ Setting: { measuredScore: 50 } }), data({ Setting: { position: "Libero" } }), data({ Setting: { yes: 1.5 } }),
    { ...data(), evidence: SKILLS.map(skill => row(skill, { yes: 0, notTried: 4 })) }])
    assert.throws(() => setupData({ data: bad }));
  assert.equal(nextSkillsData(null), null);
});

test("strength is measured first, then answers, and never counts what was not tried", () => {
  assert.equal(strength(row("Serving", { yes: 2, notTried: 2 })), 100);
  assert.equal(strength(row("Serving", { yes: 1, notTried: 3 })), null);
  assert.equal(strength(row("Serving", { yes: 0, notYet: 4, measuredScore: 90, scoredSessions: 2 })), 90);
  assert.equal(strength(row("Serving", { yes: 0, sometimes: 4 })), 50);
  assert.deepEqual(rankSkills(weakSetting().evidence).slice(0, 2).map(r => r.skill), ["Setting", "Blocking"]);
  assert.deepEqual(localNextSkills(weakSetting()).skills.map(s => s.skill), ["Setting", "Blocking"]);
  assert.deepEqual(localSetup(weakSetting()), localNextSkills(weakSetting()));
});

test("the AI may order and explain but not skip the lowest skill, invent numbers or label the player", () => {
  const good = { skills: [{ skill: "Blocking", reason: "This builds on your answers." }, { skill: "Setting", reason: "Your answers were lowest here." }] };
  assert.deepEqual(validateNextSkills(good, weakSetting()), good);
  const only = reason => ({ skills: [{ skill: "Setting", reason }] });
  for (const bad of [{ skills: [] }, { skills: [{ skill: "Serving", reason: "Try this." }] }, { skills: [...good.skills, { skill: "Serving", reason: "x" }] },
    { skills: [good.skills[1], good.skills[1]] }, { ...good, score: 9 }, only(""), only("You scored 40."), only("You are a beginner."), only("You are weak here."),
    only("I watched you play."), only("Good for your height."), only("x".repeat(161)), only("See https://x.y")])
    assert.throws(() => validateNextSkills(bad, weakSetting()), JSON.stringify(bad));
  const tied = data({ Setting: { yes: 0, notYet: 4 }, Serving: { yes: 0, notYet: 4 } });
  assert.ok(validateNextSkills({ skills: [{ skill: "Serving", reason: "Your answers were lowest here." }] }, tied));
});

test("the provider sees evidence only, with strict bounded output", async () => {
  const output = { skills: [{ skill: "Setting", reason: "Your answers were lowest here." }] };
  const result = await generateSetup({ apiKey: "private-secret", data: weakSetting(), fetchImpl: async (url, options) => {
    const body = JSON.parse(options.body);
    assert.equal(url, "https://api.groq.com/openai/v1/chat/completions");
    assert.equal(body.response_format.json_schema.strict, true);
    assert.deepEqual(Object.keys(JSON.parse(body.messages[1].content)), ["evidence"]);
    assert.ok(!options.body.includes("private-secret"));
    return Response.json({ choices: [{ message: { content: JSON.stringify(output) } }] });
  } });
  assert.deepEqual(result, output);
  assert.match(nextSkillsPrompt(weakSetting()).messages[0].content, /never assume a position, age, gender/);
});

test("without the AI the route still answers, marked as the app's own ordering", async () => {
  const encode = value => Buffer.from(JSON.stringify(value)).toString("base64url");
  const now = Date.UTC(2026, 10, 25);
  const token = `${encode({ alg: "RS256", kid: "t" })}.${encode({ sub: "u", aud: "volleyball-lab", iss: "https://securetoken.google.com/volleyball-lab", exp: now / 1000 + 3600, iat: now / 1000 - 10 })}.sig`;
  const response = await trainingSetupRequest(new Request("https://w.example/personalizeTrainingSetup", { method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` }, body: JSON.stringify({ data: weakSetting() }) }),
    {}, {}, { now: () => now, fetchImpl: async () => Response.json({ fields: {} }) });
  const { result } = await response.json();
  assert.equal(result.status, "next_skills"); assert.equal(result.source, "local");
  assert.deepEqual(result.skills.map(s => s.skill), ["Setting", "Blocking"]);
});
