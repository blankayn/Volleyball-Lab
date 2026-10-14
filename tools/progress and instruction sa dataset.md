# Pose pipeline handoff

How the extraction pipeline, the TensorFlow classifier and the Android analyzers
fit together — what runs today, what needs data, and the one rule that keeps the
two halves honest.

There is a formatted version of this document published as a web page. This copy
is the one that travels with the repo, and it is the authoritative one.

| | |
|---|---|
| Feature columns | 24, world space, per frame |
| Labelled segments | 11 passing, from 2 clips |
| Kotlin tests | 125, 0 failures |
| Python parity checks | 16, all pass |
| Model | 10.8 KB TFLite |

---

## Where it stands

Read this before quoting any accuracy number to anyone.

| Status | Item |
|---|---|
| **Working** | **Extraction pipeline.** Six clips through it. Handles mixed codecs, detects edits, reads ✓/✗ badges, flags unusable footage. |
| **Working** | **Feature parity with the app.** `features.py` is a port of `Angles.kt`, pinned by the Kotlin's own test vectors. |
| **Fixed** | **Three analyzer defects.** `shoulderTilt`, `trunkForwardLean` and `penetrationMetres` all measured against the camera where they meant the athlete. Corrected on both sides with regression tests. |
| **Needs data** | **The classifier.** Trains, exports, loads. Its weights are placeholder — 11 segments from 2 clips, memorised. |
| **Blocked** | **App integration.** Nothing worth integrating until the model has a defensible number. Wiring it now ships confident wrong feedback. |

### The number to not quote

Training accuracy reads **1.000**. It is memorisation: 2,818 parameters over 11
examples from one athlete. `train.py` refuses to print a holdout figure below
three source clips and writes the reason into `form_classifier.json` instead.
**Leave that guard in.**

---

## Running the pipeline

Four stages, in order — each one's output is the next one's input.

### 1. Collect clips

Short-form coaching clips that stamp a green ✓ or red ✗ on their own demos. Those
badges are the labels, and the ✗ half is the class you cannot get anywhere else —
professionals rarely demonstrate bad form.

Screen on five things:

- **one person** in frame (a coach standing beside the demonstrator breaks it)
- **one panel** — no split-screen comparisons
- **badges present** — ✓/✗ marks, not numbered text steps
- **static camera**
- **moving reps**, not static holds

Search the corrective framing — "common mistakes", "stop doing this" — not
"tutorial", which yields numbered steps and no marks.

Roughly half will fail screening. Budget ~40 downloads for 20 usable.

> TikTok sometimes serves ByteDance's proprietary `bvc2` codec, which no standard
> decoder handles. Re-download forcing H.264:
> `yt-dlp -f "bv*[vcodec^=avc1]+ba/b[vcodec^=avc1]" <url>`

### 2. Extract

Tag every batch with its skill. Pooling skills into one classifier measurably
weakens every feature — a tick means *correct serve* or *correct pass*, never
"correct".

```bash
cd tools/pose-pipeline
.venv/Scripts/python extract.py samples --out data --skill pass --overlay
```

`--overlay` writes an annotated mp4 per clip. **Watch one before trusting a
batch** — the badge reader is tuned, and tuned things drift when the footage
changes. If badges go missing, run `debug_hue.py <clip> <frame>` and read the blob
table rather than guessing at a threshold.

### 3. Check before training

```bash
.venv/Scripts/python summarise.py data
```

Prints each feature's mean for correct versus faulty reps, ranked by separation in
pooled standard deviations. Above ~0.8 is a feature worth keeping; near zero is
one the model cannot learn from. Finding that out here costs a second; finding it
out after training costs a week.

> **Watch `facing_camera`.** It currently separates the classes at 0.50. That
> measures how square the athlete is to the lens — a property of the camera, not
> the rep. If it keeps separating as clips are added, the model is learning that
> faulty demos were filmed at a different angle.

### 4. Train

