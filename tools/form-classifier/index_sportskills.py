"""Index SportSkills shot annotations as review leads, never training truth.

The release's parsed_result/vlm_result fields are machine-generated judgments.
They require actor, movement, narration, label and pose-quality review before
prepare_online_clips.py may receive an isolated demonstration selection.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
import math
from pathlib import Path
import re

PATTERNS = {
    "serve": re.compile(r"\bserv(?:e|es|ing|ice)\b", re.I),
    "pass": re.compile(r"\b(?:receiv\w*|passing|forearm|platform)\b", re.I),
    "set": re.compile(r"\b(?:setting|setter|setters)\b", re.I),
    "spike": re.compile(r"\b(?:spik\w*|hitting|attacking|arm swing)\b", re.I),
    "block": re.compile(r"\bblock(?:ing|er|ers|s)?\b", re.I),
}


def shot_lead(identifier: str, shot: dict) -> dict | None:
    if not re.fullmatch(r"[A-Za-z0-9_-]{11}", identifier):
        raise ValueError("Expected original YouTube video identifier")
    start, end = shot.get("shot_start_sec"), shot.get("shot_end_sec")
    if any(isinstance(v, bool) or not isinstance(v, (float, int)) or not math.isfinite(v) for v in (start, end)):
        return None
    if not 0 <= start < end:
        return None
    rows = shot.get("overlapping_actionable_rows", [])
    narration = " ".join(str(row.get("text", "")) for row in rows if isinstance(row, dict)).strip()
    skills = [skill for skill, pattern in PATTERNS.items() if pattern.search(narration)]
    if not skills:
        return None
    parsed = shot.get("parsed_result", [])
    if not isinstance(parsed, list):
        raise ValueError("Expected a list of provisional shot judgments")
    labels = sorted(set(str(label).strip().lower() for label in parsed))
    label = labels[0] if len(labels) == 1 and labels[0] in {"correct", "incorrect"} else "mixed_or_unknown"
    return {"video_id": identifier, "source_group": identifier,
            "source_url": f"https://www.youtube.com/watch?v={identifier}",
            "shot_index": shot.get("shot_index"), "start": start, "end": end,
            "duration": end - start, "suggested_skills": skills,
            "provisional_source_label": label, "original_parsed_results": labels,
            "narration": narration, "requires_single_rep_trim": end - start > 20,
            "review_required": True, "coach_verified": False,
            "label_origin": "SportSkills machine-generated shot judgment; not independent coaching truth"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--annotations", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    leads, invalid, files = [], 0, 0
    for path in sorted(args.annotations.glob("*.json")):
        shots = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(shots, list):
            raise ValueError(f"{path.name}: expected a shot list")
        files += 1
        for shot in shots:
            if not isinstance(shot, dict):
                raise ValueError(f"{path.name}: expected shot objects")
            lead = shot_lead(path.stem, shot)
            if lead:
                leads.append(lead)
            else:
                invalid += 1
    counts = {skill: dict(Counter(row["provisional_source_label"] for row in leads if skill in row["suggested_skills"]))
              for skill in PATTERNS}
    report = {"annotation_files": files, "skipped_non_skill_or_invalid_shots": invalid,
              "review_windows": len(leads), "counts": counts, "training_approved": False,
              "source": "https://vision.cs.utexas.edu/projects/sportskills/dataset.html", "leads": leads}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2, allow_nan=False), encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key != "leads"}, indent=2))


if __name__ == "__main__":
    main()
