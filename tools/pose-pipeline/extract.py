"""Turns a folder of volleyball clips into labelled, camera-independent pose sequences.

    python extract.py <video-or-folder> [...] --out data/

For each clip this writes four things:

    <clip>_frames.csv      one row per frame: quality flags and the feature columns
    <clip>_landmarks.npz   the image- and world-space 33-point skeletons
    <clip>_segments.csv    one row per demonstration: label, boundaries, usability
    <clip>_sequences.npz   per-segment feature sequences resampled to a fixed length

plus a run-wide `manifest.json` with the totals worth reading before training
anything.

The model, and the confidences it runs at, are deliberately the same ones the
Android app uses - `pose_landmarker_full.task`, up to three poses, 0.5 across the board.
Measuring form offline with a different detector than the phone ships would make
every threshold derived here a threshold that does not transfer, and the failure
would show up late, on a device, as "the app disagrees with our own numbers".

The one difference is running mode: the app is LIVE_STREAM, this is VIDEO. Both
track across frames; VIDEO is the offline equivalent and is deterministic, which
LIVE_STREAM is not, since it drops frames under load.

What this does NOT do is find reps. A shot is an edit boundary, and in short-form
coaching clips one shot is usually one demonstration - close enough to be useful,
not the same thing. Rep segmentation needs the fault taxonomy to exist first, so
it is a later step and a human confirms it.
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

import cv2
import mediapipe as mp
import numpy as np
from mediapipe.tasks import python as mp_python
from mediapipe.tasks.python import vision

import features
import video as vid

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MODEL = REPO_ROOT / "app" / "src" / "main" / "assets" / "pose_landmarker_full.task"

VIDEO_SUFFIXES = {".mp4", ".mov", ".m4v", ".avi", ".mkv", ".webm"}

# Matches MediaPipePoseDetector.create() in the Android app. Changing one of
# these without changing the other is the bug this comment exists to prevent.
MIN_DETECTION_CONFIDENCE = 0.5
MIN_PRESENCE_CONFIDENCE = 0.5
MIN_TRACKING_CONFIDENCE = 0.5

# A landmark below this is a guess. Counting them per frame is what lets the
# capture screen say "step back, your feet are out of frame" instead of scoring
# a rep it could not actually see. Matches Landmark.DEFAULT_VISIBILITY_THRESHOLD.
VISIBILITY_FLOOR = 0.5

# A torso shorter than this in world metres means the skeleton has collapsed
# rather than that the athlete is small. Angles are scale-invariant so this does
# not affect them directly, but a degenerate torso means the whole fit is
# untrustworthy and the metric features derived from it are meaningless.
MIN_TORSO_METRES = 0.15

DEFAULT_SEQUENCE_LENGTH = 32

# Stamped into every npz. Version 1 measured features in image space; version 2
# measures them in the app's world space, and the two are not comparable - a
# consumer that silently mixed them would train on 23 degrees of noise. Bump this
# whenever FEATURE_NAMES changes meaning rather than merely growing.
SCHEMA_VERSION = 2


def build_landmarker(model_path: Path) -> vision.PoseLandmarker:
    options = vision.PoseLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=str(model_path)),
        running_mode=vision.RunningMode.VIDEO,
        num_poses=3,
        min_pose_detection_confidence=MIN_DETECTION_CONFIDENCE,
        min_pose_presence_confidence=MIN_PRESENCE_CONFIDENCE,
        min_tracking_confidence=MIN_TRACKING_CONFIDENCE,
        output_segmentation_masks=False,
    )
    return vision.PoseLandmarker.create_from_options(options)


def collect_videos(inputs: list[str]) -> list[Path]:
    found: list[Path] = []
    for raw in inputs:
        path = Path(raw)
        if path.is_dir():
            found.extend(sorted(p for p in path.rglob("*") if p.suffix.lower() in VIDEO_SUFFIXES))
        elif path.suffix.lower() in VIDEO_SUFFIXES:
            found.append(path)
        else:
            print(f"  skipping {path} (not a video)", file=sys.stderr)
    return found


class PrimaryPoseTracker:
    """Mirror the Android player's central-first, continuity-after selection."""

    def __init__(self):
        self.centre: np.ndarray | None = None
        self.missing = 0

    def select(self, poses) -> int | None:
        candidates = []
        for index, pose in enumerate(poses):
            if len(pose) != 33:
                continue
            torso = [pose[i] for i in (11, 12, 23, 24)]
            if any(not np.isfinite((p.x, p.y)).all() or
                   p.visibility < 0.5 or p.presence < 0.5 for p in torso):
                continue
            centre = np.array([np.mean([p.x for p in torso]),
                               np.mean([p.y for p in torso])])
            visible = [pose[i].y for i in (11, 12, 13, 14, 15, 16, 23, 24,
                                           25, 26, 27, 28)
                       if pose[i].visibility >= 0.5 and np.isfinite(pose[i].y)]
            height = max(visible) - min(visible) if len(visible) >= 8 else 0.0
            candidates.append((index, centre, height))
        if self.centre is None:
            chosen = min(candidates,
                         key=lambda c: np.linalg.norm(c[1] - 0.5) - c[2] * 0.25,
                         default=None)
        else:
            chosen = min(candidates,
                         key=lambda c: np.linalg.norm(c[1] - self.centre),
                         default=None)
            if chosen is not None and np.linalg.norm(chosen[1] - self.centre) > 0.20:
                chosen = None
        if chosen is None:
            self.missing += 1
            if self.missing >= 15:
                self.centre = None
                self.missing = 0
            return None
        self.missing = 0
        self.centre = chosen[1]
        return chosen[0]