```bash
cd tools/form-classifier
../pose-pipeline/.venv/Scripts/python train.py --skill pass --out models
```

TensorFlow lives in the pose-pipeline venv rather than a second 600 MB copy.

The script splits **by source clip, never randomly**: two segments from one video
share an athlete, a gym and a session, so a random split measures memorisation and
reports it as accuracy. That is the easiest way to end up defending a fake number.

---

## Getting to a defensible number

The binding constraint is **source clips, not segments**.

| Milestone | Clips | Segments | What it unlocks |
|---|---:|---:|---|
| Today | 2 | 11 | Pipeline exercised, no reportable metric |
| First honest accuracy | 3 | ~20 | `train.py` starts printing a holdout figure |
| Minimum defensible | ~10 | ~100 | A confusion matrix that survives questioning |
| Target for the paper | 15–20 | 250–300 | Per-skill thresholds worth shipping |

**Two more passing clips is the jump that matters.** It moves you from "no number"
to "a number you can defend" — much closer than the segment count suggests.

### Where the faulty examples come from

Every rep in a good clip is a professional executing correctly. A model that has
never seen a bent-elbow platform will tell every beginner their form is fine.
Three sources, and only one scales:

1. **Deliberate faults from your own volunteers.** Hand them the fault list: "now
   do ten forearm passes with your elbows bent." Clean, correctly labelled,
   camera-matched bad reps on demand.
2. **"Common mistakes" coaching clips.** Genuinely useful, but perhaps one or two
   per skill exist.
3. **Beginners doing their honest best.** Naturally faulty, but every rep needs
   hand-labelling.

Self-recorded should be roughly 70% of the final set. Eight to ten volunteers ×
five skills × twenty reps is 800–1,000 reps across two or three sessions.

### Ground truth stays human

If an AI assigns the good/faulty labels, your accuracy stops measuring correctness
and starts measuring agreement with the labeller. Pre-labelling — model proposes,
human reviews every one — is legitimate and about 3× faster. For the subset you
report MPJPE against, keep it fully manual.

---

## Wiring it into the app

Read this before writing any integration code — the first point changes what you
build.

### The app already scores form

It has a working rule-based analyzer: `Angles.kt`, `RepSegmenter.kt`,
`SkillAnalyzers.kt`, and `FormCue` with phase-aware target ranges. So the
classifier is not the only path, and possibly not the best one.

The pipeline's most valuable role right now may be **deriving the target ranges for
cues that already exist** rather than shipping a second model. `FormCue.kt` says so
itself — its thresholds are "first-pass values drawn from general coaching
guidance, not from measured data". Replacing them is a contained edit to one file,
and every feature the pipeline emits now mirrors a measurement the analyzers
already make.

### If you do ship the classifier

Two models, and only one of them was ever trained here. **BlazePose is frozen** and
already in `app/src/main/assets/`. The form classifier is the small one on top.

| Contract | Value |
|---|---|
| Input tensor | `[1, 32, 24]` float32 |
| Output tensor | `[1, 2]` float32 |
| Class order | `["fault", "ok"]` |
| Timesteps | 32, resampled per rep |
| Features | 24, order fixed by `FEATURE_NAMES` |
| Normalisation | 24 means + 24 stds, in the JSON |

> **`form_classifier.json` is not optional.** It ships beside the `.tflite`, and the
> app must apply the same per-feature mean and standard deviation before calling
> the model. Feeding it raw angles produces confident nonsense rather than a
> visible error — the worst failure mode there is.

Steps the Kotlin side has to reproduce, in order:

1. compute the 24 features via `Angles.kt`, in **world space**
2. segment the rep
3. resample to 32 timesteps
4. apply mean/std from the JSON
5. invoke

`hitting_side` and the three columns derived from it must take the **contact
frame's** value, not each frame's own.

---

## The parity rule

Both implementations measure the same geometry, and **they must move together**.
When one changes, the other changes in the same commit.

