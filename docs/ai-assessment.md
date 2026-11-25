# AI assessment and weekly plan

Built 25 November 2026. Code: `ai-worker/src/athlete-assessment.mjs`,
`domain/model/AthleteAssessment.kt`, `data/assessment/AthletePlanner.kt`.

## Flow

1. The athlete answers: experience, position, the skills they want to work on (at least one), goal,
   days and minutes. Then one card for each characteristic of **each chosen skill only**, four per skill.
   - What follows depends on the answer. "Sometimes" or "Not yet" asks what usually happens, with three
     details of that characteristic; "I haven't tried it" offers to leave the rest of the skill out.
   - At the end the athlete sees what they said and may add any other skill. It is optional.
   - A skill that was not asked about is unknown, never weak. Earlier answers about it are kept for Your skills.
   - The same is asked about a skill whoever chooses it, and position never hides one, so it has no bias
     toward a skill or a kind of player.
2. Answers are saved first (`athleteAssessments/{uid}`), then sent to Groq through the worker
   (`POST /assessAthlete`) without a name, account, video or team.
3. Groq returns a summary, strengths, weaknesses, improvements, focus, up to three practices
   and weekly goals. The worker checks it, and the app checks it again.
4. The plan is saved, with every earlier one in `plans/{id}`.

There is no stored result to fall back on. When the AI is away or over its limit, the answers
stay saved and the plan is written later.

## The AI cannot invent performance

Each fact has an id and a leaning. A point must name its facts, for the same skill:

| Fact | For | Against | Neither |
|---|---|---|---|
| `say:` an answer, with what they say happens | Yes | Not yet | Sometimes, not tried |
| `score:` camera form score | 80 or more | under 60 | 60–79 |
| `check:` a camera check's trend | Solid | Slipping, work on this | Improving |
| `weak:` something they find hard (answers from before the cards) | | always | |

A strength needs a fact for it and none against. A weakness needs one against and none for.
An improvement needs one that is not for it. A `weak:` fact alone is never enough. A point
that fails is dropped. A sentence with a digit, a link, a level label, a medical word or a claim
of watching is dropped; if nothing is left the answer is refused. Scores on screen come from the app.

**What "measured" means:** the pose model, the angle rules and the trained receiving model.
Serving, setting, spiking and blocking have no trained model of their own.

## The plan

- Drills: only for the assessed skills, only ones this player can open, at their level or the closest there is.
- Level: from experience (new → Beginner, 1–2 years → Intermediate, 3+ → Advanced) until there
  are scores; then under 60, 60–79, 80 or more.
- A practice fits the minutes given: `drill minutes × sets × reps / the drill's reps`.
- At most three practices, and never more than the days given.
- The assessed skill with the lowest strength cannot be left out. Focus and goals stay with the assessed skills.

## Updates

A new plan is written when the app opens, after at least 7 days **and** 3 newly scored
sessions. It is given the new scores, trends, finished drills and coach marks. "What changed"
is worked out from the two plans' facts, not written by the AI.

## Limits

- 15 requests a day per athlete and 200 a day for everyone; a refused or rejected answer is tried up to twice more (`openai/gpt-oss-120b` first, then `openai/gpt-oss-20b`, which has its own Groq allowance, then without a strict schema). Requests stay well under Groq's 8,000 tokens a minute, and only a reason code is logged.
- Athletes with nearly the same answers can get similar advice.
- A Free player can open five Beginner drills, so only sets, reps and goals can vary.
- Answers and plans are private to the athlete; coaches cannot read them.
- Older app versions keep using the old worker route, which still exists for them.