def process(path: Path, model_path: Path, out_dir: Path, sequence_length: int,
            read_badges: bool, overlay: bool, skill: str,
            clip_label: str | None = None,
            source_group: str | None = None) -> dict:
    capture, transcoded = vid.open_video(path)

    # One landmarker per clip, not one per run. In VIDEO mode MediaPipe keeps a
    # single monotonic timestamp cursor, so a second clip starting again at t=0
    # is rejected outright. Rebuilding also drops the tracking state, which is
    # what we want across unrelated clips - carrying a pose hypothesis from one
    # athlete into another's footage is a bug that would be very hard to see.
    landmarker = build_landmarker(model_path)
    primary_tracker = PrimaryPoseTracker()

    fps = capture.get(cv2.CAP_PROP_FPS) or 30.0
    width = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT))

    signatures: list[np.ndarray] = []
    badges: list[str | None] = []
    image_all: list[np.ndarray] = []
    world_all: list[np.ndarray] = []
    visibility_all: list[np.ndarray] = []
    angles_all: list[np.ndarray] = []
    valid_all: list[bool] = []
    torso_all: list[float] = []
    detected_all: list[bool] = []

    writer = None
    if overlay:
        out_dir.mkdir(parents=True, exist_ok=True)
        writer = cv2.VideoWriter(
            str(out_dir / f"{path.stem}_overlay.mp4"),
            cv2.VideoWriter_fourcc(*"mp4v"), fps, (width, height),
        )

    index = 0
    while True:
        ok, frame = capture.read()
        if not ok:
            break

        signatures.append(vid.frame_signature(frame))
        # Pre-cut coaching demonstrations can carry a reviewer-supplied label.
        # This avoids inventing a visual badge and keeps the label provenance at
        # the command line.  Badge reading remains the default for mixed clips.
        badges.append(clip_label if clip_label else
                      (vid.read_badge(frame) if read_badges else None))

        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
        # Timestamps must increase strictly; deriving them from the frame index
        # rather than the container keeps that true even on clips with a broken
        # or fragmented time base, which YouTube downloads frequently have.
        result = landmarker.detect_for_video(mp_image, int(index * 1000 / fps))

        # Both landmark lists are kept, and they are not interchangeable. World is
        # metric, hip-centred and has real depth: the only correct input for
        # angles, and what every analyzer in the app consumes. Image is a
        # perspective projection: correct for drawing, and the only space that
        # records a jump, since world puts the hips at the origin in every frame.
        selected = primary_tracker.select(result.pose_landmarks)
        if selected is not None and selected < len(result.pose_world_landmarks):
            marks = result.pose_landmarks[selected]
            image = np.array([[m.x, m.y] for m in marks], dtype=np.float64)
            world = np.array([[m.x, m.y, m.z] for m in result.pose_world_landmarks[selected]],
                             dtype=np.float64)
            visibility = np.array([m.visibility for m in marks], dtype=np.float64)
            torso = float(np.linalg.norm(
                features.shoulder_centre(world) - features.hip_centre(world)))
            valid = np.isfinite(world).all() and torso >= MIN_TORSO_METRES
            detected_all.append(True)
        else:
            image = np.full((features.NUM_LANDMARKS, 2), np.nan)
            world = np.full((features.NUM_LANDMARKS, 3), np.nan)
            visibility = np.zeros(features.NUM_LANDMARKS)
            torso, valid = float("nan"), False
            detected_all.append(False)

        image_all.append(image)
        world_all.append(world)
        visibility_all.append(visibility)
        # Computed unconditionally. `valid` is reported, not enforced: the Kotlin
        # has no all-or-nothing gate, and a distant athlete still has a
        # measurable elbow. Whether the capture was good enough is
        # PoseQualityValidator's question, asked about capture rather than about
        # measurement, and answering it here would throw away real data.
        angles_all.append(features.compute(world, image))
        valid_all.append(bool(valid))
        torso_all.append(torso)

        if writer is not None:
            writer.write(draw(frame, image, visibility, badges[-1], width, height))

        index += 1

    capture.release()
    landmarker.close()
    if writer is not None:
        writer.release()
    if transcoded is not None:
        transcoded.unlink(missing_ok=True)

    if index == 0:
        raise RuntimeError(f"{path} decoded zero frames")

    shots, segments = vid.find_segments(
        signatures, badges if (read_badges or clip_label) else [None] * index)
    angles_array = np.array(angles_all)
    visibility_array = np.array(visibility_all)
    low_vis = (visibility_array < VISIBILITY_FLOOR).sum(axis=1)
    mean_vis = visibility_array.mean(axis=1)

    shot_of_frame = np.zeros(index, dtype=int)
    for shot in shots:
        shot_of_frame[shot.start:shot.end] = shot.index

    segment_rows = []
    sequences, sequence_labels, sequence_ids = [], [], []
    for segment in segments:
        window = slice(segment.start, segment.end)
        usable = float(np.mean([
            valid_all[i] and low_vis[i] == 0 for i in range(segment.start, segment.end)
        ]))

        # The jump baseline is recoverable only from the segment as a whole, so
        # it is filled here rather than per frame in `features.compute`. Filling
        # before resampling uses every real frame's hip height for the baseline;
        # filling after would use only the 32 interpolated samples.
        raw = features.fill_hip_rise(angles_array[window])
        resampled = features.resample(raw, sequence_length)

        # Contact is located on the resampled sequence, because that is the
        # timeline the model and the phase-mapped cues both speak in. The
        # confidence is measured on the *raw* frames around it - resampling
        # interpolates a blurred frame into its neighbours, which would launder
        # exactly the blur this is trying to report.
        contact = features.contact_frame_index(resampled, skill)
        contact_raw = segment.start + round(contact * (segment.length - 1)
                                           / max(1, sequence_length - 1))
        half = features.CONTACT_WINDOW
        lo, hi = max(segment.start, contact_raw - half), min(segment.end, contact_raw + half + 1)
        contact_window = slice(lo, hi)

        segment_rows.append({
            "segment": segment.index,
            "skill": skill,
            "shot": segment.shot,
            "start_frame": segment.start,
            "end_frame": segment.end,
            "start_s": round(segment.start / fps, 3),
            "duration_s": round(segment.length / fps, 3),
            "label": segment.label,
            "usable_frac": round(usable, 3),
            "mean_visibility": round(float(np.mean(mean_vis[window])), 3),
            # Where in the rep the athlete contacted, and how sure the detector
            # was there. Contact is the frame landmark confidence is worst at -
            # arms toward the lens, motion blur - so a segment can be perfectly
            # usable overall and still have nothing trustworthy at the one
            # instant the contact-phase cues are scored from. Carrying it here
            # keeps that from surfacing for the first time during the defence.
            "contact_frame": contact_raw,
            "contact_s": round(contact_raw / fps, 3),
            "contact_mean_visibility": round(
                float(np.mean(mean_vis[contact_window])), 3),
            "contact_low_visibility_count": int(low_vis[contact_window].sum()),
            "contact_valid": int(valid_all[contact_raw]),
            "too_short": int(segment.length < vid.MIN_SEGMENT_FRAMES),
        })

        sequences.append(resampled)
        sequence_labels.append(segment.label)
        sequence_ids.append(segment.index)

    out_dir.mkdir(parents=True, exist_ok=True)
    stem = path.stem

    segment_of_frame = np.zeros(index, dtype=int)
    label_of_frame = ["unlabelled"] * index
    for segment in segments:
        segment_of_frame[segment.start:segment.end] = segment.index
        label_of_frame[segment.start:segment.end] = [segment.label] * segment.length

    with (out_dir / f"{stem}_frames.csv").open("w", newline="", encoding="utf-8") as handle:
        columns = ["frame", "time_s", "shot", "segment", "label", "detected", "valid",
                   "torso_metres", "mean_visibility", "low_visibility_count",
                   *features.FEATURE_NAMES]
        out = csv.writer(handle)
        out.writerow(columns)
        for i in range(index):
            out.writerow([
                i, round(i / fps, 4), int(shot_of_frame[i]), int(segment_of_frame[i]),
                label_of_frame[i], int(detected_all[i]),
                int(valid_all[i]), round(torso_all[i], 4) if np.isfinite(torso_all[i]) else "",
                round(float(mean_vis[i]), 4), int(low_vis[i]),
                *[("" if not np.isfinite(v) else round(float(v), 4)) for v in angles_all[i]],
            ])

    with (out_dir / f"{stem}_segments.csv").open("w", newline="", encoding="utf-8") as handle:
        out = csv.DictWriter(handle, fieldnames=list(segment_rows[0].keys()))
        out.writeheader()
        out.writerows(segment_rows)

    np.savez_compressed(
        out_dir / f"{stem}_landmarks.npz",
        schema_version=SCHEMA_VERSION,
        image=np.array(image_all), world=np.array(world_all),
        visibility=visibility_array, valid=np.array(valid_all),
        torso_metres=np.array(torso_all),
        shot_of_frame=shot_of_frame, segment_of_frame=segment_of_frame,
        fps=fps, width=width, height=height,
    )
    # `too_short` travels with the tensor rather than only in the CSV: a segment
    # of one frame resamples to 32 identical rows, which looks like perfectly
    # valid training data and is not. Anything reading this file should filter on
    # it, and it cannot do that if the flag lives in a different file.
    np.savez_compressed(
        out_dir / f"{stem}_sequences.npz",
        schema_version=SCHEMA_VERSION,
        sequences=np.array(sequences), labels=np.array(sequence_labels),
        segments=np.array(sequence_ids), feature_names=np.array(features.FEATURE_NAMES),
        too_short=np.array([r["too_short"] for r in segment_rows], dtype=bool),
        skill=np.array([skill] * len(segment_rows)),
        # Several short, reviewer-labelled clips may come from one original
        # coaching video.  Preserve that identity so the trainer cannot leak the
        # same athletes and gym into both train and holdout sets.
        source_group=np.array([source_group or stem] * len(segment_rows)),
        # Coaching clips cut between full-body demos and arms-only detail shots.
        # The close-ups carry a perfectly good badge and no usable skeleton, so
        # this has to reach the trainer or it will learn from frames in which the
        # body it is judging was never visible.
        usable_frac=np.array([r["usable_frac"] for r in segment_rows], dtype=float),
    )

    def count(label: str) -> int:
        return sum(1 for r in segment_rows
                   if r["label"] == label and not r["too_short"])

    return {
        "clip": path.name,
        "frames": index,
        "fps": round(fps, 2),
        "resolution": f"{width}x{height}",
        "duration_s": round(index / fps, 2),
        "shots": len(shots),
        "segments": len(segment_rows),
        "segments_ok": count("ok"),
        "segments_fault": count("fault"),
        "segments_unlabelled": count("unlabelled"),
        "segments_too_short": sum(r["too_short"] for r in segment_rows),
        "detection_rate": round(float(np.mean(detected_all)), 3),
        "clean_frame_rate": round(float(np.mean((low_vis == 0) & np.array(valid_all))), 3),
    }


