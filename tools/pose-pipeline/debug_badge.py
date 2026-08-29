"""Dumps every green/red blob in one frame with the numbers read_badge filters on.

    python debug_badge.py samples/serve_receive.mp4 397

Use this when a clip's badges are being missed or invented. It prints the same
measurements `video._has_badge` tests, so the fix is a threshold you can read off
the table rather than one you guess at.
"""
from __future__ import annotations

import sys

import cv2
import numpy as np

import video as vid


def dump(frame: np.ndarray, name: str, mask: np.ndarray) -> None:
    hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
    area_total = float(frame.shape[0] * frame.shape[1])
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    rows = []
    for contour in contours:
        area = cv2.contourArea(contour)
        if area / area_total < 0.0005:
            continue
        x, y, w, h = cv2.boundingRect(contour)
        blob = np.zeros(mask.shape, np.uint8)
        cv2.drawContours(blob, [contour], -1, 255, cv2.FILLED)
        inside = blob > 0
        rows.append((
            area / area_total,
            area / float(w * h) if w and h else 0.0,
            w / h if h else 0.0,
            float(np.mean(hsv[:, :, 1][inside])),
            float(np.std(hsv[:, :, 2][inside])),
            f"{x},{y} {w}x{h}",
        ))

    print(f"\n{name}: {len(rows)} candidate blob(s)")
    if rows:
        print(f"  {'area%':>7} {'extent':>7} {'aspect':>7} {'meanS':>7} {'stdV':>7}  box")
        for area_frac, extent, aspect, sat, std_v, box in sorted(rows, reverse=True):
            print(f"  {area_frac * 100:7.3f} {extent:7.3f} {aspect:7.3f} {sat:7.1f} {std_v:7.1f}  {box}")


def main() -> int:
    path, index = sys.argv[1], int(sys.argv[2])
    capture = cv2.VideoCapture(path)
    capture.set(cv2.CAP_PROP_POS_FRAMES, index)
    ok, frame = capture.read()
    capture.release()
    if not ok:
        print(f"could not read frame {index}", file=sys.stderr)
        return 1

    hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
    print(f"frame {index} of {path}  ({frame.shape[1]}x{frame.shape[0]})")
    print(f"read_badge says: {vid.read_badge(frame)}")

    dump(frame, "GREEN", cv2.inRange(hsv, np.array(vid.GREEN_HSV[0]), np.array(vid.GREEN_HSV[1])))
    dump(frame, "RED", cv2.bitwise_or(
        cv2.inRange(hsv, np.array(vid.RED_HSV_LOW[0]), np.array(vid.RED_HSV_LOW[1])),
        cv2.inRange(hsv, np.array(vid.RED_HSV_HIGH[0]), np.array(vid.RED_HSV_HIGH[1])),
    ))

    print(f"\nthresholds: area>={vid.BADGE_MIN_AREA_FRAC * 100:.3f}%  "
          f"extent>={vid.BADGE_MIN_EXTENT}  aspect {vid.BADGE_ASPECT}  "
          f"stdV<={vid.BADGE_MAX_VALUE_STD}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
