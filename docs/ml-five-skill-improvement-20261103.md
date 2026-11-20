# Five-skill improvement run — 3 November 2026

The requested target is at least 80% measured correctness for **each** skill on
independently labelled, previously unseen players. No skill currently has that
verified result. A combined average, training accuracy, one-class test or a
six-example arm-only result cannot satisfy the target. Fault precision/recall,
macro-F1, uncertainty coverage and player-separated confidence intervals must
also be reported so an apparently high accuracy does not conceal missed faults.
The existing calibrated acceptance and release gates remain in force.

## Development comparison

The new run preserves the earlier reports and compares linear, TCN, LSTM and
GRU candidates at learning rates 0.0003, 0.001 and 0.003, with 30 epochs,
training-only augmentation and class weighting. Entire linked sources stay
together. The current corpus is still provisional and has already been exposed
during development. Hyperparameter exploration on these folds does not create
an independent test or select a production winner.

The run completed all **48 development candidates** (12 for each eligible
skill). Its exit code 2 indicates development-only/needs-data status, including
the correctly refused whole-skill Blocking fit; it is not a successful release.
`target-status.json` in the run directory records the 0.80 target with verified
accuracy unavailable for every skill.

Artifacts: `outputs/ai-coach-five-skill-improvement-20261103/report.json` and
its per-skill `comparison.json`, dataset manifests and candidate exports.

The source-held-out measurement limitations remain:

| Skill | Evaluated examples | Evaluated faulty examples | Interpretation |
|---|---:|---:|---|
| Serving | 1 | 0 | Cannot measure fault detection |
| Receiving | 10 | 4 | Best explored development accuracy 60%, macro-F1 0.583; no independent player verification |
| Setting | 1 | 0 | Cannot measure fault detection |
| Spiking | 2 | 0 | Cannot measure fault detection |
| Blocking | 0 | 0 | Whole-skill binary fit needs faulty data |

Receiving's best development GRU confusion matrix is `[[2, 2], [2, 4]]`, with
rows actual and columns predicted in `fault, ok` order. Fault precision and
recall are both 0.50. Its best explored score is unchanged from the earlier
comparison. The smaller-rate LSTM improved development macro-F1 from about
0.451 to 0.524, which is an exploratory configuration result, not verified
generalization. All candidates retain an abstaining, uncalibrated policy.

Whole-skill Blocking still needs an eligible faulty example; the trainer must
refuse that binary fit rather than fabricate a class. The existing visible-arm
Blocking experiment has a different scope and remains separate.

The contemporaneous locked audit is
`outputs/ai-coach-locked-audit-20261103/report.json`. Serving, Receiving, Setting
and Spiking lack the required verified train/tune/calibration/test registry.
Their two or three linked source groups also cannot supply the necessary
independent cohorts simply by assigning new partition names. Blocking has no
eligible faulty whole-skill example. No app score or AI-generated label was
promoted to independent expert evidence.

## Review preparation

`prepare_coach_review.py` now supports all five skills and original legacy
segment CSV/video pairs. It preserves timing, source grouping, clip hashes and
per-skill feature fingerprints. It refuses missing media before creating a
partial output and refuses to overwrite existing worksheets. The worksheet
leaves actual labels, identities, qualifications, corrections and drills blank.

The local packet `outputs/five-skill-coach-review-20261103` contains 25 examples:
4 Serving, 10 Receiving, 5 Setting, 4 Spiking and 2 Blocking. The ten legacy
Receiving rows retain their original segment timings within the supplied source
videos; their short badge intervals need explicit completeness review. Source
narration can reveal the creator's opinion. This is development label review,
not a blinded or untouched accuracy evaluation. Nothing was uploaded or shared.

The expanded data-integrity suite passed **89 tests**. No Android model was
activated by this work. Additional public-source checks are documented in
[dataset sources](ml-dataset-sources.md); action and rally-outcome labels were
not substituted for correct/faulty mechanics.

A read-only physical-app cloud recheck passed on the same date: four own
sessions, one linked coach and zero coach notes. No new coach labels were found.
The private audit remains in ignored outputs; no recordings were uploaded or
shared by this recheck.

## Evidence needed for the next training cycle

A qualified coach must review complete actions, identify the actual athlete and
technique variant, and judge only visible mechanics. Unobservable or ambiguous
examples need exclusion rather than a forced label. Newly collected players
must be assigned to separate train, tuning, calibration and locked-test cohorts
before model selection; related recordings and alternate cuts stay together.
The additional footage needs both correct and faulty demonstrations for every
skill, especially complete Blocking actions. This review and new cohort are the
remaining external dependencies; running more epochs on the same examples
cannot verify the 80% target.
