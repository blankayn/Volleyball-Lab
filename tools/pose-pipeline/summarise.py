"""Compares the angle features of correct reps against faulty ones.

    python summarise.py data/

Reads every `*_frames.csv` a run produced and prints, per feature, the mean for
`ok` frames against the mean for `fault` frames. Only clean frames count - a
frame the detector was unsure about would otherwise drag a mean toward whatever
the detector guesses when it cannot see.

This is the first thing to run after an extract, and it answers a question worth
asking early: do these features move at all between good and bad form? A feature
whose two means sit on top of each other is one the model cannot use, and finding
that out here costs a second, whereas finding it out after training costs a week.

The separations it prints are diagnostics, not thresholds. Thresholds need many
athletes; with one demonstrator these numbers describe that person.
"""
from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path

import numpy as np

import features

LABELS = ("ok", "fault")


def load(data_dir: Path) -> dict[str, dict[str, list[float]]]:
    """Gathers clean-frame feature values per label across every clip in a run."""
    collected = {label: {name: [] for name in features.FEATURE_NAMES} for label in LABELS}
    clips = 0

    for path in sorted(data_dir.glob("*_frames.csv")):
        clips += 1
        with path.open(encoding="utf-8") as handle:
            for row in csv.DictReader(handle):
                if row["label"] not in LABELS:
                    continue
                # A frame with any low-confidence landmark is not evidence about
                # form; it is evidence about the camera.
                if row["valid"] != "1" or int(row["low_visibility_count"]) > 0:
                    continue
                for name in features.FEATURE_NAMES:
                    if row[name]:
                        collected[row["label"]][name].append(float(row[name]))

    if not clips:
        print(f"No *_frames.csv in {data_dir}", file=sys.stderr)
    return collected


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("data", nargs="?", default="data", help="extract.py output directory")
    args = parser.parse_args()

    collected = load(Path(args.data))
    ok, fault = collected["ok"], collected["fault"]

    counts = (len(ok[features.FEATURE_NAMES[0]]), len(fault[features.FEATURE_NAMES[0]]))
    if not all(counts):
        print(f"Need clean frames of both classes; have ok={counts[0]} fault={counts[1]}.",
              file=sys.stderr)
        return 1

    print(f"clean frames: {counts[0]} ok, {counts[1]} fault\n")
    print(f"{'feature':<20}{'ok':>10}{'fault':>10}{'delta':>10}{'separation':>12}  unit")

    rows = []
    for name in features.FEATURE_NAMES:
        a, b = np.array(ok[name]), np.array(fault[name])
        if not a.size or not b.size:
            continue
        delta = float(np.mean(a) - np.mean(b))
        # Pooled spread, so degrees and metres can be ranked against each other at
        # all. The unit is printed beside every row because the raw means cannot
        # be compared across rows even though the separations can.
        spread = float(np.sqrt((np.var(a) + np.var(b)) / 2)) or 1e-9
        rows.append((abs(delta / spread), name, float(np.mean(a)), float(np.mean(b)), delta))

    for separation, name, mean_ok, mean_fault, delta in sorted(rows, reverse=True):
        unit = features.FEATURE_UNITS.get(name, "")
        print(f"{name:<20}{mean_ok:10.2f}{mean_fault:10.2f}{delta:10.2f}"
              f"{separation:12.2f}  {unit}")

    print("\nSeparation is the mean gap in pooled standard deviations. Above ~0.8 is a\n"
          "feature worth keeping; near zero is one the model cannot learn from.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
