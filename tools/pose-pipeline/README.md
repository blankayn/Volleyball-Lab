# Pose extraction pipeline

Turns a folder of volleyball clips into labelled, camera-independent pose
sequences you can train a form classifier on.

It runs the **same pose model the Android app runs** —
`app/src/main/assets/pose_landmarker_full.task`, up to three poses, 0.5 confidences — and
computes its features with a **direct port of the app's `Angles.kt`**, in the same
world coordinate space. A threshold derived here is therefore expressible as a
`FormCue` target range without translation.

That second half was originally wrong and it mattered. The pipeline first measured
angles in image space — a perspective projection — while every analyzer in the app
consumes MediaPipe's metric, hip-centred world landmarks. Both produce plausible
degrees. Measured against each other on 143 clean frames of real passing footage:

```
overall mean disagreement: 23.1 degrees
off by more than 10 degrees: 70.5%
off by more than 25 degrees: 32.5%   (elbows up to 128)
```

`compare_spaces.py` is that measurement and is kept deliberately. It does not go
to zero after the fix — the gap is a property of projecting 3D onto 2D, not of any
bug — so it stays as the standing argument for why the world-space port exists.

`test_parity.py` pins the port against the Kotlin's own unit-test vectors,
transcribed from `AnglesTest.kt` and `TestPoses.kt`. Run it after touching
`features.py`:

```bash
.venv/Scripts/python test_parity.py
```

## Setup

Needs Python 3.11 — MediaPipe does not publish wheels for the newest releases.

```bash
python -m venv .venv && .venv/Scripts/python -m pip install -r requirements.txt
```

The pose model is not committed. Fetch it per `app/src/main/assets/README.md`, or
point `--model` somewhere else.

## Use

```bash
.venv/Scripts/python extract.py samples --out data --overlay
```

```bash
.venv/Scripts/python summarise.py data
```

## MPJPE validation from the updated manuscript

`evaluate_mpjpe.py` implements the manuscript's mean per-joint position error:
the mean 3D Euclidean distance between a predicted joint and an independently
annotated joint, in millimetres. It reports overall, per-skill, per-clip and
per-joint results, along with the fraction of annotations that were measurable.
The manuscript's **< 50 mm** value is a target, not a measured result. No MPJPE
value should be claimed until calibrated ground truth is supplied.

Run `extract.py` on the evaluation clips first, using the same model bundle as
the app. Prepare a CSV with one row per annotated joint:

```csv
clip,skill,frame,joint,x_mm,y_mm,z_mm
serve_trial_01,serve,42,LEFT_SHOULDER,12.0,-415.0,36.0
```

`clip` is the input video's stem; `frame` is zero-based. The supported joints are
both shoulders, elbows, wrists, hips, knees and ankles. Coordinates must be
calibrated 3D positions in the **same hip-centred axis system** as MediaPipe's
world landmarks. Keep the annotation method and coordinate transform with the
dataset. Pixels or 2D image landmarks have no recoverable depth in millimetres,
so the evaluator rejects a 2D-only CSV. Do not use MediaPipe's own output as the
"ground truth"; that would measure the model against itself.

```bash
.venv/Scripts/python evaluate_mpjpe.py data ground_truth_3d.csv --out data/mpjpe_report.json
```

The report excludes joints with model visibility below 0.5 or missing pose
coordinates and exposes that exclusion as `coverage`. It applies no fitting or
rescaling to make the error look smaller. Record the device, camera position,
lighting and skill for every evaluation clip, and review poor-coverage skills
separately rather than trusting a low MPJPE from a tiny visible subset. The
five-skill target remains `null` until every skill has valid annotated joints.

`--overlay` writes an annotated mp4 per clip. Watch one before trusting a batch;
both heuristics below are tuned, and tuned things drift when the footage changes.

## What comes out

| File | Contents |
|---|---|
| `<clip>_frames.csv` | One row per frame: shot, segment, label, quality flags, 14 joint angles |
| `<clip>_segments.csv` | One row per demonstration: boundaries, label, how usable it is |
| `<clip>_landmarks.npz` | Raw pixel and normalised 33-point skeletons, visibility, validity |
| `<clip>_sequences.npz` | Per-segment angle sequences resampled to a fixed length — the training tensor |
| `manifest.json` | Run totals worth reading before training anything |

## How it decides what a demonstration is

**Cuts** are found by comparing HSV histograms between consecutive frames, with
both an absolute and a relative bar so that fast movement does not read as an
edit.

**Badges** — the green tick and red cross that coaching reels stamp on their own
demos — are found by colour, then filtered on size, aspect, solidity, flatness,
and position. Position does most of the work: a green foliage mural painted on a
gym wall is otherwise a perfectly badge-shaped blob, and only *where it sits*
separates it from a real mark.

**Segments** are cut at both — every cut boundary and every badge transition. The
badge is the finer and more important signal, because it is the editor's own
segmentation: they stamped the mark on precisely the stretch they were grading.
Cutting on edits alone gave a six-second shot in the sample clip that contained
both a correct demo and a faulty one, which is unusable at any label.

If badges are being missed, widen `BADGE_REGION` in `video.py` first, and use
`debug_badge.py <clip> <frame>` to read the actual blob geometry rather than
guessing at a threshold.

## How badges are told apart from scenery

Worth writing down, because three separate clips each broke a different guess.

