"""Evaluate the app's 3D pose model against calibrated 3D joint annotations.

Ground truth CSV columns: clip,skill,frame,joint,x_mm,y_mm,z_mm.  Coordinates
must use the same hip-centred axes as MediaPipe's world landmarks. A 2D video
annotation cannot establish millimetre depth or scale and is rejected rather
than being reported as a misleading 3D MPJPE.
"""
from __future__ import annotations

import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path

import numpy as np

JOINTS = (
    "LEFT_SHOULDER", "RIGHT_SHOULDER", "LEFT_ELBOW", "RIGHT_ELBOW",
    "LEFT_WRIST", "RIGHT_WRIST", "LEFT_HIP", "RIGHT_HIP", "LEFT_KNEE",
    "RIGHT_KNEE", "LEFT_ANKLE", "RIGHT_ANKLE",
)
JOINT_INDEX = dict(zip(JOINTS, (11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)))
REQUIRED = {"clip", "skill", "frame", "joint", "x_mm", "y_mm", "z_mm"}
SKILLS = ("serve", "pass", "set", "spike", "block")
SKILL_ALIASES = {
    "serve": "serve", "serving": "serve",
    "pass": "pass", "passing": "pass", "receive": "pass",
    "receiving": "pass", "forearm passing": "pass",
    "set": "set", "setting": "set",
    "spike": "spike", "spiking": "spike",
    "block": "block", "blocking": "block",
}


def read_annotations(path: Path) -> dict[tuple[str, int, str], tuple[str, np.ndarray]]:
    rows = {}
    with path.open(newline="", encoding="utf-8-sig") as handle:
        reader = csv.DictReader(handle)
        missing = REQUIRED - set(reader.fieldnames or ())
        if missing:
            raise ValueError(f"Ground truth needs calibrated 3D columns: {', '.join(sorted(missing))}")
        for line, row in enumerate(reader, start=2):
            clip = Path(row["clip"].strip()).stem
            skill = SKILL_ALIASES.get(row["skill"].strip().lower())
            joint = row["joint"].strip().upper()
            if not clip or not skill or joint not in JOINT_INDEX:
                raise ValueError(f"Line {line}: invalid clip, skill, or joint")
            try:
                frame = int(row["frame"])
                xyz = np.array([float(row[k]) for k in ("x_mm", "y_mm", "z_mm")])
            except (ValueError, TypeError) as exc:
                raise ValueError(f"Line {line}: invalid frame or 3D coordinates") from exc
            if frame < 0 or not np.isfinite(xyz).all():
                raise ValueError(f"Line {line}: frame and coordinates must be finite and nonnegative where applicable")
            key = (clip, frame, joint)
            if key in rows:
                raise ValueError(f"Line {line}: duplicate annotation for {key}")
            rows[key] = (skill, xyz)
    if not rows:
        raise ValueError("Ground truth CSV has no annotated joints")
    return rows


def summary(errors: list[float]) -> dict:
    return {
        "joints": len(errors),
        "mpjpe_mm": round(float(np.mean(errors)), 3) if errors else None,
        "median_mm": round(float(np.median(errors)), 3) if errors else None,
    }


def evaluate(predictions_dir: Path, annotations: dict, min_visibility: float = 0.5,
             target_mm: float = 50.0) -> dict:
    if not 0 <= min_visibility <= 1:
        raise ValueError("Visibility threshold must be between 0 and 1")
    if target_mm <= 0:
        raise ValueError("Target MPJPE must be positive")
    errors = []
    by_skill = defaultdict(list)
    by_clip = defaultdict(list)
    by_joint = defaultdict(list)
    missing_clips = set()
    skipped = defaultdict(int)
    used_frames = set()
    files = {}
    for clip, _, _ in annotations:
        path = predictions_dir / f"{clip}_landmarks.npz"
        if not path.is_file():
            missing_clips.add(clip)
        else:
            files[clip] = path
    if missing_clips:
        raise ValueError("Missing extracted landmark files: " + ", ".join(sorted(missing_clips)))

    loaded = {}
    for clip, path in files.items():
        with np.load(path, allow_pickle=False) as data:
            if int(data["schema_version"]) < 2:
                raise ValueError(f"{path.name}: image-space schema cannot be used for 3D MPJPE")
            world = np.asarray(data["world"], dtype=float)
            visibility = np.asarray(data["visibility"], dtype=float)
            if world.ndim != 3 or world.shape[1:] != (33, 3) or visibility.shape != world.shape[:2]:
                raise ValueError(f"{path.name}: expected world (frames,33,3) and visibility (frames,33)")
            loaded[clip] = world, visibility

    for (clip, frame, joint), (skill, truth_mm) in annotations.items():
        world, visibility = loaded[clip]
        if frame >= len(world):
            raise ValueError(f"{clip}: annotation frame {frame} exceeds {len(world)} extracted frames")
        index = JOINT_INDEX[joint]
        prediction_m = world[frame, index]
        if (not np.isfinite(visibility[frame, index]) or
                visibility[frame, index] < min_visibility or
                not np.isfinite(prediction_m).all()):
            skipped["low_visibility_or_missing_pose"] += 1
            continue
        error_mm = float(np.linalg.norm(prediction_m * 1000 - truth_mm))
        errors.append(error_mm)
        by_skill[skill].append(error_mm)
        by_clip[clip].append(error_mm)
        by_joint[joint].append(error_mm)
        used_frames.add((clip, frame))
    overall = summary(errors)
    skill_summaries = {key: summary(by_skill[key]) for key in SKILLS}
    missing_skills = [key for key in SKILLS if not by_skill[key]]
    return {
        "metric": "3D MPJPE, raw hip-centred coordinates, millimetres",
        "target_mm": target_mm,
        "overall_target_met": float(np.mean(errors)) < target_mm if errors else None,
        "five_skill_target_met": (all(float(np.mean(by_skill[s])) < target_mm for s in SKILLS)
                                  if not missing_skills else None),
        "skills_without_valid_annotations": missing_skills,
        "annotated_joints": len(annotations),
        "evaluated_frames": len(used_frames),
        "coverage": round(len(errors) / len(annotations), 4),
        "skipped": dict(skipped),
        "overall": overall,
        "by_skill": skill_summaries,
        "by_clip": {key: summary(value) for key, value in sorted(by_clip.items())},
        "by_joint": {key: summary(value) for key, value in sorted(by_joint.items())},
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("predictions", type=Path, help="directory containing extract.py *_landmarks.npz files")
    parser.add_argument("ground_truth", type=Path, help="CSV of calibrated, hip-centred 3D joint coordinates")
    parser.add_argument("--out", type=Path, default=Path("mpjpe_report.json"))
    parser.add_argument("--min-visibility", type=float, default=0.5)
    parser.add_argument("--target-mm", type=float, default=50.0)
    args = parser.parse_args()
    try:
        report = evaluate(args.predictions, read_annotations(args.ground_truth),
                          args.min_visibility, args.target_mm)
    except (ValueError, KeyError, OSError) as exc:
        parser.error(str(exc))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"overall": report["overall"], "coverage": report["coverage"],
                      "by_skill": report["by_skill"],
                      "five_skill_target_met": report["five_skill_target_met"]},
                     indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
