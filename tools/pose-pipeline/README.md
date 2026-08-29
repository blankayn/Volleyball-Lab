# Pose extraction pipeline

Turns a folder of volleyball clips into labelled, camera-independent pose
sequences you can train a form classifier on.

It runs the **same pose model the Android app runs** —
`app/src/main/assets/pose_landmarker_full.task`, one pose, 0.5 confidences —
so a threshold derived here is a threshold that transfers to the phone. Measuring
form offline with a different detector than the app ships is the kind of mistake
that only surfaces on a device, late, as "the app disagrees with our own numbers".

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

Two clips carry badges. `serve_receive` (16 s, 488 frames, 4 cuts) →
**18 segments, 4 correct and 4 faulty**; `beginner_tips` (11 s, 338 frames) →
**11 segments, 3 and 3**. Feature separation on serve-receive's 126 clean frames,
in pooled standard deviations:

| Feature | ok | fault | separation |
|---|---|---|---|
| `torso_lean` | 18.1° | 7.0° | 0.99 |
| `platform_tilt` | 70.1° | 49.9° | 0.89 |
| `knee_l` | 149.0° | 162.9° | 0.87 |
| `platform_height` | −0.00 | +0.36 | 0.84 |
| `weight_forward` | +0.13 | −0.13 | 0.70 |
| `knee_flexion_mean` | 158.0° | 159.3° | 0.09 |

Five features move in the direction coaching theory predicts. The faulty reps
stand upright with the platform above the waist and the weight on the heels; the
correct ones lean in, low, weight forward.

**These are diagnostics, not thresholds.** One athlete, one gym, one session.
With a single demonstrator there is no way to separate "this is what correct form
looks like" from "this is what this person looks like". Thresholds need many
athletes.

## Known issues

- `knee_flexion_mean` does not work (0.09). The knees separate well individually
  but in opposite directions — the correct rep bends the left knee, the faulty
  one the right. That is which-leg-forward, and both averaging and absolute
  difference (tried: 0.16) destroy it. The fix is mirror-normalising the skeleton
  so legs are ordered by stance rather than by side, which needs footage from
  both sides to validate.
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
