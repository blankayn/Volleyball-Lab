"""Measures how far the current 2D image-space angles sit from 3D world-space ones.

    python compare_spaces.py samples/passing_easy2.mp4

The Android analyzers consume MediaPipe's world landmarks - metric, hip-centred,
real depth - because a perspective projection distorts a joint angle by an amount
that depends on how the athlete happens to be standing relative to the lens. The
Python pipeline was built on image-space landmarks instead.

Both produce plausible-looking degrees, which is exactly the problem. This script
puts a number on the disagreement so the size of the defect is a measurement
rather than an argument.
"""
from __future__ import annotations

import math
import sys

import cv2
import mediapipe as mp
import numpy as np
from mediapipe.tasks import python as mp_python
from mediapipe.tasks.python import vision

import features

MODEL = "../../app/src/main/assets/pose_landmarker_full.task"

JOINTS = {
    "elbow_l": (features.L_SHOULDER, features.L_ELBOW, features.L_WRIST),
    "elbow_r": (features.R_SHOULDER, features.R_ELBOW, features.R_WRIST),
    "knee_l": (features.L_HIP, features.L_KNEE, features.L_ANKLE),
    "knee_r": (features.R_HIP, features.R_KNEE, features.R_ANKLE),
    "shoulder_l": (features.L_ELBOW, features.L_SHOULDER, features.L_HIP),
    "hip_l": (features.L_SHOULDER, features.L_HIP, features.L_KNEE),
}


# Local copies of the old image-space maths. They live here rather than in
# `features.py` on purpose: that module now contains only measurements the app can
# also make, and re-exporting a projected angle would invite someone to feed one
# to a cue. This script is the one legitimate consumer, because measuring the gap
# is the whole reason it exists.
def _to_pixels(landmarks: np.ndarray, width: int, height: int) -> np.ndarray:
    """MediaPipe normalises x by width and y by height independently, so raw
    values are anisotropic; this restores square pixels before measuring."""
    out = np.empty((len(landmarks), 2), dtype=np.float64)
    out[:, 0] = landmarks[:, 0] * width
    out[:, 1] = landmarks[:, 1] * height
    return out


def _angle_2d(a: np.ndarray, b: np.ndarray, c: np.ndarray) -> float:
    ba, bc = a - b, c - b
    na, nc = np.linalg.norm(ba), np.linalg.norm(bc)
    if na < 1e-9 or nc < 1e-9:
        return float("nan")
    return math.degrees(math.acos(max(-1.0, min(1.0, float(np.dot(ba, bc) / (na * nc))))))


def angle3d(a: np.ndarray, b: np.ndarray, c: np.ndarray) -> float:
    ba, bc = a - b, c - b
    na, nc = np.linalg.norm(ba), np.linalg.norm(bc)
    if na < 1e-9 or nc < 1e-9:
        return float("nan")
    return math.degrees(math.acos(max(-1.0, min(1.0, float(np.dot(ba, bc) / (na * nc))))))


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else "samples/passing_easy2.mp4"
    step = int(sys.argv[2]) if len(sys.argv) > 2 else 15

    landmarker = vision.PoseLandmarker.create_from_options(vision.PoseLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=MODEL),
        running_mode=vision.RunningMode.VIDEO, num_poses=1,
        min_pose_detection_confidence=0.5, min_pose_presence_confidence=0.5,
        min_tracking_confidence=0.5,
    ))

    capture, _ = __import__("video").open_video(__import__("pathlib").Path(path))
    fps = capture.get(cv2.CAP_PROP_FPS) or 30.0
    width = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT))

    gaps = {name: [] for name in JOINTS}
    index = 0
    while True:
        ok, frame = capture.read()
        if not ok:
            break
        if index % step == 0:
            image = mp.Image(image_format=mp.ImageFormat.SRGB,
                             data=cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
            result = landmarker.detect_for_video(image, int(index * 1000 / fps))
            if result.pose_landmarks and result.pose_world_landmarks:
                marks = result.pose_landmarks[0]
                if min(m.visibility for m in marks) >= 0.5:
                    px = _to_pixels(
                        np.array([[m.x, m.y] for m in marks]), width, height)
                    world = np.array([[m.x, m.y, m.z] for m in result.pose_world_landmarks[0]])
                    for name, (a, b, c) in JOINTS.items():
                        flat = _angle_2d(px[a], px[b], px[c])
                        solid = angle3d(world[a], world[b], world[c])
                        if np.isfinite(flat) and np.isfinite(solid):
                            gaps[name].append(abs(flat - solid))
        index += 1
    capture.release()
    landmarker.close()

    print(f"{path}  -  {index} frames, sampled every {step}\n")
    print(f"{'joint':<12}{'n':>5}{'mean |gap|':>12}{'median':>9}{'p90':>8}{'max':>8}")
    for name, values in gaps.items():
        if not values:
            print(f"{name:<12}{0:>5}   (no clean frames)")
            continue
        v = np.array(values)
        print(f"{name:<12}{len(v):>5}{v.mean():>12.1f}{np.median(v):>9.1f}"
              f"{np.percentile(v, 90):>8.1f}{v.max():>8.1f}")

    everything = np.concatenate([np.array(v) for v in gaps.values() if v])
    print(f"\noverall mean disagreement: {everything.mean():.1f} degrees")
    print(f"fraction of measurements off by more than 10 degrees: "
          f"{(everything > 10).mean():.1%}")
    print(f"fraction off by more than 25 degrees: {(everything > 25).mean():.1%}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