EDGES = ((11, 12), (11, 13), (13, 15), (12, 14), (14, 16), (11, 23), (12, 24),
         (23, 24), (23, 25), (25, 27), (24, 26), (26, 28), (27, 31), (28, 32))


def draw(frame: np.ndarray, image: np.ndarray, visibility: np.ndarray,
         badge: str | None, width: int, height: int) -> np.ndarray:
    """Annotated copy for eyeballing a batch. Dim joints are the low-confidence ones.

    Drawing is the one job image-space landmarks are right for - they map onto
    the frame directly, which world coordinates have no relationship to.
    """
    out = frame.copy()
    if np.isfinite(image).all():
        pixels = image * np.array([width, height])
        for a, b in EDGES:
            cv2.line(out, tuple(pixels[a].astype(int)), tuple(pixels[b].astype(int)), (0, 255, 136), 2)
        for i, point in enumerate(pixels):
            strong = visibility[i] >= VISIBILITY_FLOOR
            cv2.circle(out, tuple(point.astype(int)), 4,
                       (0, 255, 136) if strong else (60, 60, 200), -1)
    if badge:
        cv2.putText(out, badge.upper(), (16, 44), cv2.FONT_HERSHEY_SIMPLEX, 1.2,
                    (0, 200, 0) if badge == "ok" else (0, 0, 220), 3)
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("inputs", nargs="+", help="video files or folders to process")
    parser.add_argument("--out", default="data", help="output directory (default: data)")
    parser.add_argument("--model", default=str(DEFAULT_MODEL),
                        help="pose .task bundle; defaults to the app's own asset")
    parser.add_argument("--resample", type=int, default=DEFAULT_SEQUENCE_LENGTH,
                        help=f"frames per shot sequence (default: {DEFAULT_SEQUENCE_LENGTH})")
    parser.add_argument("--no-badges", action="store_true",
                        help="skip tick/cross reading for footage that carries no marks")
    # A tick means "correct serve" or "correct pass", never "correct" on its own,
    # and the two look nothing alike. Pooling skills into one classifier measurably
    # weakens every feature - it is why this flag exists rather than being assumed.
    parser.add_argument("--skill", default="unknown",
                        help="skill these clips demonstrate (serve, pass, set, spike, block). "
                             "Clips covering several need splitting first")
    parser.add_argument("--label", choices=("ok", "fault"), default=None,
                        help="reviewer label for every frame in pre-cut single-form clips; "
                             "disables tick/cross badge reading")
    parser.add_argument("--source-group", default=None,
                        help="original video/session id for split clips; keeps all clips from "
                             "that source in the same train or holdout partition")
    parser.add_argument("--overlay", action="store_true",
                        help="also write an annotated mp4 per clip, for visual review")
    args = parser.parse_args()

    model_path = Path(args.model)
    if not model_path.exists():
        print(f"Pose model not found: {model_path}\n"
              f"Fetch it per app/src/main/assets/README.md, or pass --model.", file=sys.stderr)
        return 1

    videos = collect_videos(args.inputs)
    if not videos:
        print("No videos found.", file=sys.stderr)
        return 1

    out_dir = Path(args.out)
    summaries = []

    for path in videos:
        print(f"[{len(summaries) + 1}/{len(videos)}] {path.name}", flush=True)
        try:
            summary = process(path, model_path, out_dir, args.resample,
                              not (args.no_badges or args.label), args.overlay,
                              args.skill, args.label, args.source_group)
        except Exception as error:  # one bad clip must not lose the batch
            print(f"  FAILED: {error}", file=sys.stderr)
            summaries.append({"clip": path.name, "error": str(error)})
            continue
        summaries.append(summary)
        print(f"  {summary['frames']} frames, {summary['shots']} shots, "
              f"{summary['segments']} segments "
              f"({summary['segments_ok']} ok / {summary['segments_fault']} fault), "
              f"{summary['clean_frame_rate']:.0%} clean")

    ok = [s for s in summaries if "error" not in s]
    manifest = {
        "clips": summaries,
        "totals": {
            "clips": len(ok),
            "failed": len(summaries) - len(ok),
            "shots": sum(s["shots"] for s in ok),
            "segments": sum(s["segments"] for s in ok),
            "segments_ok": sum(s["segments_ok"] for s in ok),
            "segments_fault": sum(s["segments_fault"] for s in ok),
            "segments_unlabelled": sum(s["segments_unlabelled"] for s in ok),
            "segments_too_short": sum(s["segments_too_short"] for s in ok),
        },
    }
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    totals = manifest["totals"]
    print(f"\n{totals['clips']} clips -> {totals['segments']} segments "
          f"({totals['segments_ok']} ok, {totals['segments_fault']} fault, "
          f"{totals['segments_unlabelled']} unlabelled, "
          f"{totals['segments_too_short']} too short) in {out_dir}/")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
