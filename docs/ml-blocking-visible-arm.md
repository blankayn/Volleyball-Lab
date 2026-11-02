# Blocking visible-arm experiment — 31 October 2026

This experiment addresses a narrow observable part of blocking: the demonstrated
visible arm's reach and hold versus the creator's quick-withdrawal examples.
It does **not** establish a trained whole-skill blocking coach.

## Data and labels

The source is Coach Ardy's
[How to Block a Volleyball — Beginner to Advanced](https://www.youtube.com/watch?v=fJxB8sPCbiM).
The creator introduces the unwanted quick arm withdrawal near 336–342 seconds,
then demonstrates reaching forward, holding and releasing near 366–373 seconds.
Quarter-second visual review identified four distinct arm cycles:

| Window | Provisional demonstration label |
|---|---|
| 337.8–339.6 seconds | Faulty quick-withdrawal example |
| 339.75–342.4 seconds | Second faulty example |
| 366.7–370.3 seconds | Proposed corrected reach/hold example |
| 370.4–373.1 seconds | Second corrected example |

These include standing arm rehearsals. They are not four independently verified
complete block jumps. The source's instruction and AI review supply provisional
labels; no independent volleyball coach verified the dataset. All four belong
to the same source, athlete and session and retain the original source SHA-256.
Static arm preparation without a complete arm cycle was not counted as another
repetition. No replay was duplicated to increase the sample count.

## Why a different feature contract is appropriate

The whole-body model needs knees/ankles and both arms. Its existing data gate is
unchanged. These clips do not qualify as whole-body training examples.

The attempted bilateral arm recipe also failed: the far-side elbow/wrist are
occluded in the corrected demonstrations. The final task therefore uses one
fixed visible arm per cycle and never diagnoses the hidden arm. Selection uses
pose observability before consulting the class label; it cannot switch arms
frame by frame. All four recorded examples select the anatomical left arm.
Right-arm performance is unverified.

`blocking_arm_dataset.py` uses seven landmarks: nose, both shoulders, both hips,
and the selected arm's elbow and wrist. Its six measurements are the visible
elbow angle, shoulder angle, wrist height relative to the shoulder and nose,
forward wrist reach and image-space wrist rise. An observation-mask channel is
included. The recipe does not consume feet, knees, the hidden elbow/wrist,
jump height, airborne duration, landing quality or ball-contact timing.

The consumed joints still require visibility at least 0.5, finite coordinates,
image bounds and a valid skeleton; a segment still needs at least 50% reliable
original frames. All four selected visible-arm examples passed. This is input
observability, **not model accuracy**. Missing measurements remain NaN with an
explicit zero observation mask. Nearest-original-frame sampling preserves gaps;
augmentation keeps masks and entire frames together. Temporal differences across
unobserved frames are zeroed rather than treating imputed zeros as real motion.

## Reproduction

Reviewed selections and extraction provenance are local artifacts under
`outputs/online-form-data/review/block-visible-arm-selections.json` and
`outputs/online-form-data/extracted-block-visible-arm/provenance.json`.

```powershell
& tools/pose-pipeline/.venv/Scripts/python.exe tools/form-classifier/blocking_arm_dataset.py `
  --extracted outputs/online-form-data/extracted-block-visible-arm `
  --out outputs/online-form-data/corpus-block-visible-arm

& tools/pose-pipeline/.venv/Scripts/python.exe tools/form-classifier/train_coach_system.py `
  --data outputs/online-form-data/corpus-block-visible-arm `
  --out outputs/ai-coach-block-visible-arm --skills block --mode development `
  --epochs 30 --augment-copies 1
```

The scope is `block_visible_arm_reach_hold`; the feature recipe is
`blocking_arm_dataset_v1`. It must remain separate from whole-skill form datasets
and from the earlier block-to-attack retreat experiment.

## Interpretation and release gate

The original experiment compares a linear baseline, temporal CNN, LSTM and GRU. Source-
linked folds cannot train and hold out different sources because only one source
exists. Therefore independent accuracy, precision, recall, F1 and confidence
calibration cannot be established from this corpus. Resubstitution accuracy
would only describe fitting these four examples and is not a performance result.

Every current candidate must abstain in coaching inference, remains outside
Android model assets and has deployment approval set to false. Native export
checks establish numerical fidelity only. Whole-skill blocking, independent
player testing, professional feedback validation and MPJPE remain unfinished.
More varied labelled demonstrations and independent testing are required before
this prototype can provide reliable live coaching.

## Recorded training and export results

The 30-epoch development run completed with two eligible `fault` and two eligible
`ok` arm demonstrations in one linked source group. Four models were actually
fitted and exported:

| Candidate | Native TFLite bytes | Maximum probability difference versus TensorFlow |
|---|---:|---:|
| Linear baseline | 1,744 | 2.98 × 10⁻⁸ |
| Temporal CNN | 13,064 | 1.19 × 10⁻⁷ |
| LSTM | 95,124 | 8.94 × 10⁻⁸ |
| GRU | 110,244 | 5.96 × 10⁻⁸ |

Every export was executed with native built-in operations on all four available
inputs, with zero raw class-decision changes. Export fidelity is not coaching
accuracy. Host classifier timing excludes video decoding, pose estimation and
feature extraction; these models have not been benchmarked end to end on a phone.

Held-out coverage is zero: the only source group cannot be split into train and
test without leakage. Independent accuracy and development test metrics are
therefore null, no production winner is selected, and every decision policy is
`abstain`. The run's exit code `2` denotes development-only status, not a failed
fit. Results and preprocessing contracts are in
`outputs/ai-coach-block-visible-arm/report.json` and the adjacent per-model JSONs.

The ML regression suite passed 65 tests, including a real TensorFlow graph check
that missing pose frames do not produce fake motion differences. No Android code
or app model asset was changed by this experiment.

## Expansion to a second creator and source-held-out testing

The subsequent pass added a matched pair from Caleb Kwekel's
[How to block in beach volleyball](https://www.youtube.com/watch?v=Zrr1po_FpLU):
8.1–10.8 seconds for the creator's high-arm counterexample and 14.5–17.3 seconds
for the proposed forward-reaching demonstration. These are provisional
visible-arm labels, not general blocking grades. The latter window ends before
the following camera cut. Both are single continuous arm cycles; neither was
duplicated into extra repetitions.

The fixed-arm recipe selects the right arm for both new examples. Consumed-joint
quality was 1.0 and 0.928571 respectively. The complete expanded corpus contains
six demonstrations (three per class), two original source videos, and both left-
and right-arm examples. Athlete identities and labels are still not independently
verified. Camera view, rehearsal type and error type vary with source and can
confound what the model learns.

Two leave-one-source-out folds were evaluated. Each fold trains on one entire
source and evaluates the other, preserving both classes without source overlap.
All six examples received a held-out prediction:

| Candidate | Source-held-out correct / total | Development macro-F1 | Confusion matrix, rows actual fault/ok |
|---|---:|---:|---|
| Linear | 4 / 6 | 0.625 | `[[1,2],[0,3]]` |
| Temporal CNN | 5 / 6 | 0.829 | `[[3,0],[1,2]]` |
| LSTM | 4 / 6 | 0.625 | `[[3,0],[2,1]]` |
| GRU | 4 / 6 | 0.625 | `[[3,0],[2,1]]` |

The TCN's five-of-six result is **not a verified production accuracy percentage**.
Six weakly labelled examples across two sources are far too few; its one error
called a proposed correct demonstration faulty. The four/ two split sizes also
give folds very different training support. No separate expert-reviewed player
test or calibration cohort exists. No production winner was selected, and all
exported policies remain `abstain` with deployment approval false.

The four expanded candidates were refitted on all six development examples and
exported as native TFLite models. All six export probes agreed in raw class
decision with TensorFlow; maximum probability difference was below 9 × 10⁻⁸.
That establishes numerical fidelity only. Android assets were not modified.
The new left/right feature-convention regression check and the complete ML suite
passed **66 tests**.

Artifacts:

- `outputs/online-form-data/review/block-visible-arm-expansion-reviewed.json`
- `outputs/online-form-data/corpus-block-visible-arm-expanded/provenance.json`
- `outputs/ai-coach-block-visible-arm-expanded/report.json`

The IVA before/after montage was not admitted because its split-screen/frozen
editing does not establish an unaltered temporal arm cycle. The VBU example
cuts camera angles through the arm action and was also excluded from this
temporal experiment. No quality threshold was reduced to admit those clips.

## Re-extraction with the current app pose configuration

The results above used the older three-pose, 0.5 person-confidence extractor.
The six original pre-cut clips were subsequently re-extracted using one pose,
0.65 detection/presence/tracking confidences and the app's confirmed primary-player
tracker. Original clips, source hashes, provisional labels, source-separated folds,
30 epochs, augmentation and the four architecture configurations were preserved.
All six remain quality-eligible; they were not supplemented with unlabelled student
recordings. Training ingestion now rejects mixed detector configurations.

| Candidate | Source-held-out correct / total | Development macro-F1 | Confusion matrix, rows actual fault/ok |
|---|---:|---:|---|
| Linear | 3 / 6 | 0.486 | `[[1,2],[1,2]]` |
| Temporal CNN | 4 / 6 | 0.667 | `[[2,1],[1,2]]` |
| LSTM | 4 / 6 | 0.625 | `[[3,0],[2,1]]` |
| GRU | 5 / 6 | 0.829 | `[[3,0],[1,2]]` |

The architecture ranking changed after detector alignment. This sensitivity on
six examples is a reason to gather stronger independent evidence, not select a
production winner. None establishes overall Blocking accuracy. All four final
native TFLite exports had zero raw class flips on the six available probes and
maximum probability difference at most 5.97e-8. Every policy remains `abstain`.

Artifacts are `outputs/online-form-data/corpus-block-arm-runtime-aligned` and
`outputs/ai-coach-block-arm-runtime-aligned/report.json`; earlier artifacts remain
unchanged. The new packaging audit is
`outputs/blocking-runtime-deployment-readiness.json` and correctly reports false.
The earlier successful physical-phone benchmark belongs to the earlier TCN hash,
not these newly fitted weights. End-to-end device processing and dropped-frame
sampling parity remain open, in addition to coach-reviewed independent-player
calibration/test data. The student recording has been inspected locally and stays
unlabelled; its automated app scores are not training labels.

## Timestamp-based recipe v2

`blocking_arm_time_v2` resolves the frame-index sampling mismatch between offline
arrays and Android's unevenly emitted poses. Both implementations sample 32
uniform elapsed-time targets between the first and last emitted pose. The nearest
original pose must be within 50 ms; otherwise measurements remain NaN with mask
zero. Integer distance comparisons choose the earlier pose on an exact tie.
Missing detections are not compressed into consecutive time steps. Unreliable
visible joints also remain masked, including low landmark presence as well as
low visibility. The observed arm is fixed using sampled-time coverage for the
entire window. Unconsumed leg/opposite-arm world values do not enter this recipe.

Both implementations require at least ten emitted poses, a window spanning
350–6000 ms and no timestamp reset or tracking gap over 500 ms. A model decision
additionally requires an observed arm rise and return and validated confidence.
These are evidence guards, not proof of correct technique or a real ball contact.

The extractor now retains presence, emitted-pose flags and millisecond timestamps.
All six reviewed public clips were re-extracted, then four models trained from
scratch using the same source folds, epochs and augmentation. Old weights were
not relabelled. Training rejects mixed feature recipes and quality profiles; the
Android manifest accepts only v2. Synthetic shared Python/Android fixtures cover
left arm, right arm, occlusion, missing detections, irregular dropped frames and
low presence, including changing feature values rather than static poses alone.

| Candidate | Source-held-out correct / total | Development macro-F1 | Confusion matrix, rows actual fault/ok |
|---|---:|---:|---|
| Linear | 3 / 6 | 0.486 | `[[1,2],[1,2]]` |
| Temporal CNN | 4 / 6 | 0.625 | `[[3,0],[2,1]]` |
| LSTM | 4 / 6 | 0.625 | `[[3,0],[2,1]]` |
| GRU | 5 / 6 | 0.829 | `[[3,0],[1,2]]` |

All final native exports had zero raw decision flips on the six available probes
and maximum probability difference at most 5.97e-8. These are still only six
provisional examples from two sources. Every decision policy remains `abstain`;
there is no production winner, independently established accuracy or calibrated
confidence. The student's own recording remains outside supervised training.

Artifacts are `outputs/online-form-data/corpus-block-arm-time-v2` and
`outputs/ai-coach-block-arm-time-v2/report.json`. The TCN is staged solely as a
test candidate for the full physical-phone replay benchmark, not selected for
deployment. See [device validation](ml-blocking-deployment.md) for its workflow.