| Android | Pipeline |
|---|---|
| `app/.../pose/analysis/Angles.kt` | `tools/pose-pipeline/features.py` |
| Consumed by every analyzer | A deliberate port |
| Verified by `AnglesTest.kt`, 14 cases | Verified by `test_parity.py`, which transcribes the Kotlin's own vectors |

```bash
.venv/Scripts/python test_parity.py   # after touching features.py
./gradlew :app:testDebugUnitTest      # after touching Angles.kt
```

This is not ceremony. Measuring form offline with different geometry than the
phone ships is a mistake that only surfaces late, on a device, as "the app
disagrees with our own numbers" — long after the cause is forgettable. The
pipeline originally measured in image space and disagreed with the app by **23° on
average**, with elbow angles diverging by up to 128°. Both produced
plausible-looking degrees. That is exactly the problem.

### Three conventions that are easy to get backwards

- World **y increases downward**. Four places compensate, and each does it
  differently.
- World **z decreases toward the camera**, and the axes are the *camera's*, not the
  body's — which is what caused all three analyzer defects.
- `atan2` argument order flips depending on whether you measure from vertical or
  from horizontal. Swapping them yields the complement, which looks like a
  plausible angle.

`gradlew` needs `JAVA_HOME` set. Android Studio's bundled JDK works:
`C:\Program Files\Android\Android Studio\jbr`.

---

## Open items

Known, deliberate, and worth someone's attention.

| Item | Where | Why it is open |
|---|---|---|
| **Segments are not reps** | `video.py` | A segment is an edit boundary; a rep is an athlete action. Close enough to be useful, not the same thing. Real rep segmentation needs the fault taxonomy first. |
| **`hip_rise_m` is always NaN** | `features.py` | Jump height needs a standing-hip baseline only a rep segmenter knows. Both ingredients are emitted, so it can be filled in later without re-running MediaPipe. |
| **`hitting_side` is per-frame** | `features.py` | The app resolves it once from the contact frame. Emitted as its own column so a segmenter can redo the three dependent columns correctly. |
| **Split-screen rejected, not handled** | `video.py` | One skeleton for the whole frame, no way to know which panel. Any frame with two badges returns no label. |
| **Some faults are invisible to pose** | — | "Don't interlock fingers" needs MediaPipe Hands. Pose gives one wrist landmark and no finger detail. |
| **Three stale doc comments** | `PoseFrame.kt`, `Angles.kt` | `PoseFrame` claims world axes are "aligned to the body rather than the camera" — the opposite of the truth, and the belief that produced the defects. |

### Capture protocol

Landmark confidence is worst **exactly at ball contact** — arms extended toward the
lens, motion blur. On the best sample clip only 81% of frames are fully clean; on
the worst, 34%. For forearm passing the instant you most want to measure is the
instant the detector is least sure of. That belongs in the evaluation chapter
rather than being discovered during the defence.

---

## Layout

```
tools/
├── pose-pipeline/          video → labelled pose sequences
│   ├── extract.py          main pipeline
│   ├── features.py         the port of Angles.kt — 24 features
│   ├── video.py            shot detection + badge reading + codec handling
│   ├── summarise.py        feature separation check
│   ├── test_parity.py      Python ↔ Kotlin agreement
│   ├── debug_badge.py      badge threshold diagnostics
│   ├── debug_hue.py        badge colour diagnostics
│   ├── compare_spaces.py   measures the 2D-vs-3D gap
│   ├── .venv/              1.9 GB, gitignored — rebuild with requirements.txt
│   ├── data/               generated, gitignored
│   └── samples/            source clips, gitignored
└── form-classifier/        pose sequences → TFLite model
    ├── train.py            Keras + TFLite export
    ├── label_clips.py      manual timestamp trimming (alternative path)
    └── models/             gitignored — placeholder weights today
```

Every claim in this document was measured against 7,318 frames of real footage.
