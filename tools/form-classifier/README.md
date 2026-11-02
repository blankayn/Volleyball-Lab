# Form classifier

## Five-skill workflow

From the repository root in PowerShell, audit the available labelled segments:

```powershell
& tools/pose-pipeline/.venv/Scripts/python.exe tools/form-classifier/all_skills.py
```

This writes `outputs/all-skills-ml/status.json` with separate coverage and missing
data reasons for serving (`serve`), receiving (`pass`), setting (`set`), spiking
(`spike`) and blocking (`block`). Exit code 2 means some skills need data; 1 means
a processing error. Accuracy stays null until evaluation runs. The deployed
receiving-ready model's 91.67% development result is separate from these
form-demonstration models and does not apply to the other skills. Extracted shots
are not guaranteed to contain complete repetitions; retain excerpt-specific
label scope and avoid treating a partial technique example as whole-skill truth.

Copy `form_manifest_template.csv` and replace its example rows with real,
coach-reviewed **pre-cut clips showing one skill and one form label**. Paths are
relative to the CSV. Use `ok` or `fault`; action names alone cannot label form.
Keep every cut from the same original video or athlete session in the same
`source_group`. Record the actual reviewer. Independent athlete/session review
is still necessary; a filled CSV field alone does not establish coaching validity.

```powershell
& tools/pose-pipeline/.venv/Scripts/python.exe tools/form-classifier/all_skills.py --manifest path/to/reviewed.csv --train
```

The manifest is validated before extraction. Duplicate video content is rejected.
Only clips in that manifest are used in that run, so old extractions cannot
silently enter a revised dataset. Each eligible skill gets leave-one-source-out
development evaluation, a separate final TensorFlow fit, TFLite export,
normalization metadata, and a real TFLite inference check. Each skill needs at
least three source groups and both labels in at least two groups; these are
minimum technical requirements, not proof of sufficient data or accuracy.
Missing skills are reported while eligible skills can continue.

Exports remain development candidates under `outputs/all-skills-ml/<skill>/`.
They are **not installed in Android**: the current app supports a narrower
six-feature receiving-ready model, while these exports take 32-frame feature
sequences. Android integration and independent validation remain required before
shipping these models. Existing rule-based feedback continues for all five skills.

Run data-integrity regression checks with:

```powershell
& tools/pose-pipeline/.venv/Scripts/python.exe -m unittest discover -s tools/form-classifier -p test_all_skills.py
```

## Existing single-skill trainer

### Online demonstrations and exploratory fits

`prepare_online_clips.py` accepts a JSON list of explicitly selected tutorial
excerpts with `id`, `skill`, `label`, `start`, `end`, and `evidence`. Downloaded
sources must be named `<YouTube-id>.mp4`. Optional normalized `crop` coordinates
are `[x, y, width, height]`; preserve the full athlete and visually check the
extracted landmarks. Source identity always remains the original video ID,
including when cropping or cutting it into several clips.

```powershell
& tools/pose-pipeline/.venv/Scripts/python.exe tools/form-classifier/prepare_online_clips.py --selections selections.json --sources source-videos --out extracted
```

The output records source URLs, hashes, timing, crop and label evidence in
`provenance.json`. These are creator demonstrations reviewed by AI, **not
independently coach-verified labels**. Talking heads, cropped athletes, mixed
demonstrations and the tracker following a stationary trainer must be rejected.
Alternate camera replays do not establish independent repetitions.

The original `usable_frac` gate requires visibility of all 33 landmarks.
`feature_quality.py` can annotate a separate `feature_usable_frac`, preserving
the original values. Its versioned profile checks the 13 landmarks actually
used by the 24 feature columns: nose, both shoulders/elbows/wrists and both
hips/knees/ankles. Every required point must have visibility at least 0.5,
finite geometry and image coordinates inside the frame; the skeleton must also
pass the existing validity check. Ears, finger tips and heels do not appear in
these features. This profile does not prove the correct person was tracked.

```powershell
& tools/pose-pipeline/.venv/Scripts/python.exe tools/form-classifier/all_skills.py --data annotated-corpus --out development-models --train --development-only-fit --quality-field feature_usable_frac
```

`--development-only-fit` permits an explicitly **unvalidated** export when both
classes have at least two eligible segments but source diversity is insufficient.
It retains the missing-data reasons, reports no accuracy, marks deployment as
unapproved, and returns exit code 2. It does not relax visibility or frame-length
requirements. Tiny fits exercise the pipeline and can memorize demonstrations;
they are not evidence that a skill is solved. `--quality-field` is optional and
defaults to the original gate. A missing selected quality field is an error.

The current online-data investigation is documented in
[`docs/ml-dataset-sources.md`](../../docs/ml-dataset-sources.md). The locally
generated source videos, contact sheets, annotations and model candidates stay
under `outputs/online-form-data/`; they are not Android model assets.

Run all training-data regression checks with:

```powershell
& tools/pose-pipeline/.venv/Scripts/python.exe -m unittest discover -s tools/form-classifier -p 'test_*.py'
```

