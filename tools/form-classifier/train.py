"""Trains the form classifier that sits on top of BlazePose, and exports it to TFLite.

    python train.py --data "<repo>/tools/pose-pipeline/data" --out models/

Input is whatever `extract.py` produced: per-segment sequences of joint angles,
shaped (segments, timesteps, features), with a label per segment. Output is a
`.tflite` bundle small enough to sit in the app's assets beside the pose model,
plus the normalisation constants the app must apply to match what was trained.

This does not train BlazePose. BlazePose is frozen, pretrained, and already
shipping in `app/src/main/assets/`. What gets trained here is the small model
that reads its landmarks and decides whether the rep was any good - a few
thousand parameters, not a few million.

Labels are taken from the data rather than hardcoded, so the same script trains
a two-class correct/faulty model on badge-labelled coaching clips and an n-class
skill model on manually labelled ones. It does not need to know which it is doing.

On honesty about the metric
---------------------------
The script splits by *source clip*, never randomly. Two segments cut from one
video share an athlete, a gym, a camera and a session; putting one in train and
the other in test measures memorisation and reports it as accuracy. When there
are too few distinct clips to hold any out, the script still trains - the code
path is worth exercising - but it refuses to print a headline accuracy and says
why. A number you cannot defend is worse than no number.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

import numpy as np

# Keras 3 ships with TensorFlow 2.16+; importing through tf keeps one source.
import tensorflow as tf
from tensorflow import keras

# Below this many distinct source clips, no split can separate athlete from
# form, so no reported accuracy would mean anything.
MIN_CLIPS_FOR_HOLDOUT = 3

# Must match extract.SCHEMA_VERSION. See the guard in load().
SCHEMA_VERSION = 2

SEED = 1337


def load(data_dir: Path, skill: str | None,
         min_usable: float) -> tuple[np.ndarray, np.ndarray, np.ndarray, list[str]]:
    """Gathers every labelled, long-enough segment across a pipeline run.

    Returns X, y (string labels), groups (source clip per sample), and the
    feature names, so a caller can tell which column was which without
    re-deriving the order from `features.py`.

    `skill` filters to one movement. A tick never means "correct" on its own - it
    means correct *serve* or correct *pass*, and those are different shapes.
    Pooling them measurably weakens every feature, so training one model across
    skills is a mistake the data will not announce.

    `min_usable` drops segments whose skeleton was mostly unreliable. Coaching
    clips cut to arms-only close-ups to show a detail, and those frames carry a
    real badge over a body the detector never saw. They are labelled, they are
    long enough, and they are worse than nothing: the model fits whatever noise
    the detector emitted and the label says it was right to.
    """
    sequences, labels, groups = [], [], []
    feature_names: list[str] = []

    for path in sorted(data_dir.glob("*_sequences.npz")):
        bundle = np.load(path, allow_pickle=True)
        # Version 1 measured features in image space and version 2 in the app's
        # world space. The arrays have the same shape and neither looks wrong, so
        # mixing them would train a model on 23 degrees of silent noise. Refuse
        # rather than guess.
        version = int(bundle["schema_version"]) if "schema_version" in bundle else 1
        if version != SCHEMA_VERSION:
            raise SystemExit(
                f"{path.name} is schema v{version}, this script needs v{SCHEMA_VERSION}. "
                f"Re-run extract.py over the clips to regenerate it."
            )
        seq, lab = bundle["sequences"], bundle["labels"]
        # `too_short` predates nothing - older runs may not have it, and a
        # missing flag should not silently admit one-frame segments.
        short = bundle["too_short"] if "too_short" in bundle else np.zeros(len(seq), bool)
        names = [str(n) for n in bundle["feature_names"]]
        if not feature_names:
            feature_names = names
        elif feature_names != names:
            raise SystemExit(f"Feature order mismatch in {path.name}; regenerate features.")
        if seq.ndim != 3 or seq.shape[2] != len(names) or len(seq) != len(lab):
            raise SystemExit(f"Invalid feature/label dimensions in {path.name}")

        keep = (lab != "unlabelled") & ~short
        if skill is not None:
            tags = bundle["skill"] if "skill" in bundle else np.array(["unknown"] * len(seq))
            keep &= (tags == skill)
        if min_usable > 0:
            if "usable_frac" not in bundle:
                raise SystemExit(f"Missing usable_frac in {path.name}; regenerate features.")
            keep &= (bundle["usable_frac"] >= min_usable)
        if not keep.any():
            continue
        sequences.append(seq[keep])
        labels.append(lab[keep])
        if "source_group" in bundle:
            source_groups = np.asarray(bundle["source_group"]).astype(str)
            if source_groups.ndim == 0:
                source_groups = np.repeat(source_groups, len(seq))
            if len(source_groups) != len(seq):
                raise SystemExit(f"Invalid source_group length in {path.name}")
            groups.extend(source_groups[keep].tolist())
        else:
            groups.extend([path.stem.replace("_sequences", "")] * int(keep.sum()))

    if not sequences:
        return np.empty((0,)), np.empty((0,)), np.empty((0,)), feature_names

    return (np.concatenate(sequences), np.concatenate(labels),
            np.array(groups), feature_names)


def standardise(train: np.ndarray, *others: np.ndarray):
    """Centres and scales each feature using training statistics only.

    Two things matter here. The statistics come from the training split alone -
    computing them over everything leaks the test set's distribution into the
    model. And they are returned, because the Android app has to apply the exact
    same shift and scale before calling the model; a classifier fed unstandardised
    angles will produce confident nonsense rather than an obvious error.

    NaNs become zero *after* centring, which places a missing landmark at the
    training mean. That is the least-committal value available, and the extract
    pipeline already flags how often it happens.
    """
    train = np.where(np.isfinite(train), train, np.nan)
    observed = np.isfinite(train).any(axis=(0, 1))
    # Disabled all-missing features get finite metadata and remain zero at inference.
    mean = np.zeros(train.shape[-1], dtype=np.float64)
    std = np.ones(train.shape[-1], dtype=np.float64)
    mean[observed] = np.nanmean(train[..., observed], axis=(0, 1))
    std[observed] = np.nanstd(train[..., observed], axis=(0, 1))
    std[std < 1e-6] = 1.0

    def apply(a: np.ndarray) -> np.ndarray:
        result = np.where(np.isfinite(a), (a - mean) / std, 0.0)
        result[..., ~observed] = 0.0
        return result.astype(np.float32)

    return (apply(train), *[apply(o) for o in others]), mean, std


def build(timesteps: int, features: int, classes: int, samples: int) -> keras.Model:
    """A deliberately small temporal CNN.

    Width is scaled to the dataset because capacity is the thing most likely to
    go wrong here: a model with more parameters than it has examples will reach
    perfect training accuracy on anything and has learned nothing. At a few dozen
    samples this is barely more than a smoothed linear model, which is the honest
    ceiling for that much data.

    Convolution rather than an LSTM because the discriminative part of a rep is a
    short local shape - the platform coming up, the knees straightening - not a
    long dependency, and because Conv1D converts to TFLite without the
    select-ops flag an LSTM would drag in.
    """
    # With only a few labelled repetitions, even the small temporal CNN below
    # has far more trainable parameters than examples.  Its perfect training
    # score is then just memorisation.  The extracted feature stream already
    # contains motion summaries (hip/wrist rise), so a pooled linear classifier
    # is the most defensible model until the dataset reaches 50 clean segments.
    if samples < 50:
        return keras.Sequential([
            keras.layers.Input(shape=(timesteps, features)),
            keras.layers.GlobalAveragePooling1D(),
            keras.layers.Dropout(0.15),
            keras.layers.Dense(classes, activation="softmax",
                               kernel_regularizer=keras.regularizers.l2(1e-3)),
        ])

    width = 16 if samples < 200 else 32

    return keras.Sequential([
        keras.layers.Input(shape=(timesteps, features)),
        keras.layers.Conv1D(width, 5, padding="same", activation="relu"),
        keras.layers.BatchNormalization(),
        keras.layers.Conv1D(width, 3, padding="same", activation="relu"),
        keras.layers.GlobalAveragePooling1D(),
        keras.layers.Dropout(0.3),
        keras.layers.Dense(classes, activation="softmax"),
    ])


def split_by_clip(groups: np.ndarray, rng: np.random.Generator) -> tuple[np.ndarray, np.ndarray] | None:
    """Holds out whole clips, or returns None when there are too few to try."""
    unique = np.unique(groups)
    if len(unique) < MIN_CLIPS_FOR_HOLDOUT:
        return None

    held = rng.choice(unique, size=max(1, len(unique) // 4), replace=False)
    test = np.isin(groups, held)
    if test.all() or not test.any():
        return None
    return ~test, test


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    # A sibling hop inside the repo, so this resolves the same on every machine.
    # It used to reach back out through the Desktop into Downloads, which worked
    # on exactly one computer.
    default_data = Path(__file__).resolve().parent.parent / "pose-pipeline" / "data"
    parser.add_argument("--data", default=str(default_data),
                        help="directory of *_sequences.npz from extract.py")
    parser.add_argument("--out", default="models", help="where to write the model")
    parser.add_argument("--epochs", type=int, default=120)
    parser.add_argument("--skill", default=None,
                        help="train on one skill only (serve, pass, set, spike, block). "
                             "Omitting this pools every skill, which is almost always wrong")
    parser.add_argument("--min-usable", type=float, default=0.5,
                        help="drop segments whose fraction of clean frames is below this "
                             "(default 0.5); close-up detail shots score near zero")
    parser.add_argument("--train-all", action="store_true",
                        help="fit the export model on every eligible sample after a separate "
                             "source-separated evaluation has been completed")
    args = parser.parse_args()

    data_dir = Path(args.data).resolve()
    X, y, groups, feature_names = load(data_dir, args.skill, args.min_usable)

    if not len(X):
        print(f"No labelled segments in {data_dir}.\n"
              f"Run extract.py first, and check that its clips carry tick/cross badges.")
        return 1

    classes = sorted(set(y.tolist()))
    if len(classes) < 2:
        raise SystemExit("Need at least two label classes; single-class accuracy is meaningless.")
    counts = Counter(y.tolist())
    clips = sorted(set(groups.tolist()))

    print(f"data      : {data_dir}")
    print(f"samples   : {len(X)}  shape {X.shape[1:]}")
    print(f"classes   : {', '.join(f'{c}={counts[c]}' for c in classes)}")
    print(f"clips     : {len(clips)} ({', '.join(clips)})")

    y_index = np.array([classes.index(label) for label in y])
    rng = np.random.default_rng(SEED)
    keras.utils.set_random_seed(SEED)

    split = None if args.train_all else split_by_clip(groups, rng)
    if args.train_all:
        print("\nfinal fit : all eligible samples (use a separate LOSO report for accuracy)")
        train_mask = np.ones(len(X), bool)
        test_mask = np.zeros(len(X), bool)
    elif split is None:
        print(f"\n  Fewer than {MIN_CLIPS_FOR_HOLDOUT} source clips, so no honest holdout\n"
              f"  exists. Training anyway to exercise the pipeline; accuracy will NOT\n"
              f"  be reported, because with one athlete it would measure memorisation.")
        train_mask = np.ones(len(X), bool)
        test_mask = np.zeros(len(X), bool)
    else:
        train_mask, test_mask = split
        print(f"\nholdout   : {int(test_mask.sum())} samples from "
              f"{len(set(groups[test_mask].tolist()))} clip(s)")

    (X_train, X_test), mean, std = standardise(X[train_mask], X[test_mask])
    y_train, y_test = y_index[train_mask], y_index[test_mask]
    if set(y_train.tolist()) != set(range(len(classes))):
        raise SystemExit("Training split lacks a label class; collect more independent sources.")

    # Faulty demonstrations are rarer than correct ones in coaching clips, and an
    # unweighted model can score well by never predicting the class the whole
    # product exists to detect.
    weights = {i: len(y_train) / (len(classes) * max(1, (y_train == i).sum()))
               for i in range(len(classes))}

    model = build(X.shape[1], X.shape[2], len(classes), len(X_train))
    model.compile(optimizer=keras.optimizers.Adam(1e-3),
                  loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    print(f"params    : {model.count_params():,}\n")

    history = model.fit(
        X_train, y_train, epochs=args.epochs, batch_size=min(16, max(2, len(X_train) // 2)),
        class_weight=weights, verbose=0,
        # Fixed epochs; reserve the test set for the final evaluation only.
    )

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    metrics = {
        "deployment_approved": False,
        "label_scope": "Agreement with supplied segment labels; not independently verified coaching accuracy.",
        "athlete_independence_verified": False,
        "samples": int(len(X)),
        "classes": classes,
        "class_counts": {k: int(v) for k, v in counts.items()},
        "source_clips": clips,
        "parameters": int(model.count_params()),
        "final_train_accuracy": round(float(history.history["accuracy"][-1]), 4),
        "training_mode": "all-data-final-fit" if args.train_all else "source-holdout",
    }

    if test_mask.any():
        loss, accuracy = model.evaluate(X_test, y_test, verbose=0)
        predictions = model.predict(X_test, verbose=0).argmax(axis=1)
        matrix = np.zeros((len(classes), len(classes)), int)
        for actual, predicted in zip(y_test, predictions):
            matrix[actual][predicted] += 1
        metrics["holdout_accuracy"] = round(float(accuracy), 4)
        metrics["holdout_confusion"] = matrix.tolist()
        print(f"holdout accuracy: {accuracy:.3f}")
        print("confusion (rows actual, cols predicted):")
        print(f"  {'':<12}{''.join(f'{c:>10}' for c in classes)}")
        for i, name in enumerate(classes):
            print(f"  {name:<12}{''.join(f'{v:>10}' for v in matrix[i])}")
    else:
        metrics["holdout_accuracy"] = None
        if args.train_all:
            metrics["holdout_note"] = (
                "Final export fit used all eligible samples. Accuracy is reported only "
                "in the separate leave-one-source-out evaluation."
            )
            print(f"final-fit train accuracy: {metrics['final_train_accuracy']:.3f}  "
                  "(not a validation result)")
        else:
            metrics["holdout_note"] = (
                f"Not measured: {len(clips)} source clip(s), fewer than the "
                f"{MIN_CLIPS_FOR_HOLDOUT} needed to hold one out. Any accuracy from this "
                f"run would describe one athlete in one session, not the skill."
            )
            print(f"train accuracy  : {metrics['final_train_accuracy']:.3f}  "
                  f"(memorisation - not a result)")

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite = converter.convert()
    (out_dir / "form_classifier.tflite").write_bytes(tflite)

    # The app cannot use the model without these. Same file, so they cannot drift
    # apart from the weights they belong to.
    (out_dir / "form_classifier.json").write_text(json.dumps({
        "classes": classes,
        "feature_names": feature_names,
        "timesteps": int(X.shape[1]),
        "model_sha256": hashlib.sha256(tflite).hexdigest(),
        "skill": args.skill,
        "disabled_features": [feature_names[i] for i in range(len(feature_names))
                              if not np.isfinite(X[train_mask, :, i]).any()],
        "normalisation": {"mean": mean.tolist(), "std": std.tolist()},
        "metrics": metrics,
    }, indent=2, allow_nan=False), encoding="utf-8")

    print(f"\nwrote {out_dir / 'form_classifier.tflite'} ({len(tflite) / 1024:.1f} KB)")
    print(f"wrote {out_dir / 'form_classifier.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
