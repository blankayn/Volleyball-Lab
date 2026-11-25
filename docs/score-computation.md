# How every number is computed

Written for the panel comment of 24 November 2026: "Trace / justify statistical
values and computational ratings". Paths are under
`app/src/main/java/com/volleyballlab/player/` unless another root is stated.

In the app: open a session report and tap **How this score was computed**. It shows
every line below for that session, and the lines add up to the score
(`PanelComplianceTest.thePointsShownAddUpToTheScoreShown`).

## 1. From the camera to a measurement

MediaPipe gives 33 body points per frame. A cue is measured at one moment of the rep
(loading, contact or follow-through) from the points it needs. If a point is hidden or
the camera angle cannot show the cue, the cue is **not measured**. It is never counted
as zero. Code: `pose/analysis/SkillAnalyzers.kt`, `SessionScorer.kt`.

## 2. Score of one cue in one rep

Let `x` be the measured value, `[a, b]` the range and `t` the tolerance.

```
d = a - x   if x < a
d = x - b   if x > b
d = 0       otherwise
cue score = max(0, 1 - d / t)          0 to 1
```

Inside the range is 1. It falls in a straight line and reaches 0 at `t` outside it.
Why a line: a pass/fail mark would treat 1° off the same as 40° off.
Code: `FormCue.score` in `domain/model/FormCue.kt`.

**Worked example.** Serving arm extension has range 155–180° and tolerance 30°. A rep
measured at 146°: `d = 155 - 146 = 9`, so the score is `1 - 9/30 = 0.70`, shown as 70.

Receiving has one addition: for front-view knee and trunk, a ready-posture rule or model
may set the rep's value to 0 or 1 (`ReceivingReadyPostureRule.kt`). It affects the live
rep and section 3, not the session score in section 4.

## 3. Score of one rep, and "form score"

```
rep score  = 100 × Σ(cue score × weight) / Σ(weight of the cues that were measured)
form score = mean of the rep scores of the session
```

A rep counts as **good** when its rep score is at least 80 and at least 75% of the
weight was measured. Code: `RepResult.formScore`, `SessionResult.formScore` in
`domain/model/TrainingSession.kt`; `isValidFor` in `domain/model/WeeklyProgress.kt`.
Skill progress uses the mean form score of the **latest five** scored sessions
(`SkillRecommendation.recentScoreFor`), so one bad day or old results do not decide.

## 4. Session score on the report

```
check score   = mean over the session's reps of section 2, × 100
coverage      = Σ(weight measured) / Σ(weight of the checks the drill trains)
session score = Σ(check score × weight) / Σ(weight measured)
```

A check needs at least **3 measured reps**. Coverage must be at least **75%**, or no
score is shown. Jump height is never scored because it is a camera estimate.
Code: `CoachAssessmentEngine`, shown by `ScoreBreakdown`.

**Worked example (serving, all four checks measured):**

| Check | Check score | Weight | Points |
|---|---:|---:|---:|
| Arm extension | 70 | 0.35 | 24.5 |
| Reach | 90 | 0.30 | 27.0 |
| Trunk | 100 | 0.20 | 20.0 |
| Toss arm | 60 | 0.15 | 9.0 |
| **Session score** | | **1.00** | **80.5 → 81** |

The session score and the form score can differ by a few points: the session score
leaves out a check with fewer than three measured reps and the receiving override.

**Why these limits.** Three reps is the smallest number with a middle value that one
bad frame cannot decide. 75% stops a score built on one or two checks. 80 is the
project's chosen mark for a good rep. These are design decisions, not statistics from a
study; they are applied the same way to every player.

## 5. Weights and ranges

Weights within a skill add up to 1.00; the app refuses to start the catalogue otherwise.
They were chosen by the team from coaching guidance: checks at ball contact weigh most.
They are **not** fitted to data.

| Skill | Check | Range | Tolerance | Weight |
|---|---|---|---:|---:|
| Serving | Arm extension | 155–180° | 30° | 0.35 |
| | Reach height | 140–180° | 35° | 0.30 |
| | Trunk lean | −10–15° | 20° | 0.20 |
| | Toss arm lift | 90–160° | 35° | 0.15 |
| Receiving | Platform straight | 165–180° | 25° | 0.30 |
| | Platform height | 25–90° | 15° | 0.10 |
| | Knee bend | 110–150° | 30° | 0.30 |
| | Trunk angle | 15–40° | 20° | 0.15 |
| | Shoulders level | 0–12° | 15° | 0.15 |
| Setting | Hands above forehead | 2–40 cm | 15 cm | 0.35 |
| | Elbow bend | 80–120° | 30° | 0.25 |
| | Knee bend | 130–165° | 30° | 0.20 |
| | Follow-through | 155–180° | 35° | 0.20 |
| Spiking | Knee load | 80–115° | 35° | 0.20 |
| | Arm extension | 155–180° | 30° | 0.25 |
| | Elbow height | 0–90° | 20° | 0.20 |
| | Jump height (recorded, never scored) | 0.30–1.00 m | 0.20 m | 0.20 |
| | Trunk drive | −5–25° | 25° | 0.15 |
| Blocking | Arm extension | 160–180° | 30° | 0.30 |
| | Hand spacing | 15–45 cm | 20 cm | 0.25 |
| | Forward reach | 5–35 cm | 15 cm | 0.25 |
| | Knee load | 90–125° | 35° | 0.20 |