Trains the small classifier that reads BlazePose landmarks and judges a rep.

```bash
../pose-pipeline/.venv/Scripts/python train.py --skill pass --out models
```

TensorFlow lives in the pose-pipeline venv rather than a second 600 MB copy here.
The `--data` default is a sibling hop to `../pose-pipeline/data`, so it resolves the
same on any machine that has the repo.

## Where this sits

```
video files
    ↓  ../pose-pipeline/extract.py        ← BlazePose, cuts, ✓/✗ labels
../pose-pipeline/data/*_sequences.npz      (segments, 32, 24)
    ↓  train.py                           ← this folder
models/form_classifier.tflite              11 KB, ships beside the pose model
models/form_classifier.json                classes + normalisation constants
```

`form_classifier.json` is not optional. The app must apply the same per-feature
mean and standard deviation before calling the model; feeding it raw angles
produces confident nonsense rather than a visible error.

## Two different models — don't confuse them

**BlazePose** is pretrained, frozen, and already in `app/src/main/assets/`. It is
never trained here. It turns pixels into 33 landmarks.

**The form classifier** is what `train.py` produces. It reads those landmarks and
answers "was this rep correct?".

## About label_clips.py

`label_clips.py` (already in this folder) cuts clips by hand-logged timestamps
into `trimmed_clips/<action>/`, with labels like `serve`, `spike`, `set`, `dig`,
`block`. That builds an **action classifier**: *which skill is this?*

The app already knows which skill it is — the player picks the drill before the
camera opens. So an action classifier answers a question the app never asks,
while the question it does ask — *was that pass any good, and why not?* — needs
correct/faulty labels instead.

`train.py` takes its class list from the data rather than hardcoding it, so it
trains either kind. Point it at badge-labelled segments and you get a form model;
point it at manually labelled skills and you get an action model. Nothing needs
to change but the input.

Two notes if you do run `label_clips.py`: it expects `.mp4` files in
`source_videos/`, which does not exist yet, and the `videos_sample/` folder here
is the CVPR broadcast dataset — loose JPEG frames from two matches, no
annotations, players about 100 px tall. It cannot feed either pipeline.

## Historical baseline — read this before quoting any number

The last run:

```
samples : 9   shape (32, 14)
classes : fault=4, ok=5
clips   : 1 (serve_receive)
params  : 2,018
```

Training accuracy was 1.000 and **that is not a result.** Nine segments from one
sixteen-second clip means one athlete, one gym, one session. The model can
memorise that in a few thousand parameters, and it did.

`train.py` refuses to print a headline accuracy below three distinct source
clips, and writes the reason into `models/form_classifier.json` instead of a
number. It splits by source clip and never randomly — two segments from the same
video share an athlete and a camera, so a random split measures memorisation and
reports it as accuracy.

The exported `.tflite` loads and runs correctly. What exists is a working,
verified pipeline with placeholder weights.

## Historical data collection estimate

Roughly 100 labelled segments for receive, from 15–20 different clips, is the
point where a held-out accuracy starts to mean something. Around 250–300 reps
per skill, with several athletes, is where it becomes defensible.

Those counts are collection estimates, not statistical guarantees. Independent
athletes, label review, class coverage and confidence bounds matter more than
the raw number of segments. Use the locked workflow below for evaluation.

## Five-skill temporal coach workflow (31 October 2026)

The current status and measured limitations are in
[`docs/ai-coach-system-status.md`](../../docs/ai-coach-system-status.md).
The new runner compares `linear`, `tcn`, `lstm` and `gru` on schema-v2 32-frame
features extracted from MediaPipe poses. No candidate is installed in Android.

Development comparison with provisional labels:

```powershell
& tools/pose-pipeline/.venv/Scripts/python.exe tools/form-classifier/train_coach_system.py `
  --data outputs/online-form-data/corpus-required13 `
  --out outputs/ai-coach-development --mode development --epochs 30 --augment-copies 1
```

Source/identity-linked folds prevent known overlap. Missing-class training folds
are skipped and reported. Read held-out coverage and `all_classes_observed`
alongside metrics: a perfect score on one correct segment cannot establish fault
detection. The current corpus cannot support independent coaching accuracy.
Blocking correctly refuses training because no eligible faulty segment exists.

Locked evaluation is the default:

```powershell
& tools/pose-pipeline/.venv/Scripts/python.exe tools/form-classifier/train_coach_system.py `
  --data path/to/reviewed-corpus --registry path/to/reviewed-registry.csv `
  --out outputs/ai-coach-locked --epochs 30 --rates 0.001 0.0003
```

The generated `registry-template.csv` lists sample identifiers. Reviewers must
complete `athlete_id`, optional `session_id`/`source_sha256`, `partition`
(`train`, `tune`, `calibration`, `test`) and the independently checked flags
`label_verified`, `actor_verified`, `complete_rep`, `athlete_verified` as `true`.
Source, skill and label cannot silently change through the registry. Correct
extraction errors at their source. All partitions require both classes;
calibration requires at least ten examples per class and calibration/test each
require at least three independent linked groups. These are entry checks, not
an assurance of reliable results. Confidence gates can require much more data.

