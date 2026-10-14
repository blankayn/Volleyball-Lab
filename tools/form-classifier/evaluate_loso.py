"""Leave-one-source-out evaluation for the app-aligned form classifier.

Every clip cut from the same original video carries the same ``source_group``.
Each fold holds out one original video. Athlete/session overlap across different
videos is not verified; this is development evaluation, not an untouched test.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

import numpy as np
from tensorflow import keras

import train


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--data", required=True)
    parser.add_argument("--skill", required=True)
    parser.add_argument("--epochs", type=int, default=120)
    parser.add_argument("--min-usable", type=float, default=0.5)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    X, y, groups, _ = train.load(Path(args.data), args.skill, args.min_usable)
    classes = sorted(set(y.tolist()))
    if len(classes) < 2:
        raise SystemExit("Need at least two label classes.")
    y_index = np.array([classes.index(label) for label in y])
    unique_groups = sorted(set(groups.tolist()))
    if len(unique_groups) < 3:
        raise SystemExit("Need at least three independent source groups.")

    folds = []
    aggregate = np.zeros((len(classes), len(classes)), dtype=int)
    for fold_index, held_group in enumerate(unique_groups):
        keras.backend.clear_session()
        fold_seed = (train.SEED + int(hashlib.sha256(held_group.encode()).hexdigest()[:8], 16)) % (2**31 - 1)
        keras.utils.set_random_seed(fold_seed)
        test_mask = groups == held_group
        train_mask = ~test_mask
        y_train, y_test = y_index[train_mask], y_index[test_mask]
        if set(y_train.tolist()) != set(range(len(classes))):
            folds.append({"held_source": held_group, "status": "skipped",
                          "reason": "training fold lacks a label class"})
            continue

        (X_train, X_test), _, _ = train.standardise(X[train_mask], X[test_mask])
        weights = {i: len(y_train) / (len(classes) * max(1, (y_train == i).sum()))
                   for i in range(len(classes))}
        model = train.build(X.shape[1], X.shape[2], len(classes), len(X_train))
        model.compile(optimizer=keras.optimizers.Adam(1e-3),
                      loss="sparse_categorical_crossentropy", metrics=["accuracy"])
        history = model.fit(
            X_train, y_train, epochs=args.epochs,
            batch_size=min(16, max(2, len(X_train) // 2)),
            class_weight=weights, verbose=0)
        predictions = model.predict(X_test, verbose=0).argmax(axis=1)
        matrix = np.zeros_like(aggregate)
        for actual, predicted in zip(y_test, predictions):
            matrix[actual, predicted] += 1
        aggregate += matrix
        folds.append({
            "held_source": held_group,
            "seed": fold_seed,
            "status": "evaluated",
            "test_samples": int(test_mask.sum()),
            "test_class_counts": {c: int((y[test_mask] == c).sum()) for c in classes},
            "train_samples": int(train_mask.sum()),
            "parameters": int(model.count_params()),
            "final_train_accuracy": round(float(history.history["accuracy"][-1]), 4),
            "accuracy": round(float(np.mean(predictions == y_test)), 4),
            "confusion": matrix.tolist(),
        })

    evaluated = [f for f in folds if f["status"] == "evaluated"]
    total = int(aggregate.sum())
    correct = int(np.trace(aggregate))
    recalls = [aggregate[i, i] / aggregate[i].sum()
               for i in range(len(classes)) if aggregate[i].sum()]
    result = {
        "evaluation": "leave-one-source-out-development",
        "deployment_approved": False,
        "classes": classes,
        "untouched_test": False,
        "label_scope": "Agreement with provisional demonstration labels. Labels and athlete independence need independent review.",
        "skill": args.skill,
        "min_usable": args.min_usable,
        "epochs_per_fold": args.epochs,
        "samples": int(len(X)),
        "class_counts": dict(Counter(y.tolist())),
        "source_groups": unique_groups,
        "folds_evaluated": len(evaluated),
        "folds": folds,
        "aggregate_accuracy": round(correct / total, 4) if total else None,
        "balanced_accuracy": round(float(np.mean(recalls)), 4) if recalls else None,
        "aggregate_confusion": aggregate.tolist(),
    }
    destination = Path(args.out)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