The emoji are strikingly consistent — the green tick measures **hue 59.6-59.7,
saturation ~195, extent ~0.94** across unrelated clips, because it is the same
Unicode asset every time. The things that imitate it are not:

| Blob | hue | saturation | area % |
|---|---|---|---|
| Green ✓ emoji | 59.6–59.7 | 195–196 | 1.8–2.3 |
| Red ✗ emoji | ~163 | 248 | 0.9 |
| Painted gym mural | 41.4 | 180 | 0.4 |
| Orange sports floor | 8.4–11.7 | 159–189 | 7.2–9.1 |
| Skin | 0–10 | 139–152 | varies |

So: narrow hue bands reject the mural and the floor, mean saturation rejects
skin, and an area ceiling rejects anything the size of a court. Position is no
longer used at all — the second clip stamped its ticks at the top of frame and a
lower-centre gate silently inverted its labels.

## Measured on the sample clips

Three clips carry badges: `serve_receive` (4 ok / 4 fault), `passing_easy2`
(8 / 3), `beginner_tips` (3 / 3, mixed skills). Feature separation over 1110 clean
frames, in pooled standard deviations:

| Feature | ok | fault | separation | unit |
|---|---|---|---|---|
| `knee_r` | 138.6 | 155.6 | 0.92 | degrees |
| `knee_mean` | 139.8 | 153.1 | 0.78 | degrees |
| `facing_camera` | 0.94 | 0.84 | 0.50 | ratio |
| `knee_l` | 141.0 | 150.6 | 0.49 | degrees |
| `trunk_forward_lean` | 18.3 | 11.2 | 0.45 | degrees |
| `elbow_mean` | 135.0 | 137.3 | 0.11 | degrees |

Bent knees and a forward chest on the correct reps; straighter and more upright on
the faulty ones. That is what a coach would say, which is mild evidence the
measurement is real.

**These numbers are lower than the image-space ones they replaced**, and that is
the point rather than a regression. The old separations (`torso_lean` 0.99,
`platform_tilt` 0.89) were partly measuring a projection that correlates with
where the athlete happened to stand, so they flattered themselves.

**They are diagnostics, not thresholds.** Two athletes, three sessions. There is
still no way to separate "this is correct form" from "this is what these two
people look like".

**`facing_camera` at 0.50 is a warning, not a feature.** It measures how square
the athlete is to the lens — a property of the camera, not of the rep. If it keeps
separating the classes as clips are added, the model is learning that the faulty
demos were filmed at a different angle. Watch it.

## Source codecs

Downloads arrive in whatever the platform served. OpenCV's bundled FFmpeg is
narrower than the standalone one, and an HEVC, AV1 or VP9 clip will open, read
zero frames, and report nothing useful. The pipeline detects that and transcodes
to H.264 through ffmpeg automatically, so mixed batches just work.

One case it cannot rescue: **TikTok serves ByteDance's proprietary `bvc2`**, which
no standard decoder handles - not OpenCV, not ffmpeg 9. The clip is unusable and
the only fix is to fetch it again forcing H.264:

```bash
yt-dlp -f "bv*[vcodec^=avc1]+ba/b[vcodec^=avc1]" <url>
```

The failure names the codec so this is obvious rather than mysterious.

## Known issues

- **`hitting_side` and the three columns derived from it are per-frame, not
  per-rep.** The app resolves the hitting arm once, from the rep's contact frame.
  This pipeline has no reps, so `elbow_hitting`, `shoulder_hitting` and
  `shoulder_tossing` use whatever the frame itself says. `hitting_side` is emitted
  as its own column precisely so a later rep segmenter can take the contact
  frame's value and recompute the other three correctly.
- **`hip_rise_m` is always NaN here.** Jump height needs a standing-hip baseline
  that only a rep segmenter knows. `vertical_scale` and `hip_centre_image_y` are
  its two ingredients and are both emitted, so it can be filled in later without
  re-running MediaPipe.
- **Segments are not reps.** A segment is a stretch with one label. In short-form
  coaching clips that is usually one demonstration, which is close enough to be
  useful and is not the same thing. Real rep segmentation needs the fault
  taxonomy to exist first.
- Landmark confidence is worst exactly at ball contact — arms extended toward the
  camera, motion blur. On the sample clip only 54% of frames are fully clean. For
  forearm passing the instant you most want to measure is the instant the
  detector is least sure of, and that belongs in the evaluation chapter.
- Badge geometry is tuned on vertical short-form clips. Landscape coaching video
  that marks its demos elsewhere in frame will read as unlabelled until
  `BADGE_REGION` is widened.
- **Split-screen comparisons are rejected, not handled.** Stacking the same
  athlete at three moments with a mark on each panel is common in short-form
  coaching content, and it is untrainable as things stand: the detector returns
  one skeleton for the whole frame, so there is no way to know which panel it
  came from. Any frame carrying more than one badge returns None. Handling these
  properly means detecting the panel layout and running the detector per panel -
  worth doing if a lot of source footage turns out to be built this way.
- Skin sits in the same hue band as a red cross and is otherwise blob-shaped,
  compact, and centre frame. On a clip where the athlete stood close to the
  camera this produced six confidently mislabelled "fault" segments before the
  mean-saturation test existed. If badges start appearing on footage that has
  none, check that first with `debug_badge.py`.
