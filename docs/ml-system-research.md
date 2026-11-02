# ML system research — 30 October 2026

Primary-source investigation for temporal volleyball assessment, evaluation,
uncertainty, mobile deployment and MPJPE. Recommendations below are engineering
judgments for this repository, not measured improvements or accuracy claims.

## What the current implementation establishes

The current corpus has only 4 serving, 10 receiving, 5 setting and 4 spiking
segments; blocking has two positive segments and no eligible faults. Labels are
provisional creator demonstrations/AI reviews, and athlete independence is not
verified. See [`ml-training-progress.md`](ml-training-progress.md).

`train.py` uses global average pooling plus a linear output below 50 examples.
That baseline receives a sequence but discards its order; it cannot learn whether
arm preparation precedes takeoff. `evaluate_loso.py` checks that every training
fold retains both classes. `evaluate_architectures.py` currently lacks that
check, compares architectures on the same held-source folds that it reports,
and does not perform nested selection. Those results must remain exploratory.
Both tools currently group videos, not verified players across videos.

## Practical temporal candidates

Start comparison with the order-invariant baseline, a small temporal Conv1D
network, a small LSTM and a small GRU. Keep input length, eligible samples and
outer splits identical. Temporal convolutions are a reasonable candidate, not a
guaranteed winner for volleyball: Bai et al. compare convolutional/recurrent
sequence models on other benchmarks, not this dataset.
[Original TCN study](https://arxiv.org/abs/1803.01271).

Keras provides LSTM and GRU layers with configurable units, dropout and recurrent
behavior. Choose a deliberately small search space and record parameter counts;
more capacity is especially easy to memorize with four examples. A transformer
can be implemented as an experimental comparator, but these data cannot justify
selecting it as the strongest model.
[Keras LSTM](https://keras.io/api/layers/recurrent_layers/lstm/),
[Keras GRU](https://keras.io/api/layers/recurrent_layers/gru/).

Preserve original timestamps, duration, phase boundaries and missing-observation
masks alongside resampled sequences. Resampling all clips to 32 steps removes
absolute-duration information; include measured duration/velocity descriptors
when claiming timing analysis. Do not time-reverse demonstrations or apply time
warps that could change a timing-error label. Landmark jitter, dropout and
camera transformations need physical and label-preserving review. Augment only
training partitions, preserve handedness/categorical columns correctly, and do
not count augmented copies as additional independent athletes.

## Validation and model selection

Group splits are needed because repeated measurements from one person can
otherwise overstate generalization. `GroupKFold` keeps groups apart;
`StratifiedGroupKFold` additionally attempts class balance but cannot guarantee
it when the available group/class structure is insufficient. Validate the
actual label support in every partition rather than trusting the split name.
[Official grouped-validation guide](https://scikit-learn.org/stable/modules/cross_validation.html).

Recommended split identity is the connected component of shared athlete,
original video/session and replay identifiers. A player appearing in two videos
must not bridge train and test. If athlete identity is unknown, a held-video
result is **not** a held-player result. Store immutable sample IDs and split
assignments so future cuts/augmentations inherit their original partition.

Use inner grouped validation for hyperparameters/early stopping and an outer
grouped test for the selected model. Selecting the best architecture on the same
test folds used to announce its performance introduces selection optimism.
Keep a final untouched athlete-held-out test if the data support it.
[Official nested-CV example](https://scikit-learn.org/stable/auto_examples/model_selection/plot_nested_cross_validation_iris.html).

Fit normalization, imputation, feature selection, balancing weights, augmentation,
calibration and decision thresholds using only their permitted training or
validation groups. Report per-class precision/recall/F1 and support, confusion
matrix, macro F1 and accuracy. An undefined metric or missing test class needs an
explicit unavailable value/reason, not a fabricated zero or perfect score.
Add group-level uncertainty estimates once there are enough independent groups;
thousands of frames from one person do not create thousands of independent tests.

## Confidence and abstention

A large softmax value is not proof that the prediction is likely correct.
Temperature scaling is a simple post-hoc calibration candidate; its effectiveness
in published image/text experiments does not establish calibration here.
[Guo et al., original calibration paper](https://proceedings.mlr.press/v70/guo17a.html).

Calibration needs predictions from data disjoint from classifier fitting.
Evaluate reliability bins, negative log likelihood and Brier score, and retain
the calibration split and fitting sample counts. Isotonic calibration is
particularly susceptible to overfitting small datasets. With this corpus,
confidence should remain explicitly uncalibrated/unavailable rather than being
presented as a correctness percentage.
[Official calibration guidance](https://scikit-learn.org/stable/modules/calibration.html).

Choose abstention thresholds on validation groups, then report both error among
accepted predictions and coverage on untouched groups. A high score after
rejecting nearly everything is incomplete without the rejected denominator.
Abstain separately for missing phases, poor landmarks, wrong/ambiguous actor,
unsupported camera view, out-of-distribution motion and model uncertainty.
Selective prediction trades coverage for risk; its published guarantees do not
transfer automatically to a new dataset or arbitrary thresholds.
[Original selective-classification study](https://arxiv.org/abs/1705.08500).

## Mobile compatibility and measurement

Official LiteRT documentation recommends standard Keras LSTM and describes
conversion to fused LSTM operations. It does not establish equivalent fused-GRU
support for every Keras/TensorFlow combination. Convert each exact trained model,
run real inference, compare Keras/LiteRT output numerically, and record failures.
Do not silently enable Select TF Ops or custom operations to make a candidate
look mobile-ready. Test fixed input shapes, state resets and missing-data behavior.
[Official RNN conversion guide](https://developers.google.com/edge/litert/conversion/tensorflow/rnn).

Benchmark initialization, warmup, steady-state inference and memory on the target
phone/runtime/delegate. Desktop Python timing is a desktop result, not Android
latency. Measure the complete camera→pose→features→classifier→feedback path as
well as the classifier alone; pose estimation may dominate. Compare quantized
and float exports on the same untouched examples before accepting a speed/size
tradeoff.
[Official LiteRT benchmarking guide](https://developers.google.com/edge/litert/models/measurement).

## MPJPE is a separate benchmark

MediaPipe outputs image landmarks and estimated three-dimensional world
coordinates. Its Android guide describes world coordinates in meters with the
hip midpoint as origin; those estimates are predictions, not independent ground
truth. Their existence does not establish metric-depth accuracy in a volleyball
capture.
[Official Android Pose Landmarker guide](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker/android).

The existing `tools/pose-pipeline/evaluate_mpjpe.py` computes mean Euclidean joint
error in millimeters against calibrated 3D annotation CSVs, rejects missing 3D
coordinates, and reports per-skill coverage. Retain that contract. Never label
MediaPipe's output with itself to obtain zero MPJPE. Match joint definitions,
camera axes, scale, frame synchronization and hip origin. If alignment is added,
report aligned error separately from the original unaligned metric. Classification
accuracy and MPJPE must not be combined into one accuracy percentage.

AthletePose3D is a potential athletic 3D-pose benchmarking lead, not a confirmed
five-skill volleyball form-error dataset. Its authors provide motion/pose data,
report velocity-estimation limitations and publish a non-commercial scientific
research license. Check the actual motion inventory, joint mapping and license
before treating it as applicable app training data.
[Author repository](https://github.com/calvinyeungck/AthletePose3D).

## Blocking data: no new confirmed solution

SportSkills publishes positive/negative demonstration annotations, but prior
local inspection found mixed demonstrations, crop/actor failures and noisy
volleyball tags. Those annotations can propose review candidates; they do not
replace athlete identities or independent coaching labels.
[Author dataset instructions](https://vision.cs.utexas.edu/projects/sportskills/dataset.html).

VNL-STES provides timed/spatial volleyball event labels, including blocking. That
supports action localization, not the distinct question of technically correct
versus faulty blocking. An unsuccessful block is not automatically faulty body
mechanics, and a successful block does not prove correct mechanics.
[Author dataset description](https://hoangqnguyen.github.io/stes/).

This investigation found no newly confirmed, accessible dataset satisfying the
missing complete-block fault/actor/visibility requirements. Keep blocking
training blocked on eligible negative examples. Collect/review original,
head-to-toe block executions with both classes across multiple athletes and
sessions, annotate specific phase/cue faults, and identify original source and
replay relationships before claiming the fifth model is trained/evaluated.

## Work that can be completed now

Implement the common temporal-model interface, grouped/nested split audits,
training-only augmentation and balancing, metric/calibration/abstention reports,
mobile conversion checks, evidence-based coaching and explicit unavailable
results for all five skills. Synthetic tests can verify those mechanisms but
cannot supply real performance metrics. Independent player testing, trustworthy
calibration, blocking form classification and measured volleyball MPJPE remain
data/measurement gates rather than percentages of code completion.
