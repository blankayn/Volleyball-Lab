# Adaptive coaching

Built 25 November 2026. The app learns from each player's own right and wrong reps and
changes how it coaches that player. It is automatic: no switch, no form, no retraining.

## What it learns from

| Source | Counted as |
|---|---|
| Every measured check of every saved rep | Right at a score of 0.8 or more, otherwise wrong |
| An attempt refused for a posture check | Wrong for that check |
| A verified coach's Right / Wrong mark on a rep | Replaces the camera's answer; the latest mark wins |

A check that was not measured is left out, never counted as wrong.

## How it decides (`domain/model/AdaptiveCoaching.kt`)

It compares the last 20 reps of a check with the 20 before. At least 6 reps are needed.

| Trend | Rule, in this order |
|---|---|
| Slipping | at least 15 points fewer right than before |
| Solid | 80% or more right |
| Improving | at least 15 points more right than before |
| Work on this | anything else |

`need = weight of the check × share of recent reps that were wrong`. Highest need first.

**Worked example.** Knee bend: 8 of the last 20 right, 15 of the 20 before. 40% is 35
points below 75%, so it is **Slipping**. Weight 0.30: need = 0.30 × 0.60 = 0.18.

It also remembers how each correction was said (direct, rehearse, reset, short, feel) and
whether the next two measured reps were in range or clearly closer. A style's rate is
`(helped + 1) / (tried + 2)`, so an untried style is 0.5. Results fade after 20 tries.

## What changes

| Where | Change |
|---|---|
| Start of a live session | The coach opens with what changed: slipping, improving, still the most to gain, or solid |
| During the session | Corrections focus on this player's needs; a solid check steps aside; the explanation that helped this player most comes first |
| Results | "AI coach: what to do next", one suggestion: a drill of this skill for the check that needs work, another drill when the score is 80 or more, or another skill when the last 3 sessions of this skill all scored under 60 without gaining 10 points. The trends themselves are not shown |
| Coach website | Right / Wrong on each rep, with what was wrong. The player's AI coach learns from it |

## What never changes

Measurements, ranges, scores and what counts as a rep. A player cannot get a better score
because the coach adapted, so scores stay comparable and traceable (`score-computation.md`).

## Where the data is

Sessions are in the player's account as before. The memory of explanations and refused
attempts stays on the phone, per account, and is removed with the account. Nothing is
pooled between players, so one player's habits never affect another.

## Limits to say plainly

- It adapts **coaching decisions**. It does not retrain the pose model or the receiving model.
- The thresholds (20, 6, 80%, 15 points) are design choices, the same for everyone.
- It can only learn from what the camera measures, plus the coach's marks. If the camera is
  wrong and no coach marks the rep, it learns the wrong answer for that rep.
- On a new phone the trends return with the sessions; remembered explanations start again.
- A model shared by all players and retrained from their data is **not built**.
