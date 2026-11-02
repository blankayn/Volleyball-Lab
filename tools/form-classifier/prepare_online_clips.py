"""Extract explicitly selected online demonstrations, retaining provisional labels.

This is weak supervision from creator tutorials reviewed by AI. It does not
create coach-verified truth and cannot establish real-world model accuracy.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
from pathlib import Path
import subprocess
import sys


def attach_source_hash(sequence_path: Path, source_sha256: str, task_scope: str | None = None) -> None:
    """Carry original-video identity through copied/renamed feature bundles."""
    import numpy as np
    if not re.fullmatch(r"[a-f0-9]{64}", source_sha256):
        raise ValueError("Expected a lowercase original-source SHA-256")
    if task_scope is not None and not re.fullmatch(r"[a-z][a-z0-9_]{0,79}", task_scope):
        raise ValueError("Expected a machine-readable task scope")
    with np.load(sequence_path, allow_pickle=True) as bundle:
        values = {key: bundle[key] for key in bundle.files}
    values["source_sha256"] = np.repeat(source_sha256, len(values["sequences"]))
    if task_scope is not None:
        values["task_scope"] = np.repeat(task_scope, len(values["sequences"]))
    np.savez_compressed(sequence_path, **values)

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/pose-pipeline"))


def validate(rows: list[dict], source_dir: Path) -> None:
    if not rows:
        raise ValueError("No selected demonstrations")
    seen = []
    for row in rows:
        if not re.fullmatch(r"[A-Za-z0-9_-]{11}", row.get("id", "")):
            raise ValueError("Source id must be a YouTube video identifier")
        if row.get("skill") not in {"serve", "pass", "set", "spike", "block"}:
            raise ValueError("Unknown skill")
        if row.get("label") not in {"ok", "fault"} or not row.get("evidence"):
            raise ValueError("Every selected clip needs a label and its source evidence")
        if row.get("task_scope") is not None and (not isinstance(row["task_scope"], str) or
                not re.fullmatch(r"[a-z][a-z0-9_]{0,79}", row["task_scope"])):
            raise ValueError("Invalid task scope")
        if not all(isinstance(row[k], (int, float)) and not isinstance(row[k], bool)
                   and math.isfinite(row[k]) for k in ("start", "end")):
            raise ValueError("Clip timestamps must be finite")
        if not 0 <= row["start"] < row["end"] or row["end"] - row["start"] > 20:
            raise ValueError("Select a single demonstration lasting at most 20 seconds")
        if not (source_dir / (row["id"] + ".mp4")).is_file():
            raise ValueError(f"Missing downloaded source {row['id']}")
        crop = row.get("crop")
        if crop is not None:
            if not isinstance(crop, list) or len(crop) != 4 or not all(
                isinstance(v, (float, int)) and not isinstance(v, bool) and math.isfinite(v) for v in crop
            ):
                raise ValueError("Crop must be finite normalized x, y, width, height")
            x, y, width, height = crop
            if x < 0 or y < 0 or width <= 0 or height <= 0 or x + width > 1 or y + height > 1:
                raise ValueError("Crop must stay inside the original frame")
        for prior in seen:
            if prior["id"] == row["id"] and max(prior["start"], row["start"]) < min(prior["end"], row["end"]):
                raise ValueError("Selected windows must not overlap")
        seen.append(row)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--selections", type=Path, required=True)
    parser.add_argument("--sources", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    rows = json.loads(args.selections.read_text(encoding="utf-8"))
    validate(rows, args.sources)
    import extract
    import video

    ffmpeg = video._find("ffmpeg")
    if not ffmpeg:
        raise SystemExit("FFmpeg is required to trim reviewed demonstrations")
    args.out.mkdir(parents=True, exist_ok=True)
    clips = args.out / "clips"
    clips.mkdir(exist_ok=True)
    provenance = {
        "label_origin": "creator-demonstration-with-AI-review",
        "coach_verified": False, "athlete_independence_verified": False,
        "deployment_approved": False,
        "selections_sha256": hashlib.sha256(args.selections.read_bytes()).hexdigest(),
        "clips": [],
    }
    hashes = {}
    failed = False
    for row in rows:
        source = args.sources / (row["id"] + ".mp4")
        if row["id"] not in hashes:
            with source.open("rb") as handle:
                hashes[row["id"]] = hashlib.file_digest(handle, "sha256").hexdigest()
        stem = f"{row['id']}_{round(row['start'] * 1000)}_{round(row['end'] * 1000)}"
        clip = clips / (stem + ".mp4")
        record = {**row, "source_url": "https://www.youtube.com/watch?v=" + row["id"],
                  "source_sha256": hashes[row["id"]], "source_group": row["id"], "clip": str(clip)}
        try:
            filters = ["fps=15"]
            if row.get("crop") is not None:
                x, y, width, height = row["crop"]
                filters.insert(0, f"crop=trunc(iw*{width}/2)*2:trunc(ih*{height}/2)*2:trunc(iw*{x}/2)*2:trunc(ih*{y}/2)*2")
            subprocess.run([ffmpeg, "-v", "error", "-y", "-threads", "2", "-ss", str(row["start"]),
                            "-i", str(source), "-t", str(row["end"] - row["start"]),
                            "-vf", ",".join(filters), "-an", "-c:v", "libx264", "-preset", "fast", "-crf", "20",
                            str(clip)], check=True, timeout=120)
            print(f"Extracting {row['skill']} {row['label']} {stem}", flush=True)
            record["extraction"] = extract.process(clip, extract.DEFAULT_MODEL, args.out, 32,
                False, False, row["skill"], row["label"], row["id"])
            attach_source_hash(args.out / (stem + "_sequences.npz"), hashes[row["id"]], row.get("task_scope"))
            record["status"] = "extracted"
        except Exception as error:
            failed = True
            record.update(status="error", reason=str(error))
            print(f"Failed {stem}: {error}", file=sys.stderr, flush=True)
        provenance["clips"].append(record)
        (args.out / "provenance.json").write_text(json.dumps(provenance, indent=2), encoding="utf-8")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
