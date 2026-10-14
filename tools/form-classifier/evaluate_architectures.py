"""Compare compact TFLite-friendly classifiers with source-held-out evaluation."""
from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

import numpy as np
import tensorflow as tf
from tensorflow import keras

import train


def build(name: str, timesteps: int, features: int, classes: int) -> keras.Model:
    regularizer = keras.regularizers.l2(1e-3)
    inputs = keras.Input((timesteps, features))
    if name == "gap-linear":
        x = keras.layers.GlobalAveragePooling1D()(inputs)
    elif name == "gap-dense8":
        x = keras.layers.GlobalAveragePooling1D()(inputs)
        x = keras.layers.Dense(8, activation="relu", kernel_regularizer=regularizer)(x)
        x = keras.layers.Dropout(0.15)(x)
    elif name == "conv8":
        x = keras.layers.Conv1D(8, 5, padding="same", activation="relu", kernel_regularizer=regularizer)(inputs)
        x = keras.layers.GlobalAveragePooling1D()(x)
    elif name == "conv12x8":
        x = keras.layers.Conv1D(12, 5, padding="same", activation="relu", kernel_regularizer=regularizer)(inputs)
        x = keras.layers.MaxPooling1D(2)(x)
        x = keras.layers.Conv1D(8, 3, padding="same", activation="relu", kernel_regularizer=regularizer)(x)
        x = keras.layers.GlobalAveragePooling1D()(x)
    elif name == "flat-dense16":
        x = keras.layers.Flatten()(inputs)
        x = keras.layers.Dense(16, activation="relu", kernel_regularizer=regularizer)(x)
        x = keras.layers.Dropout(0.3)(x)
    else:
        raise ValueError(f"Unknown architecture: {name}")
    outputs = keras.layers.Dense(classes, activation="softmax", kernel_regularizer=regularizer)(x)
    return keras.Model(inputs, outputs, name=name)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", required=True)
    parser.add_argument("--skill", default="pass")
    parser.add_argument("--epochs", type=int, default=120)
    parser.add_argument("--min-usable", type=float, default=0.5)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    X, y, groups, _ = train.load(Path(args.data), args.skill, args.min_usable)
    classes = sorted(set(y.tolist()))
    y_index = np.array([classes.index(label) for label in y])
    group_names = sorted(set(groups.tolist()))
    architectures = ("gap-linear", "gap-dense8", "conv8", "conv12x8", "flat-dense16")
    results = []

    for architecture in architectures:
        aggregate = np.zeros((len(classes), len(classes)), dtype=int)
        folds = []
        for held_group in group_names:
            keras.backend.clear_session()
            seed_text = f"{architecture}:{held_group}".encode()
            seed = (train.SEED + int(hashlib.sha256(seed_text).hexdigest()[:8], 16)) % (2**31 - 1)
            keras.utils.set_random_seed(seed)
            test_mask = groups == held_group
            train_mask = ~test_mask
            (X_train, X_test), _, _ = train.standardise(X[train_mask], X[test_mask])
            y_train, y_test = y_index[train_mask], y_index[test_mask]
            weights = {
                index: len(y_train) / (len(classes) * max(1, int((y_train == index).sum())))
                for index in range(len(classes))
            }
            model = build(architecture, X.shape[1], X.shape[2], len(classes))
            model.compile(
                optimizer=keras.optimizers.Adam(1e-3),
                loss="sparse_categorical_crossentropy",
                metrics=["accuracy"],
            )
            history = model.fit(
                X_train,
                y_train,
                epochs=args.epochs,
                batch_size=min(16, max(2, len(X_train) // 2)),
                class_weight=weights,
                verbose=0,
            )
            predictions = model.predict(X_test, verbose=0).argmax(axis=1)
            matrix = np.zeros_like(aggregate)
            for actual, predicted in zip(y_test, predictions):
                matrix[actual, predicted] += 1
            aggregate += matrix
            folds.append(
                {
                    "held_source": held_group,
                    "test_samples": int(test_mask.sum()),
                    "accuracy": round(float(np.mean(predictions == y_test)), 4),
                    "final_train_accuracy": round(float(history.history["accuracy"][-1]), 4),
                    "confusion": matrix.tolist(),
                }
            )
        recalls = [
            aggregate[index, index] / aggregate[index].sum()
            for index in range(len(classes))
            if aggregate[index].sum()
        ]
        results.append(
            {
                "architecture": architecture,
                "parameters": int(model.count_params()),
                "accuracy": round(float(np.trace(aggregate) / aggregate.sum()), 4),
                "balanced_accuracy": round(float(np.mean(recalls)), 4),
                "confusion": aggregate.tolist(),
                "folds": folds,
            }
        )
        print(architecture, results[-1]["balanced_accuracy"], results[-1]["accuracy"], flush=True)

    report = {
        "evaluation": "architecture-selection-development",
        "deployment_approved": False,
        "untouched_test": False,
        "samples": int(len(X)),
        "class_counts": dict(Counter(y.tolist())),
        "source_groups": group_names,
        "epochs_per_fold": args.epochs,
        "results": sorted(results, key=lambda item: item["balanced_accuracy"], reverse=True),
    }
    destination = Path(args.out)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
