"""Join the app's replay output with the evidence labels and summarise.

    python evaluate.py

Reads results/candidates.csv (PublicClipReplay) and labels.csv. Movements a label
row does not cover are excluded: multi-person or match footage, cuts, unmarked
reps, or the wrong person tracked.
"""
import csv
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
TRAIN_MIN_PER_CLASS = 20
TRAIN_MIN_SOURCES = 5

candidates = list(csv.DictReader(open(HERE / "results" / "candidates.csv")))
labels = {}
for row in csv.DictReader(open(HERE / "labels.csv")):
    labels[(row["clip"], row["n"])] = row

seen = Counter()
rows = []
for c in candidates:
    seen[c["clip"]] += 1
    n = str(seen[c["clip"]])
    label = labels.get((c["clip"], n)) or labels.get((c["clip"], "*"))
    rows.append({**c, "n": n, "label": label["label"] if label else "exclude",
                 "evidence": label["evidence"] if label else "not covered by a label"})

with open(HERE / "results" / "labelled_movements.csv", "w", newline="") as f:
    keys = ["clip", "n", "skill", "label", "verdict", "form_score", "view", "start_ms", "contact_ms", "end_ms", "evidence"]
    w = csv.DictWriter(f, keys, extrasaction="ignore")
    w.writeheader()
    w.writerows(rows)

counted = lambda r: r["verdict"] == "Counted"
out = ["# App movement checks on public coaching clips", ""]
out.append("| Skill | Label | Movements | Counted by the app | Mean form score when counted | Refusal reasons |")
out.append("| --- | --- | --- | --- | --- | --- |")
groups = defaultdict(list)
for r in rows:
    groups[(r["skill"], r["label"])].append(r)
order = ["correct", "faulty", "drill", "not_a_rep", "wrong_skill", "exclude"]
for skill in ["Serving", "Setting", "Spiking", "Blocking"]:
    for label in order:
        g = groups.get((skill, label))
        if not g:
            continue
        k = [r for r in g if counted(r)]
        scores = [int(r["form_score"]) for r in k if r["form_score"]]
        mean = f"{sum(scores) / len(scores):.0f}" if scores else "-"
        reasons = Counter(r["verdict"].replace("NotCounted:", "") for r in g if not counted(r))
        out.append(f"| {skill} | {label} | {len(g)} | {len(k)} ({len(k) / len(g):.0%}) | {mean} | "
                   + ", ".join(f"{v} {n}" for v, n in reasons.most_common()) + " |")

out += ["", "## Correct reps by camera view", "",
        "| Skill | View | Correct reps | Counted |", "| --- | --- | --- | --- |"]
for skill in ["Serving", "Setting", "Spiking"]:
    views = defaultdict(list)
    for r in groups.get((skill, "correct"), []):
        views[r["view"]].append(r)
    for view, g in sorted(views.items()):
        out.append(f"| {skill} | {view} | {len(g)} | {sum(map(counted, g))} |")

out += ["", "## Can a fault model be trained?", "",
        f"Needs at least {TRAIN_MIN_PER_CLASS} correct and {TRAIN_MIN_PER_CLASS} faulty reps "
        f"from at least {TRAIN_MIN_SOURCES} different videos per skill.", "",
        "| Skill | Correct reps (videos) | Faulty reps (videos) | Trainable |", "| --- | --- | --- | --- |"]
for skill in ["Serving", "Setting", "Spiking", "Blocking"]:
    c = groups.get((skill, "correct"), [])
    f_ = groups.get((skill, "faulty"), [])
    cs, fs = len({r["clip"] for r in c}), len({r["clip"] for r in f_})
    ok = len(c) >= TRAIN_MIN_PER_CLASS and len(f_) >= TRAIN_MIN_PER_CLASS and min(cs, fs) >= TRAIN_MIN_SOURCES
    out.append(f"| {skill} | {len(c)} ({cs}) | {len(f_)} ({fs}) | {'yes' if ok else 'no'} |")

(HERE / "results" / "evaluation.md").write_text("\n".join(out) + "\n")
print("\n".join(out))