### What published studies say

| Our check | Published value | Agreement |
|---|---|---|
| Receiving, platform straight 165–180° | Elbow at contact 172.44° ± 5.08 (7 NCAA Division I players) and 161.29° ± 7.46 (7 junior players) [1] | Supports it: skilled mean inside, low-skilled mean outside |
| Receiving, trunk 15–40° | Trunk at contact 19.84° ± 10.19 (skilled) and 8.75° ± 4.66 (low-skilled) [1] | Supports it: skilled inside, low-skilled outside |
| Receiving, knees 110–150° | Knee at contact 147.38° ± 10.61 (skilled) and 143.18° ± 11.74 (low-skilled) [1] | Both inside. The check does not separate skill levels in that study |
| Spiking, arm extension 155–180° | Elbow flexion at contact about 34° ± 10, so an elbow angle near 146° (14 collegiate women, cross-body spike) [2] | Our range may be too strict. A rep at 146° scores 70 |
| Serving, reach 140–180° | Shoulder abduction about 130° at contact [2] | A different angle definition; our range may be too strict |

[1] Ridgway, M. E., & Hamilton, N. *The Kinematics of Forearm Passing in Low Skilled
and High Skilled Volleyball Players.* ISBS Conference Proceedings, Table 2.
<https://ojs.ub.uni-konstanz.de/cpa/article/view/2336>. Read in full. Angles were measured from
the side, as ours are, but by hand from film.

[2] Reeser et al. (2010). *Upper limb biomechanics during the volleyball serve and
spike.* Sports Health, 2(5), 368–374. <https://pmc.ncbi.nlm.nih.gov/articles/PMC3445065>.
**Only a search summary was read; open the paper and check these two numbers before
citing them.**

No source with usable numbers was found for setting or blocking arm position. Those
ranges rest on coaching guidance alone.

**What is still missing.** The ranges are provisional until volleyball coaches review
them. The manuscript's coach interviews are the step that would replace them, and each
range is one line in `FormCue.kt`.

## 6. Skill check (before there are sessions)

Four statements for each of the five skills, with the same wording pattern: ready
position, movement, control, and after moving. Skills appear in a different order for
each player, so no skill gains from being first.

```
Yes = 1, Sometimes = 0.5, Not yet = 0, Not tried = left out
answer share = 100 × Σ / number answered        (needs at least 2 answers)
strength     = latest-five form score if one exists, otherwise the answer share
```

The two are never mixed, and the screen says which one is shown. There is no "beginner"
or "style" label. Code: `domain/model/SkillCheck.kt`.

## 7. Assessment and weekly plan

The AI writes words; it never writes or changes a number. See `docs/ai-assessment.md` for
the evidence rule, the difficulty rule and when a plan is rewritten.

## 8. Coach rating and coach checks

Star rating = plain mean of the players' 1–5 reviews. A player can review only after that
coach gave feedback on a shared session (`firestore.rules`, `coachReviews`).

Coach checks are five yes/no facts, not a score:

| Check | Passed when |
|---|---|
| Email | confirmed by the sign-in service, coach signed in within the last year |
| Institution | that email ends in a restricted school or government domain (`.edu`, `.edu.ph`, `.gov.ph`, `.ac.xx`, `.sch.xx`) |
| Details | a full name and a school or club |
| Known | at least 2 coaches with an institution email say so |
| Players | at least 3 different players left a review |

**Verified** = email + details + one of (institution, known, players), or verified by the
project owner. Otherwise **Not verified**. Players see only those two words.

## 9. What the numbers cannot say

- Pose accuracy: the measured error on one volleyball benchmark is 172.7 mm, above the
  manuscript's 50 mm target (`docs/mpjpe-3d-benchmark-status.md`). Small score
  differences should not be over-read.
- The app does not track the ball, so accuracy of a serve or a pass is not scored.
- A school email shows someone belongs to that school, not that they coach there. The
  system cannot check an ID, and someone could make several player accounts to add reviews.
