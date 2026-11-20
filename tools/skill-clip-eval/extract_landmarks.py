"""Write one landmark CSV per clip, for the app's Kotlin checks to replay.

    python extract_landmarks.py <out_dir> <clip.mp4> [...]

Same detector and confidences as the phone (pose_landmarker_full.task, one pose,
0.65). Every second frame is kept (15 fps from 30 fps sources), close to what the
live camera delivers to the analyzers.

Columns: t_ms, width, height, then 33 image landmarks (x, y, z, visibility,
presence) and 33 world landmarks in the same layout. A frame with no body is
omitted, exactly as PoseLandmarker emits nothing in the app.
"""
import csv
import os
import sys
from pathlib import Path

import cv2
import mediapipe as mp
from mediapipe.tasks import python as mpp
from mediapipe.tasks.python import vision

MODEL = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "assets" / "pose_landmarker_full.task"
STRIDE = 2


def landmarks(points):
    row = []
    for p in points:
        row += [f"{p.x:.5f}", f"{p.y:.5f}", f"{p.z:.5f}",
                f"{(p.visibility or 0):.4f}", f"{(p.presence or 0):.4f}"]
    return row


def extract(clip: Path, out_dir: Path) -> None:
    out = out_dir / (clip.stem + ".csv")
    if out.exists():
        print("skip", clip.name)
        return
    options = vision.PoseLandmarkerOptions(
        base_options=mpp.BaseOptions(model_asset_path=str(MODEL)),
        running_mode=vision.RunningMode.VIDEO, num_poses=1,
        min_pose_detection_confidence=0.65, min_pose_presence_confidence=0.65,
        min_tracking_confidence=0.65)
    detector = vision.PoseLandmarker.create_from_options(options)
    cap = cv2.VideoCapture(str(clip))
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    rows, index = [], 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if index % STRIDE == 0:
            t_ms = int(round(index * 1000.0 / fps))
            image = mp.Image(image_format=mp.ImageFormat.SRGB, data=cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
            result = detector.detect_for_video(image, t_ms)
            if result.pose_landmarks and result.pose_world_landmarks:
                rows.append([t_ms, frame.shape[1], frame.shape[0]]
                            + landmarks(result.pose_landmarks[0])
                            + landmarks(result.pose_world_landmarks[0]))
        index += 1
    detector.close()
    tmp = out.with_suffix(".tmp")
    with open(tmp, "w", newline="") as f:
        csv.writer(f).writerows(rows)
    os.replace(tmp, out)
    print(f"{clip.name}: {len(rows)} frames with a body")


if __name__ == "__main__":
    out_dir = Path(sys.argv[1])
    out_dir.mkdir(parents=True, exist_ok=True)
    for name in sys.argv[2:]:
        extract(Path(name), out_dir)