Each newly reviewed selection should also supply an explicit `task_scope` in
its extraction JSON. This becomes immutable feature-bundle metadata and is
included in the generated registry template and exported model JSON. Use a
consistent scope per skill dataset. A label about retreating after a block
cannot be mixed with a label about the whole block execution. The registry
cannot retrofit or rewrite scope; re-extract the reviewed source instead.
Legacy bundles without scope remain `unspecified_form` for development only;
locked evaluation rejects an undefined or inconsistent scope.

Normalization, class balancing and augmentation use training data only. Tuning
selects architecture/rate; a separate calibration partition establishes a
temperature and selective acceptance rule. An isolated locked test evaluates
the rule. Failure disables acceptance; never retune on that test. Preserve a new
independent cohort after iterative test exposure. All current policies abstain.

Reports include actual class metrics, confusion matrices, probability calibration,
linked-group intervals, exclusions and provenance. `independent_overall_accuracy`
and `mpjpe_mm` remain null when unsupported. The historical receiving-only result
is not a total ML accuracy percentage.

Native TFLite exports require built-in operations, the exact feature order,
normalization, disabled-feature handling and decision policy in the adjacent JSON.
Do not deploy an uncalibrated candidate. Host classifier-only timings exclude
video decoding, pose extraction and preprocessing; profile the full pipeline on
target phones before claiming mobile performance. Quantization/export fidelity
does not establish coaching accuracy.

For the expanded public annotation review and original-source hash protection,
see [`docs/ml-full-release-followup.md`](../../docs/ml-full-release-followup.md).
`index_sportskills.py` produces review leads only; its outputs cannot be passed
straight into training as verified form labels. New online extractions preserve
the original video SHA-256, so renamed copies stay in the same identity group.

Regression checks:

```powershell
& tools/pose-pipeline/.venv/Scripts/python.exe -m unittest discover -s tools/form-classifier -p 'test_*.py'
```

The runner exits with code `2` when any skill is development-only, blocked or
needs data. Inspect `report.json`; that exit status deliberately avoids reporting
an incomplete research run as a production success.

## Narrow blocking visible-arm experiment

[`docs/ml-blocking-visible-arm.md`](../../docs/ml-blocking-visible-arm.md) records
the separate `block_visible_arm_reach_hold` task. It uses one fixed visible arm
and torso anchors, six pose measurements and an observation mask. It does not
judge the hidden arm, knees, feet, landing, jump height or ball contact. Never
merge its labels or tensor contract with whole-skill form models.

After reviewed, continuous arm-cycle clips are extracted with that explicit scope:

```powershell
& tools/pose-pipeline/.venv/Scripts/python.exe tools/form-classifier/blocking_arm_dataset.py `
  --extracted outputs/online-form-data/extracted-block-visible-arm `
  --out outputs/online-form-data/corpus-block-visible-arm

& tools/pose-pipeline/.venv/Scripts/python.exe tools/form-classifier/train_coach_system.py `
  --data outputs/online-form-data/corpus-block-visible-arm `
  --out outputs/ai-coach-block-visible-arm --skills block --mode development `
  --epochs 30 --augment-copies 1
```

The recorded four-example run fits four experimental candidates. All examples
are from one original video/session, so held-out performance is unavailable and
every candidate's decision policy is `abstain`. Exports require the exact recipe,
observed arm, feature order, normalization and observation-mask handling recorded
in their JSON. They remain outside Android model assets.

The later two-source, six-example expansion is recorded in the same status
document. To reproduce its source-held-out development comparison:

```powershell
& tools/pose-pipeline/.venv/Scripts/python.exe tools/form-classifier/train_coach_system.py `
  --data outputs/online-form-data/corpus-block-visible-arm-expanded `
  --out outputs/ai-coach-block-visible-arm-expanded --skills block --mode development `
  --epochs 30 --augment-copies 1
```

Its best preliminary result is five of six provisional demonstrations; it must
not be reported as overall AI Coach accuracy or grounds for production deployment.

The later re-extraction uses the app's one-pose/0.65 detector settings and confirmed
primary-player tracker; ingestion rejects mixed configurations. Its comparison is
in [Blocking visible-arm status](../../docs/ml-blocking-visible-arm.md). Use the
[packaging and device workflow](../../docs/ml-blocking-deployment.md) before any
app activation. The current development models do not pass that release audit.

The current mobile recipe is `blocking_arm_time_v2`, built with
`blocking_arm_temporal.py --extracted <fresh-extractions> --out <new-corpus>`.
Fresh landmark bundles must retain presence, detection flags and timestamps.
The existing v1 recipe stays available for reproduccing historical experiments;
its weights cannot be relabelled as v2. Generate shared synthetic parity fixtures
with `generate_blocking_parity.py` and use `prepare_blocking_device_test.py` plus
`verify_blocking_device.py` for the complete phone replay workflow linked above.
