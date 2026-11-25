# AI assessment and weekly plan

Built 25 November 2026. Code: `ai-worker/src/athlete-assessment.mjs`,
`domain/model/AthleteAssessment.kt`, `data/assessment/AthletePlanner.kt`.

## Flow

1. The athlete answers structured questions: experience, position, preferred skills, goal,
   days, minutes, four statements for each skill, and what feels hard. Two may be in their own words.
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
| `say:` an answer | Yes | Not yet | Sometimes, not tried |
| `score:` camera form score | 80 or more | under 60 | 60–79 |
| `check:` a camera check's trend | Solid | Slipping, work on this | Improving |
| `weak:` something they find hard | | always | |

A strength needs a fact for it and none against. A weakness needs one against and none for.
An improvement needs one that is not for it. A `weak:` fact alone is never enough. A point
that fails is dropped. AI text with a digit, a link, a level label, a medical word or a claim
of watching is refused. Scores on screen come from the app.

**What "measured" means:** the pose model, the angle rules and the trained receiving model.
Serving, setting, spiking and blocking have no trained model of their own.

## The plan

- Drills: only ones this player can open, at their level or the closest there is.
- Level: from experience (new → Beginner, 1–2 years → Intermediate, 3+ → Advanced) until there
  are scores; then under 60, 60–79, 80 or more.
- A practice fits the minutes given: `drill minutes × sets × reps / the drill's reps`.
- At most three practices, and never more than the days given.
- The skill with the lowest strength cannot be left out. Position hides nothing.

## Updates

A new plan is written when the app opens, after at least 7 days **and** 3 newly scored
sessions. It is given the new scores, trends, finished drills and coach marks. "What changed"
is worked out from the two plans' facts, not written by the AI.

## Limits

- 8 requests a day per athlete and 200 a day for everyone; a rejected answer is tried once more.
- Athletes with nearly the same answers can get similar advice.
- A Free player can open five Beginner drills, so only sets, reps and goals can vary.
- Answers and plans are private to the athlete; coaches cannot read them.
- Older app versions keep using the old worker route, which still exists for them.
