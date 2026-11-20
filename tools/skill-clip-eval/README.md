# Public-clip check of the movement rules (Serving, Setting, Spiking, Blocking)

The app's movement rules were run on 31 public coaching clips (in
`tools/pose-pipeline/samples/`) to see how they behave on people other than our
team. These are the same rules that decide whether a rep is counted live and in
the session result. Results are in `results/evaluation.md`.

This is a check of the existing rules, **not** a trained model. Training was not
possible: see the last section.

## Pipeline

1. `extract_landmarks.py` finds the body in each clip with the phone's own pose
   model (`pose_landmarker_full.task`, one pose, 0.65 confidences). It keeps every
   second frame (15 fps) and writes `landmarks/<clip>.csv`.
2. `app/src/test/.../pose/analysis/PublicClipReplay.kt` replays those frames
   through the app's Kotlin code: `RepSegmenter`, `SkillAnalyzer.verdict` and
   `SessionScorer.scoreRep`. It writes `results/candidates.csv`, one row per
   movement. Refused movements are kept, together with the reason.
   ```bash
   VL_CLIP_REPLAY=1 ./gradlew :app:testDebugUnitTest --tests '*PublicClipReplay*'
   ```
3. `candidate_sheets.py` draws four frames of every movement so each one can be
   labelled.
4. `labels.csv` gives each movement a label, together with **the on-screen evidence
   for it**: the video's own tick or cross, or a caption such as "Don't spike like
   this". There are six labels:
   - `correct`: shown as the right way.
   - `faulty`: shown as a mistake.
   - `drill`: a training variant, such as a wall drill or a spike from a box.
   - `not_a_rep`: talking, holding the ball, or a footwork-only drill.
   - `wrong_skill`: the tracked person is doing a different skill.
   - `exclude`: an edit cut, many players, no mark, or the wrong person tracked.
     Any movement no label row covers counts as `exclude`.
5. `evaluate.py` joins the labels with the replay output and writes
   `results/evaluation.md` and `results/labelled_movements.csv`.

Labels come from the video creators' marks. They were read off the screen by
Claude and have **not** been checked by a coach. Have a coach review
`labels.csv` before quoting any figure.

## Findings, before the fixes below (17 November 2026)

- **Movements that aren't reps are refused.** The app counted 1 of 49 talking,
  ball-holding and footwork movements. The one it counted was a serve-toss
  demonstration.
- **Correct Setting and Spiking reps are counted.** It counted 10 of 10 correct
  sets and 10 of 11 correct spikes, all from front or side views.
- **Correct serves filmed from behind are often refused.** It counted 4 of 7
  correct serves, and 3 of the 5 rear-view serves were refused as
  "ReachOverhead". The rear view hides which hand is the hitting hand.
- **Faulty reps are mostly not caught.** "Don't set like this" was counted with a
  score of 78. "Don't spike like this" was counted with scores of 89 and 43. The
  rules decide whether a movement *is* the skill; they don't judge technique
  quality. The cue scores did not separate these reps from correct ones.
- **Spikes can be counted as blocks.** When the tracked person was a hitter
  attacking a block pad, the Blocking check counted 8 of 19 movements. Both hands
  rising during a spike approach can pass the block test.
- **No usable Blocking reps.** Every block clip either had many players or tracked
  the coach or the hitter instead of the blocker.

## Fixes made from these findings (17 November 2026)

Two rule changes in `SkillAnalyzers.kt`, each with a unit test in
`MovementVerdictTest`. Each test fails on the old rule and passes on the new one.

| | Before | After |
| --- | --- | --- |
| Correct serves counted | 4 of 7 | 6 of 7 (the one left: arms hidden from behind) |
| Correct spikes counted | 10 of 11 | 11 of 11 |
| Serve-toss demo counted as a serve | 1 | 0 |
| Spikes counted as blocks | 8 of 19 | 1 of 19 |
| Symmetric block-like movements in the clinic clip, still counted | 5 | 5 |

- **Serve and spike:** each hand is judged at its own peak across the whole
  movement, not at the smoothed "contact" frame. When the toss and the swing are
  more than 150 ms apart, the later of the two peaks is the hitting hand. Frames
  where tracking briefly shrinks the torso are ignored.
- **Block:** the two hands must peak within 250 ms of each other, and the other
  hand must still be up when the higher one peaks. Otherwise the coach says
  "Bring both hands up together." On this footage, blocks peaked 0–67 ms apart
  and spikes 333 ms or more.

The block change has **not** been checked on clean single-athlete block reps,
because none of these clips had any. Check it with your own blockers on the
phone before relying on it.

## Why nothing was trained

| Skill | Correct reps (videos) | Faulty reps (videos) |
| --- | --- | --- |
| Serving | 7 (4) | 1 (1) |
| Setting | 10 (2) | 1 (1) |
| Spiking | 11 (2) | 5 (3) |
| Blocking | 0 | 0 |

The Receiving model was trained on 48 clips and tested on whole held-out videos.
Here no skill has more than 5 faulty reps. With leave-one-video-out testing, each
test fold would hold one or two faulty reps, so any accuracy figure would be
noise. A model trained on these clips would also learn the few demonstrators
rather than the fault. The minimum used here is 20 correct and 20 faulty reps
from at least 5 videos per skill. `evaluate.py` checks it.

## Rights

The clips are third-party videos, kept locally for this evaluation only. Do not
redistribute them. The landmark CSVs contain body coordinates only, with no
images.
