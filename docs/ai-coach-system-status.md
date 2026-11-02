# AI Volleyball Coach status — 31 October 2026

The training and coaching software now covers all five skills. Reliable trained
coaching performance is **not yet established**. There is no defensible overall
accuracy percentage, and the historical receiving result is not an accuracy
measure for all skills.

## Latest app and recording checks

The current Blocking research recipe is `blocking_arm_time_v2`, with a fresh
four-architecture comparison and strict rejection of mixed recipes. Shared
Python/Android fixtures cover both arms, low presence, occlusion, missing poses
and irregular dropped frames. Python recomputation of an actual phone-emitted
window agreed within 0.000015259 for features and 5.96e-8 for probabilities.
This establishes preprocessing conventions on measured inputs, not independently
validated pose or coaching accuracy. The [input contract decision](adr/0001-version-temporal-blocking-inputs.md)
records the incompatible version boundary.

The full physical-phone replay completed 100 inferences, but p95 processing was
382.02 ms, above the 150 ms budget. Stage profiling identified repeated video
seeking as the largest cost. A sequential-decoder diagnostic passed at 125.84 ms;
the optimized 100-inference acceptance and final color/rotation/scale checks are
still pending. A color-format compatibility fix and platform fallback are built;
the wireless phone disconnected during its update. See the
[performance investigation](ml-mobile-pipeline-performance.md) for exact artifacts,
limits and outstanding checks. The deployment audit remains false.

An earlier physical Infinix X6833B running Android 14 ran the experimental
visible-arm TCN for 100 inferences, with zero invalid outputs. Classifier-only
p95 was 0.14 ms; maximum probability difference from desktop probes was
5.96e-8. These measurements exclude video decoding, pose estimation and feature
extraction. They establish native runtime compatibility, not coaching accuracy
or end-to-end mobile performance. The model remains in instrumentation-test
assets; the main app does not ship the experimental weights.

The student's new Blocking recording was checked locally with consent. It
contains visible arm raises, jumps and pauses. Pose extraction found the player
in 97.3% of decoded frames. All five existing app windows had reliable selected-arm
landmarks in the offline extraction, but three failed the narrow arm rise-and-return
guard. This is evidence to review segmentation, not evidence of incorrect technique.
The recording has no independent correct/faulty labels and contributes neither
supervised training examples nor accuracy results. Private media, identity and
window-level results remain in ignored local outputs.

Training previously used a different detector configuration. The extractor now
uses the app's one-pose, 0.65 detection/presence/tracking thresholds and confirmed
primary-player tracking. A fresh public-data re-extraction preserves the original
reports. Mixed detector configurations are rejected during training ingestion.
The subsequent v2 feature comparison addresses emitted-frame timing and missing
samples. VIDEO versus LIVE_STREAM pose accuracy across new people, cameras and
lighting remains unestablished; matching settings and emitted feature arithmetic
alone cannot establish it.

The optional Blocking adapter requires a validated manifest and exact weights;
its packaging audit rejects the current development models. Independent coach
labels, separated-player calibration/test cohorts and full phone-pipeline evidence
remain required. A new segmentation guard rejects windows whose signal peak is
at a boundary, where preparation or recovery is absent. This fixes that specific
partial-action case; it does not validate every recorded repetition boundary.

Current ML regression verification: 86 tests passed. Pose/tracker alignment:
7 tests passed. The v2 baseline passed 351 Android unit tests plus build/lint
with zero lint errors (159 warnings, 5 hints), and its two physical-device tests
passed. Decoder unit coverage subsequently increased the Android suite to 356
passing tests. Final integrated-reader lint and device acceptance are pending.
The earlier debug update installed successfully on the connected phone with `-r`.
The existing Blocking video retained its original 33,284,279-byte size and the
saved-session store remained present. The fix applies to subsequent segmentation;
previously saved session scores were not rewritten. Experimental model assets
are absent from the main APK.

The [full-release data follow-up](ml-full-release-followup.md) documents the
subsequent expanded annotation search, additional source reviews and stronger
source-identity handling. Review-queue counts are not trained-model accuracy.

A subsequent [visible-arm blocking experiment](ml-blocking-visible-arm.md)
isolates two faulty and two corrected arm cycles under a distinct narrow task.
This does not change the whole-block data counts or establish general blocking
accuracy; the experiment has only one source athlete/session.

The latest expansion described in that document has six demonstrations across
two sources and source-held-out development predictions. It still lacks a
separate coach-reviewed test/calibration cohort and remains outside app assets.

## What was trained and measured

The development run compares a linear baseline, causal temporal CNN, LSTM and
GRU using 32-frame pose-feature sequences, 30 epochs, training-only augmentation,
training-only normalization and class weighting. Temporal candidates also use
within-sequence feature differences. Multiple learning rates can be supplied for
tuning; the recorded run used 0.001. No transformer was trained on this tiny corpus.

| Skill | Correct / faulty segments | Held-out coverage | Limitation |
|---|---:|---:|---|
| Serving | 2 / 2 | 25% | Evaluated held-out samples contain only correct demonstrations |
| Receiving | 6 / 4 | 100% | Both classes evaluated; athletes and labels remain unverified |
| Setting | 3 / 2 | 20% | Evaluated held-out samples contain only correct demonstrations |
| Spiking | 2 / 2 | 50% | Evaluated held-out samples contain only correct demonstrations |
| Blocking | 2 / 0 | Not evaluated | No eligible faulty demonstration; binary training refused |

