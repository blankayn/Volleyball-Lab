"""Measures hue/saturation of candidate badge blobs, ignoring the position gate.

    python debug_hue.py samples/beginner_tips.mp4 60

`debug_badge.py` reports what the current thresholds decide. This reports what
the pixels actually are, with BADGE_REGION switched off, so a blob that the
position gate is silently discarding still shows up. Use it when badges are being
missed entirely rather than merely misread.
"""
from __future__ import annotations

import sys

import cv2
import numpy as np

import video as vid


def scan(frame: np.ndarray, name: str, mask: np.ndarray) -> None:
    hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
    height, width = mask.shape
    area_total = float(height * width)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    rows = []
    for contour in contours:
        area = cv2.contourArea(contour)
        if area / area_total < vid.BADGE_MIN_AREA_FRAC:
            continue
        x, y, w, h = cv2.boundingRect(contour)
        if not w or not h:
            continue
        blob = np.zeros(mask.shape, np.uint8)
        cv2.drawContours(blob, [contour], -1, 255, cv2.FILLED)
        inside = blob > 0
        rows.append((
            area / area_total, area / float(w * h), w / h,
            float(np.mean(hsv[:, :, 0][inside])), float(np.std(hsv[:, :, 0][inside])),
            float(np.mean(hsv[:, :, 1][inside])), float(np.mean(hsv[:, :, 2][inside])),
            (x + w / 2) / width, (y + h / 2) / height,
        ))

    print(f"\n{name}: {len(rows)} blob(s) above area floor (position gate OFF)")
    if rows:
        print(f"  {'area%':>7}{'extent':>8}{'aspect':>8}{'hue':>7}{'hueSD':>7}"
              f"{'sat':>7}{'val':>7}{'cx':>7}{'cy':>7}  passes?")
        for a, e, asp, hue, hsd, sat, val, cx, cy in sorted(rows, reverse=True):
            left, right, top, bottom = vid.BADGE_REGION
            checks = {
                "area": vid.BADGE_MIN_AREA_FRAC <= a <= vid.BADGE_MAX_AREA_FRAC,
                "extent": e >= vid.BADGE_MIN_EXTENT,
                "aspect": vid.BADGE_ASPECT[0] <= asp <= vid.BADGE_ASPECT[1],
                "sat": sat >= vid.BADGE_MIN_MEAN_SATURATION,
                "region": left <= cx <= right and top <= cy <= bottom,
            }
            failed = ",".join(k for k, ok in checks.items() if not ok) or "ALL PASS"
            print(f"  {a * 100:7.3f}{e:8.3f}{asp:8.2f}{hue:7.1f}{hsd:7.1f}"
                  f"{sat:7.1f}{val:7.1f}{cx:7.2f}{cy:7.2f}  {failed}")


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

    scan(frame, "GREEN", cv2.inRange(hsv, np.array(vid.GREEN_HSV[0]), np.array(vid.GREEN_HSV[1])))
    scan(frame, "RED", cv2.bitwise_or(
        cv2.inRange(hsv, np.array(vid.RED_HSV_LOW[0]), np.array(vid.RED_HSV_LOW[1])),
        cv2.inRange(hsv, np.array(vid.RED_HSV_HIGH[0]), np.array(vid.RED_HSV_HIGH[1])),
    ))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
