"""
Volleyball Lab - clip trimming + labeling helper
=================================================

What this does
---------------
You watch downloaded source videos (YouTube technique tutorials, drills,
your own footage, etc.), note the start/end timestamp of each clean single
action (one serve, one spike, one dig...), and log those timestamps in
manifest.csv. This script then uses ffmpeg to auto-cut each entry into its
own short clip, sorted into folders by action label, and writes a
processed manifest with the resulting frame counts - so you can see at a
glance whether a clip is roughly the right length for your model's
30-frame action window before it ever goes near prepare_pose_dataset.py.

Requirements
------------
- ffmpeg + ffprobe installed and on PATH (https://ffmpeg.org/download.html)
- python3, no extra pip packages needed (uses only stdlib)

Folder layout expected
-----------------------
project/
  source_videos/          <- put your downloaded/raw videos here
  manifest.csv             <- your timestamp log (see manifest_template.csv)
  label_clips.py            <- this script
  trimmed_clips/            <- created automatically, one subfolder per label
      serve/
      spike/
      set/
      dig/
      block/
      ...
  manifest_processed.csv    <- written automatically after running

manifest.csv columns
---------------------
source_file   : filename inside source_videos/ (e.g. serve_tutorial_1.mp4)
start_time    : clip start, as seconds (12.5) or mm:ss.ms (00:12.500)
end_time      : clip end, same format
action_label  : the class name (serve, spike, set, dig, block, ... -
                use whatever label set your train_action_classifier.py
                expects, e.g. the VB14-style 7 classes or your own)
notes         : optional, free text (angle, source, quality flags, etc.)

Usage
-----
    python label_clips.py

Edit the ACTION_DURATION_RANGE below if your action windows are meant to
be longer/shorter than the volleyball-typical 0.5-3.0 second range.
"""

import csv
import os
import shutil
import subprocess
import sys

SOURCE_DIR = "source_videos"
MANIFEST_PATH = "manifest.csv"
OUTPUT_DIR = "trimmed_clips"
PROCESSED_MANIFEST_PATH = "manifest_processed.csv"

# A single volleyball action (windup through follow-through) is usually
# well inside this range. Clips outside it get flagged, not skipped -
# you decide whether to keep them.
ACTION_DURATION_RANGE = (0.4, 3.5)  # seconds


def check_ffmpeg():
    for tool in ("ffmpeg", "ffprobe"):
        if shutil.which(tool) is None:
            sys.exit(
                f"'{tool}' not found on PATH. Install ffmpeg first: "
                "https://ffmpeg.org/download.html"
            )


def parse_time(value):
    """Accepts '12.5' (seconds) or '00:12.500' / '01:02:03.5' (h:mm:ss.ms)."""
    value = value.strip()
    if ":" not in value:
        return float(value)
    parts = value.split(":")
    parts = [float(p) for p in parts]
    seconds = 0.0
    for p in parts:
        seconds = seconds * 60 + p
    return seconds


def probe_clip(path):
    """Returns (duration_seconds, fps, frame_count) via ffprobe, or None on failure."""
    try:
        out = subprocess.run(
            [
                "ffprobe", "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=r_frame_rate,nb_frames,duration",
                "-of", "csv=p=0",
                path,
            ],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
        fields = out.split(",")
        r_frame_rate = fields[0]
        nb_frames = fields[1] if len(fields) > 1 else ""
        duration = fields[2] if len(fields) > 2 else ""
        num, den = r_frame_rate.split("/")
        fps = float(num) / float(den) if float(den) != 0 else 0.0
        duration = float(duration) if duration not in ("", "N/A") else None
        frame_count = int(nb_frames) if nb_frames.isdigit() else None
        if frame_count is None and duration is not None:
            frame_count = round(duration * fps)
        return duration, fps, frame_count
    except Exception:
        return None, None, None


def main():
    check_ffmpeg()

    if not os.path.isfile(MANIFEST_PATH):
        sys.exit(
            f"'{MANIFEST_PATH}' not found. Copy manifest_template.csv to "
            f"'{MANIFEST_PATH}' and fill in your timestamps first."
        )

    os.makedirs(OUTPUT_DIR, exist_ok=True)

    rows_out = []
    counters = {}

    with open(MANIFEST_PATH, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        rows = list(reader)

    print(f"Found {len(rows)} clip(s) in {MANIFEST_PATH}\n")

    for i, row in enumerate(rows, start=1):
        source_file = row["source_file"].strip()
        action_label = row["action_label"].strip().lower().replace(" ", "_")
        src_path = os.path.join(SOURCE_DIR, source_file)

        if not os.path.isfile(src_path):
            print(f"[{i}] SKIP - source not found: {src_path}")
            continue

        try:
            start = parse_time(row["start_time"])
            end = parse_time(row["end_time"])
        except ValueError:
            print(f"[{i}] SKIP - bad start/end_time for {source_file}")
            continue

        if end <= start:
            print(f"[{i}] SKIP - end_time <= start_time for {source_file}")
            continue

        counters[action_label] = counters.get(action_label, 0) + 1
        clip_name = f"{action_label}_{counters[action_label]:03d}.mp4"
        out_dir = os.path.join(OUTPUT_DIR, action_label)
        os.makedirs(out_dir, exist_ok=True)
        out_path = os.path.join(out_dir, clip_name)

        duration = end - start
        cmd = [
            "ffmpeg", "-y",
            "-ss", str(start),
            "-i", src_path,
            "-t", str(duration),
            "-c:v", "libx264", "-c:a", "aac",
            "-loglevel", "error",
            out_path,
        ]
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode != 0:
            print(f"[{i}] FFMPEG ERROR on {source_file}: {result.stderr.strip()[:200]}")
            continue

        clip_duration, fps, frame_count = probe_clip(out_path)
        flag = ""
        if clip_duration is not None and not (
            ACTION_DURATION_RANGE[0] <= clip_duration <= ACTION_DURATION_RANGE[1]
        ):
            flag = f"OUTSIDE_EXPECTED_RANGE({ACTION_DURATION_RANGE[0]}-{ACTION_DURATION_RANGE[1]}s)"

        print(
            f"[{i}] OK -> {out_path}  "
            f"duration={clip_duration:.2f}s fps={fps:.1f} frames={frame_count}  {flag}"
        )

        rows_out.append({
            "clip_path": out_path,
            "action_label": action_label,
            "source_file": source_file,
            "start_time": row["start_time"],
            "end_time": row["end_time"],
            "duration_s": f"{clip_duration:.3f}" if clip_duration else "",
            "fps": f"{fps:.2f}" if fps else "",
            "frame_count": frame_count if frame_count else "",
            "flag": flag,
            "notes": row.get("notes", ""),
        })

    if rows_out:
        with open(PROCESSED_MANIFEST_PATH, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=list(rows_out[0].keys()))
            writer.writeheader()
            writer.writerows(rows_out)
        print(f"\nWrote {len(rows_out)} clip(s) to {PROCESSED_MANIFEST_PATH}")

        counts = {}
        for r in rows_out:
            counts[r["action_label"]] = counts.get(r["action_label"], 0) + 1
        print("\nPer-class counts:")
        for label, count in sorted(counts.items()):
            print(f"  {label}: {count}")
        flagged = [r for r in rows_out if r["flag"]]
        if flagged:
            print(f"\n{len(flagged)} clip(s) flagged as outside the expected duration range - review manifest_processed.csv")
    else:
        print("\nNo clips were produced. Check manifest.csv and source_videos/.")


if __name__ == "__main__":
    main()
