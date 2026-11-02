"""Compare temporal coaches on linked-source folds, or run a locked player test.

Development metrics describe provisional labels, not professional coaching
accuracy. Locked mode requires independently reviewed registry identities and
disjoint train/tune/calibration/test groups. No mode installs models in Android.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
from pathlib import Path
import platform
import time

import numpy as np

from coach_dataset import CLASSES, SKILLS, load_skill, locked_partitions, read_registry
from evaluation import fit_calibration, grouped_folds, metrics, qualify_on_locked_test, temperature_scale
from temporal_models import ARCHITECTURES, augment_training, build_model


def normalize(X, mean, std, observed):
    result = np.where(np.isfinite(X), (X - mean) / std, 0).astype(np.float32)
    result[..., ~observed] = 0
    return result


def fit(data, indices, architecture, rate, epochs, copies, seed):
    from tensorflow import keras
    import train
    keras.backend.clear_session()
    keras.utils.set_random_seed(seed)
    raw, labels = data.X[indices], data.y[indices]
    if set(labels.tolist()) != {0, 1}:
        raise ValueError("Training must retain both correct and faulty examples")
    (X,), mean, std = train.standardise(raw)
    observed = np.isfinite(raw).any(axis=(0, 1))
    if "observed_frame" in data.feature_names:
        mask_index = data.feature_names.index("observed_frame")
        mean[mask_index], std[mask_index] = 0., 1.
        X = normalize(raw, mean, std, observed)
    augmented = augment_training(X, data.feature_names, copies, seed)
    y = np.tile(labels, copies + 1)
    model = build_model(architecture, X.shape[1], X.shape[2], data.feature_names)
    model.compile(optimizer=keras.optimizers.Adam(rate), loss="sparse_categorical_crossentropy")
    weights = {i: len(y) / (2 * int((y == i).sum())) for i in range(2)}
    model.fit(augmented, y, epochs=epochs, batch_size=min(16, len(y)), class_weight=weights, verbose=0)
    return model, mean, std, observed


def predict(fitted, X):
    model, mean, std, observed = fitted
    return model(normalize(X, mean, std, observed), training=False).numpy()


def export(fitted, data, folder, architecture, policy, quality_field):
    import tensorflow as tf
    model, mean, std, observed = fitted
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    blob = converter.convert()
    folder.mkdir(parents=True, exist_ok=True)
    model_path = folder / "form_classifier.tflite"
    model_path.write_bytes(blob)
    interpreter = tf.lite.Interpreter(model_path=str(model_path), num_threads=1)
    interpreter.allocate_tensors()
    input_info, output_info = interpreter.get_input_details()[0], interpreter.get_output_details()[0]
    if input_info["shape"].tolist() != [1, 32, len(data.feature_names)] or output_info["shape"].tolist() != [1, 2]:
        raise ValueError("Export tensor contract mismatch")
    probes = data.X[:min(32, len(data.X))]
    expected = predict(fitted, probes)
    exported_probabilities = []
    for probe in probes:
        interpreter.set_tensor(input_info["index"], normalize(probe[None, ...], mean, std, observed))
        interpreter.invoke()
        exported_probabilities.append(interpreter.get_tensor(output_info["index"])[0])
    actual = np.asarray(exported_probabilities)
    difference = float(np.max(np.abs(actual - expected)))
    if (not np.isfinite(actual).all() or difference > .03 or (actual < 0).any() or (actual > 1).any()
            or not np.allclose(actual.sum(axis=1), 1, atol=1e-4)):
        raise ValueError("Export differs materially from the TensorFlow model")
    raw_flips = int((actual.argmax(axis=1) != expected.argmax(axis=1)).sum())
    if policy.get("policy") == "selective":
        reference = temperature_scale(expected, policy["temperature"])
        mobile = temperature_scale(actual, policy["temperature"])
        reference_accepts = reference.max(axis=1) >= policy["accept_above"]
        mobile_accepts = mobile.max(axis=1) >= policy["accept_above"]
        if ((reference_accepts != mobile_accepts).any() or
            ((reference.argmax(axis=1) != mobile.argmax(axis=1)) & (reference_accepts | mobile_accepts)).any()):
            raise ValueError("Native export changed a calibrated acceptance decision")
    sample = normalize(data.X[:1], mean, std, observed)
    interpreter.set_tensor(input_info["index"], sample)
    for _ in range(5):
        interpreter.invoke()
    timings = []
    for _ in range(30):
        start = time.perf_counter_ns()
        interpreter.invoke()
        timings.append((time.perf_counter_ns() - start) / 1e6)
    # Numerical export fidelity is not a classification accuracy test.
    benchmark = {"runtime": "host CPU, one interpreter thread", "host": platform.machine(),
                 "model_sha256": hashlib.sha256(blob).hexdigest(),
                 "target_phone_measured": False, "warmups": 5, "iterations": 30,
                 "p50_ms": float(np.percentile(timings, 50)), "p95_ms": float(np.percentile(timings, 95)),
                 "bytes": len(blob), "max_probability_difference": difference,
                 "builtin_ops_only": True, "fidelity_probe_samples": len(probes), "raw_argmax_flips": raw_flips,
                 "includes_pose_estimation": False, "includes_feature_extraction": False,
                 "includes_video_decode": False}
    metadata = {"schema_version": 2, "architecture": architecture, "timesteps": 32,
                "classes": CLASSES, "feature_names": data.feature_names,
                "temporal_differences_inside_graph": architecture != "linear",
                "normalisation": {"mean": mean.tolist(), "std": std.tolist()},
                "disabled_features": [name for name, enabled in zip(data.feature_names, observed) if not enabled],
                "model_sha256": hashlib.sha256(blob).hexdigest(), "decision_policy": policy,
                "quality_field": quality_field, "deployment_approved": False,
                "label_scope": "Agreement with supplied demonstration labels; not independently established coaching accuracy",
                "benchmark": benchmark, "framework_version": tf.__version__,
                "skill": data.rows[0]["skill"]}
    metadata["task_scope"] = data.rows[0]["task_scope"]
    metadata["feature_recipe"] = data.rows[0]["feature_recipe"]
    metadata["feature_quality_profile"] = data.rows[0]["feature_quality_profile"]
    metadata["observed_arms_in_training"] = sorted({row["observed_arm"] for row in data.rows})
    metadata["pose_configuration"] = json.loads(data.rows[0]["pose_configuration"])
    (folder / "form_classifier.json").write_text(json.dumps(metadata, indent=2, allow_nan=False), encoding="utf-8")
    return benchmark


def development(data, configs, args, folder):
    results = []
    for architecture, rate in configs:
        predictions, actual, indices, folds = [], [], [], []
        for fold in grouped_folds(data.y, data.groups, 2):
            if fold["reason"]:
                folds.append({"held_group": fold["held_group"], "status": "skipped", "reason": fold["reason"]})
                continue
            seed = 1337 + int(hashlib.sha256(fold["held_group"].encode()).hexdigest()[:6], 16)
            fitted = fit(data, fold["train"], architecture, rate, args.epochs, args.augment_copies, seed)
            p = predict(fitted, data.X[fold["test"]])
            predictions.extend(p.tolist())
            actual.extend(data.y[fold["test"]].tolist())
            indices.extend(fold["test"].tolist())
            folds.append({"held_group": fold["held_group"], "status": "evaluated",
                          "metrics": metrics(data.y[fold["test"]], p, CLASSES, groups=data.groups[fold["test"]])})
        result = {"architecture": architecture, "learning_rate": rate, "folds": folds,
                  "held_out_coverage": len(indices) / len(data.y),
                  "development_metrics": metrics(actual, predictions, CLASSES, groups=data.groups[indices]) if actual else None,
                  "sample_predictions": [{"sample_id": data.rows[i]["sample_id"], "actual": CLASSES[y], "probabilities": p}
                                         for i, y, p in zip(indices, actual, predictions)],
                  "independent_accuracy": None, "calibration": "unavailable; abstain in coaching inference"}
        # Benchmark candidates on all development data, never call this an
        # untouched test or choose a production winner from the outer folds.
        fitted = fit(data, np.arange(len(data.y)), architecture, rate, args.epochs, args.augment_copies, 1337)
        result["parameters"] = fitted[0].count_params()
        try:
            result["export"] = export(fitted, data, folder / f"{architecture}-{rate:g}", architecture,
                {"status": "uncalibrated", "policy": "abstain", "temperature": None, "accept_above": None}, args.quality_field)
        except Exception as error:
            result["export"] = {"status": "failed", "reason": str(error)}
        results.append(result)
        (folder / "comparison.json").write_text(json.dumps(results, indent=2, allow_nan=False), encoding="utf-8")
        print(f"{folder.name}: {architecture} development evaluation recorded", flush=True)
    return {"status": "development_only", "selected_production_model": None, "results": results,
            "unseen_players_verified": False, "untouched_test": False, "deployment_approved": False}


def locked(data, configs, args, folder):
    partitions = locked_partitions(data)
    comparisons = []
    for architecture, rate in configs:
        fitted = fit(data, partitions["train"], architecture, rate, args.epochs, args.augment_copies, 1337)
        score = metrics(data.y[partitions["tune"]], predict(fitted, data.X[partitions["tune"]]), CLASSES)
        comparisons.append((score["macro_f1"], architecture, rate))
    _, architecture, rate = max(comparisons, key=lambda item: item[0])
    # Deterministic refit uses the same train partition, not tune/calibration/test.
    fitted = fit(data, partitions["train"], architecture, rate, args.epochs, args.augment_copies, 1337)
    calibration_probabilities = predict(fitted, data.X[partitions["calibration"]])
    policy = fit_calibration(data.y[partitions["calibration"]], calibration_probabilities, CLASSES,
                             groups=data.groups[partitions["calibration"]])
    raw = predict(fitted, data.X[partitions["test"]])
    calibrated = temperature_scale(raw, policy["temperature"]) if policy["temperature"] else raw
    test_result = metrics(data.y[partitions["test"]], calibrated, CLASSES, policy["accept_above"] or 1.0,
                          data.groups[partitions["test"]])
    if policy["policy"] == "abstain":
        test_result["selective"] = {"coverage": 0.0, "accepted": 0, "accuracy": None, "risk": None,
                                     "reason": "No supported calibrated acceptance policy"}
    published_policy = qualify_on_locked_test(policy, test_result)
    benchmark = export(fitted, data, folder / "candidate", architecture, published_policy, args.quality_field)
    return {"status": "locked_evaluation_recorded", "architecture": architecture, "learning_rate": rate,
            "tuning_candidates": [{"macro_f1": score, "architecture": name, "learning_rate": lr} for score, name, lr in comparisons],
            "test_metrics": test_result, "calibration": policy, "published_policy": published_policy, "export": benchmark,
            "partition_samples": {role: [data.rows[i]["sample_id"] for i in indices] for role, indices in partitions.items()},
            "unseen_players_separated_by_registry": True, "test_isolated_from_selection": True,
            "untouched_test": False, "deployment_approved": False,
            "release_note": "Independent replication, coaching review and target-phone acceptance still required"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--registry", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--mode", choices=("development", "locked"), default="locked")
    parser.add_argument("--skills", nargs="+", choices=SKILLS, default=list(SKILLS))
    parser.add_argument("--architectures", nargs="+", choices=ARCHITECTURES, default=list(ARCHITECTURES))
    parser.add_argument("--rates", nargs="+", type=float, default=[0.001])
    parser.add_argument("--epochs", type=int, default=20)
    parser.add_argument("--augment-copies", type=int, default=1)
    parser.add_argument("--quality-field", choices=("usable_frac", "feature_usable_frac"), default="feature_usable_frac")
    parser.add_argument("--min-usable", type=float, default=0.5)
    args = parser.parse_args()
    if args.epochs < 1 or any(not np.isfinite(r) or r <= 0 for r in args.rates) or not 0 <= args.augment_copies <= 10:
        parser.error("Use positive epochs/rates and zero to ten augmentation copies")
    args.out.mkdir(parents=True, exist_ok=True)
    registry = read_registry(args.registry)
    configs = [(name, rate) for name in args.architectures for rate in args.rates]
    report = {"mode": args.mode, "deployment_approved": False, "independent_overall_accuracy": None,
              "mpjpe_mm": None, "mpjpe_reason": "Independent calibrated metric 3D ground truth not supplied",
              "epochs": args.epochs, "augment_copies_train_only": args.augment_copies, "skills": {},
              "registry_sha256": hashlib.sha256(args.registry.read_bytes()).hexdigest() if args.registry else None,
              "corpus_provenance_sha256": hashlib.sha256((args.data / "provenance.json").read_bytes()).hexdigest()
                  if (args.data / "provenance.json").is_file() else None,
              "test_exposure_note": "Prior human/researcher exposure cannot be verified automatically; independent replication remains required"}
    registry_rows = []
    for skill in args.skills:
        folder = args.out / skill
        folder.mkdir(exist_ok=True)
        data = None
        try:
            data = load_skill(args.data, skill, registry, args.quality_field, args.min_usable)
            (folder / "dataset-manifest.json").write_text(json.dumps({"feature_names": data.feature_names,
                "quality_field": args.quality_field, "minimum_quality": args.min_usable, "samples": data.rows},
                indent=2, allow_nan=False), encoding="utf-8")
            counts = {name: int((data.y == index).sum()) for index, name in enumerate(CLASSES)}
            for row in data.rows:
                registry_rows.append({key: row.get(key, "") for key in (
                    "sample_id", "skill", "label", "task_scope", "observed_arm", "source_group", "athlete_id", "session_id", "source_sha256",
                    "partition", "label_verified", "actor_verified", "complete_rep", "athlete_verified")})
            if set(data.y.tolist()) != {0, 1}:
                result = {"status": "needs_data", "reason": "Both correct and faulty examples required"}
            elif args.mode == "locked":
                result = locked(data, configs, args, folder)
            else:
                result = development(data, configs, args, folder)
            result.update(class_counts=counts, linked_groups=len(set(data.groups)), excluded_samples=data.excluded,
                          task_scope=data.rows[0]["task_scope"] if data.rows else None,
                          data_fingerprint=hashlib.sha256(data.X.tobytes() + data.y.tobytes()).hexdigest())
        except (ValueError, OSError, RuntimeError, ImportError) as error:
            result = {"status": "blocked", "reason": str(error), "deployment_approved": False}
            if data is not None:
                result.update(class_counts={name: int((data.y == index).sum()) for index, name in enumerate(CLASSES)},
                              linked_groups=len(set(data.groups)), excluded_samples=data.excluded)
        report["skills"][skill] = result
        (args.out / "report.json").write_text(json.dumps(report, indent=2, allow_nan=False), encoding="utf-8")
    if registry_rows:
        with (args.out / "registry-template.csv").open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(registry_rows[0]))
            writer.writeheader()
            writer.writerows(registry_rows)
    print(f"Report: {args.out / 'report.json'}", flush=True)
    return 2 if any(row["status"] in {"needs_data", "blocked", "development_only"} for row in report["skills"].values()) else 0


if __name__ == "__main__":
    raise SystemExit(main())
