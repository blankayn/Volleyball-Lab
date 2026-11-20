"""One contact sheet per clip: a frame at each candidate movement's start, contact
and end, captioned with its number, time and the app's verdict, so a reviewer can
label it from what the video itself shows (its tick/cross marks and captions).

    python candidate_sheets.py <candidates.csv> <clips_dir> <out_dir>
"""
import csv
import sys
from collections import defaultdict
from pathlib import Path

import cv2
import numpy as np

rows = list(csv.DictReader(open(sys.argv[1])))
clips, out = Path(sys.argv[2]), Path(sys.argv[3])
out.mkdir(parents=True, exist_ok=True)
by_clip = defaultdict(list)
for r in rows:
    by_clip[r["clip"]].append(r)


def grab(cap, ms, h=230):
    cap.set(cv2.CAP_PROP_POS_MSEC, ms)
    ok, f = cap.read()
    if not ok:
        return np.zeros((h, int(h * 0.56), 3), np.uint8)
    return cv2.resize(f, (int(f.shape[1] * h / f.shape[0]), h))


for clip, reps in by_clip.items():
    cap = cv2.VideoCapture(str(clips / f"{clip}.mp4"))
    tiles = []
    for n, r in enumerate(reps, 1):
        s, c, e = int(r["start_ms"]), int(r["contact_ms"]), int(r["end_ms"])
        strip = np.hstack([grab(cap, s), grab(cap, (s + c) // 2), grab(cap, c), grab(cap, e)])
        bar = np.full((28, strip.shape[1], 3), 255, np.uint8)
        text = f"#{n} {s/1000:.1f}-{e/1000:.1f}s contact {c/1000:.1f}s  {r['view']}  {r['verdict']}  score {r['form_score'] or '-'}"
        cv2.putText(bar, text, (4, 20), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (0, 0, 0), 1)
        tiles.append(np.vstack([bar, strip]))
    w = max(t.shape[1] for t in tiles)
    tiles = [np.hstack([t, np.full((t.shape[0], w - t.shape[1], 3), 255, np.uint8)]) for t in tiles]
    # Two columns keep the sheets readable.
    if len(tiles) > 1:
        if len(tiles) % 2:
            tiles.append(np.full_like(tiles[0], 255))
        half = len(tiles) // 2
        sheet = np.hstack([np.vstack(tiles[:half]), np.vstack(tiles[half:])])
    else:
        sheet = tiles[0]
    cv2.imwrite(str(out / f"{clip}.jpg"), sheet)
    print(clip, len(reps))
