/* Which skills a player could work on next. The AI gets five rows of counts and
   scores, may order and explain at most two of them, and may not leave out the one
   with the lowest evidence. The same rules are checked again in the Android app. */

export const SKILLS = ["Serving", "Receiving", "Setting", "Spiking", "Blocking"];
const MAX = 2;
const MIN_ANSWERED = 2;
const STATEMENTS = 4;
const FIELDS = ["measuredScore", "notTried", "notYet", "scoredSessions", "skill", "sometimes", "yes"];
const same = (value, keys) => value && typeof value === "object" && !Array.isArray(value)
  && Object.keys(value).sort().join() === [...keys].sort().join();
const whole = (value, low, high) => Number.isInteger(value) && value >= low && value <= high;

/** Returns the evidence, or null when it is not exactly the agreed shape. */
export function nextSkillsData(data) {
  if (!same(data, ["version", "mode", "evidence"]) || data.version !== 1 || data.mode !== "next_skills") return null;
  const { evidence } = data;
  if (!Array.isArray(evidence) || evidence.length !== SKILLS.length) return null;
  for (const row of evidence) {
    if (!same(row, FIELDS) || !SKILLS.includes(row.skill)
        || !(row.measuredScore === null || whole(row.measuredScore, 0, 100)) || !whole(row.scoredSessions, 0, 999)
        || (row.measuredScore !== null) !== (row.scoredSessions > 0)
        || ![row.yes, row.sometimes, row.notYet, row.notTried].every(count => whole(count, 0, STATEMENTS))
        || row.yes + row.sometimes + row.notYet + row.notTried > STATEMENTS) return null;
  }
  if (new Set(evidence.map(row => row.skill)).size !== SKILLS.length) return null;
  return evidence.some(row => strength(row) !== null) ? { version: 1, mode: "next_skills", evidence } : null;
}

/** Measured sessions, or else the share of answers; null when neither says enough. */
export function strength(row) {
  if (row.measuredScore !== null) return row.measuredScore;
  const answered = row.yes + row.sometimes + row.notYet;
  return answered < MIN_ANSWERED ? null : Math.round((row.yes + row.sometimes / 2) / answered * 100);
}

export function rankSkills(evidence) {
  return evidence.filter(row => strength(row) !== null)
    .sort((a, b) => strength(a) - strength(b) || a.scoredSessions - b.scoredSessions);
}

function reason(value) {
  if (typeof value !== "string") throw Error("invalid_reason");
  const text = value.trim().replace(/\s+/g, " ");
  if (!text || text.length > 160 || /\p{N}|[\[\]<>]|https?:|@/u.test(text)
      || /\b(?:diagnos\w*|injur\w*|medical|pain|talent\w*|gender|male|female|height|weight|age)\b|\b(?:I|we) (?:saw|watched)\b|\byou(?:'re| are) (?:a |an )?(?:weak|bad|good|poor|beginner|intermediate|advanced|unskilled)\b/i.test(text)) throw Error("ungrounded_reason");
  return text;
}

export function validateNextSkills(raw, data) {
  const value = typeof raw === "string" ? JSON.parse(raw) : raw;
  if (!same(value, ["skills"]) || !Array.isArray(value.skills) || value.skills.length < 1 || value.skills.length > MAX) throw Error("invalid_next_skills");
  const skills = value.skills.map(item => {
    if (!same(item, ["skill", "reason"]) || !SKILLS.includes(item.skill)) throw Error("invalid_next_skill");
    return { skill: item.skill, reason: reason(item.reason) };
  });
  if (new Set(skills.map(item => item.skill)).size !== skills.length) throw Error("repeated_skill");
  // A tie for lowest may go to any of the tied skills.
  const lowest = strength(rankSkills(data.evidence)[0]);
  if (!skills.some(item => strength(data.evidence.find(row => row.skill === item.skill)) === lowest)) throw Error("lowest_skill_left_out");
  return { skills };
}

export function localNextSkills(data) {
  return { skills: rankSkills(data.evidence).slice(0, MAX).map(row => ({ skill: row.skill,
    reason: row.measuredScore !== null ? "Your recorded sessions scored lowest here." : "Your answers were lowest here." })) };
}

export function nextSkillsPrompt(data) {
  const item = { type: "object", additionalProperties: false, required: ["skill", "reason"],
    properties: { skill: { type: "string", enum: SKILLS }, reason: { type: "string" } } };
  return {
    schema: { type: "object", additionalProperties: false, required: ["skills"], properties: { skills: { type: "array", items: item } } },
    messages: [{ role: "system", content: "You help a volleyball player choose which of five skills to work on next. Use only the supplied evidence. measuredScore is a camera form score from recorded sessions and outranks answers; yes, sometimes, notYet and notTried count the player's own answers. Choose one or two different skills. Always include a skill with the lowest strength. You may add a second that follows well from it, for example receiving before setting. Treat every skill as equally important and never assume a position, age, gender, level or talent. Give each a reason of at most one hundred and sixty characters in plain, encouraging English that refers to their answers or recorded sessions. Do not use digits, levels, grades, links, medical claims, or claims of watching them play. Return only the required JSON." },
      { role: "user", content: JSON.stringify({ evidence: data.evidence.map(row => ({ ...row, strength: strength(row) })) }) }],
  };
}