These are segments, not independently reviewed players or independent actions.
Folds lacking either training class are explicitly skipped. Replays from the
same source stay together. Perfect scores on a single correct held-out segment
do not demonstrate fault detection.

Receiving development macro-F1 was approximately 0.400 (linear), 0.286 (TCN),
0.451 (LSTM), and 0.583 (GRU). These results describe agreement with provisional
labels on source-held-out demonstrations. They do not establish unseen-player
performance or justify selecting a production winner.

The machine-readable report contains precision, recall, F1, confusion matrices,
probabilities, fold exclusions, class support, calibration measures and confidence
intervals: `outputs/ai-coach-development/report.json`. All sixteen experimental
candidates exported using native TFLite built-in operations. Export verification
compares available samples with TensorFlow and records numerical differences,
decision changes, model sizes and host CPU timing. That timing excludes decoding,
pose estimation and feature extraction. No connected phone was available for
an end-to-end device benchmark. Candidates remain outside the Android assets.

The final all-sample export recheck passed for all sixteen candidates with no
raw class-decision changes and maximum probability difference below 0.0021.
This establishes numerical export fidelity on those inputs, not classification
accuracy. Details are in `outputs/ai-coach-development/export-revalidation.json`.

## Reliability gates

`train_coach_system.py` defaults to locked evaluation. A reviewed registry must
identify athletes and assign disjoint training, tuning, calibration and test
partitions. Linked identities include source video, athlete, session, supplied
source hash and identical feature sequences. Unknown athletes cannot pass the
unseen-player gate. Review flags alone are assertions, not proof of expert review.

Training selects configurations on tuning data. Temperature and acceptance
thresholds use separate calibration data. The locked test checks the fixed policy
once; failure causes abstention rather than test-driven threshold changes.
Selective acceptance requires both classes, adequate coverage and conservative
sample, linked-group bootstrap and group-level confidence bounds. Sparse data
cannot obtain a trusted confidence score simply through large softmax values.

The current locked audit correctly blocks the four experimental skills and marks
blocking as needing data. Every current candidate abstains. No new learned
classifier was installed or represented as a calibrated coach. Prior researcher
exposure to a test set cannot be verified automatically; independent replication
is still needed even after registry validation.

## Coaching implemented in the app

All five skills have evidence-based assessment records and a results card:

- A visible technique estimate uses the current provisional reference ranges,
  repeated valid measurements and sufficient cue coverage. It is not a learned
  probability or a validated professional grade.
- Body-position measurements show their median, reference and sample count.
  Repeated directional deviations can produce a specific explanation, correction
  and drill. An isolated observation cannot become a diagnosis.
- Coordination and timing **quality scores remain unavailable**. Complete,
  sufficiently visible movement windows can show duration, pose-peak timing,
  shoulder-normalized stance width and knee-to-arm peak lag as observations.
  These do not establish ball-contact timing, jump takeoff or landing quality.
- Tracking gaps break movement windows. Low visibility, missing phases and
  nonfinite measurements withhold evidence. Jump height is not treated as a
  technique fault.
- Progress comparisons require the same skill, drill and known camera view,
  using the same current reference ranges. They describe observed changes.
- Model confidence remains unavailable until validated calibration exists.
  Optional generated notes use the selected measured correction and must retain
  its approved next action; unsupported event claims are rejected.

Motion summaries persist locally and through cloud session records without raw
landmarks. Older records remain readable. These estimates do not replace a
coach-reviewed assessment, and the application does not claim full biomechanical
analysis of every joint or complete spike mechanics from an occluded camera.

## MPJPE and remaining work

Actual MPJPE is **unmeasured**. MediaPipe world coordinates are predictions, not
ground truth. A meaningful MPJPE result needs paired, time-aligned, calibrated
metric 3D reference joints with a documented joint mapping and alignment policy.
The existing MPJPE evaluator can compute this when such data exist.

The user confirmed that no coach-reviewed athlete-identified recordings or
calibrated 3D ground truth are available. Public-source research and rejected
clips are documented in [dataset sources](ml-dataset-sources.md),
[spike follow-up](ml-spike-source-followup.md) and
[architecture research](ml-system-research.md). The next data requirements are
usable correct and faulty blocks, more independently reviewed demonstrations
across athletes and camera conditions for every skill, and a genuinely withheld
evaluation cohort. More epochs on these same clips cannot resolve these limits.

## Reproduction and validation

See [the training README](../tools/form-classifier/README.md) for commands,
registry fields and release limitations. Generated artifacts live under
`outputs/ai-coach-development`, `outputs/ai-coach-locked-audit` and the associated
logs. Generated outputs are local artifacts, not versioned training assets.

Regression validation: 44 form-pipeline unit tests, 2 MPJPE utility tests and
344 Android unit tests passed. Debug APK assembly and Android lint completed
successfully. Unit tests use
controlled fixtures to verify software behavior; they are not an independent
athlete evaluation or evidence of professional coaching accuracy.
