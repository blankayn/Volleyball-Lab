"""Train the Volleyball Lab receiving-ready classifier with TensorFlow.

This trainer intentionally targets one coaching question only:
"Is the athlete too upright / insufficiently bent in a forearm-pass ready pose?"

The input is the curated v29 descriptor table produced with the same MediaPipe
pose geometry used by the Android app. Evaluation holds out complete video
sources, so frames or clips from one YouTube video never appear on both sides of
a fold.
"""
from __future__ import annotations

import csv
import hashlib
import json
import math
import os
from pathlib import Path

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
os.environ.setdefault("TF_ENABLE_ONEDNN_OPTS", "0")

import numpy as np
import pandas as pd
import tensorflow as tf
from sklearn.metrics import accuracy_score, balanced_accuracy_score, confusion_matrix
from sklearn.model_selection import LeaveOneGroupOut


# Paths are relative to this folder, so the project can be rebuilt on any machine.
# The data and model were copied here on 17 November 2026 from the original
# working folder, Documents\Codex\2026-09-08\...\outputs\volleyball-training.
HERE = Path(__file__).resolve().parent
DATA_ROOT = HERE / "data"
DATASET = DATA_ROOT / "pass-ready-v29-development-descriptors.csv"
DATASET_VERSION = "v29"
V25_DATASET = DATA_ROOT / "pass-ready-v25-development-descriptors.csv"
V27_DATASET = DATA_ROOT / "pass-ready-v27-development-descriptors.csv"
# Running this overwrites model/. Training is deterministic, so a clean run
# should reproduce the shipped file; compare its SHA-256 with the app asset.
OUTPUT_DIR = HERE / "model"
VOLLEYBALL_MPJPE_REPORT = (
    DATA_ROOT / "mpjpe" / "results" / "tum_volleyball_test_mpjpe.json"
)
GENERAL_MPJPE_REPORT = DATA_ROOT / "mpjpe" / "results" / "lsp_test_mpjpe.json"
V29_PRE_UPDATE_RESULT = DATA_ROOT / "acquisition-v29" / "sealed_score.json"

FEATURE_NAMES = [
    "knee_mean_median",
    "knee_mean_q10",
    "knee_mean_q90",
    "trunk_forward_lean_median",
    "trunk_forward_lean_q10",
    "trunk_forward_lean_q90",
]
LABEL_FAULT = 1
L2 = 0.001
LEARNING_RATE = 0.05
STEPS = 2_000
MIN_REQUIRED_ACCURACY = 0.80


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def robust_fit(x: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Return imputation medians, centers, and IQR scales fitted on train only."""
    impute = np.nanmedian(x, axis=0)
    clean = np.where(np.isfinite(x), x, impute)
    center = np.median(clean, axis=0)
    q1 = np.quantile(clean, 0.25, axis=0)
    q3 = np.quantile(clean, 0.75, axis=0)
    scale = np.where((q3 - q1) > 1e-5, q3 - q1, 1.0)
    return impute.astype("float32"), center.astype("float32"), scale.astype("float32")


def robust_apply(
    x: np.ndarray, impute: np.ndarray, center: np.ndarray, scale: np.ndarray
) -> np.ndarray:
    clean = np.where(np.isfinite(x), x, impute)
    return ((clean - center) / scale).astype("float32")


@tf.function(
    input_signature=[
        tf.TensorSpec([None, len(FEATURE_NAMES)], tf.float32),
        tf.TensorSpec([None], tf.float32),
    ]
)
def train_logistic_tensorflow(x: tf.Tensor, y: tf.Tensor) -> tuple[tf.Tensor, tf.Tensor]:
    """Deterministic, class-balanced full-batch TensorFlow training."""
    weights = tf.zeros([len(FEATURE_NAMES)], tf.float32)
    bias = tf.constant(0.0, tf.float32)
    count = tf.cast(tf.size(y), tf.float32)
    positives = tf.maximum(1.0, tf.reduce_sum(y))
    negatives = tf.maximum(1.0, count - positives)
    sample_weights = tf.where(
        y > 0.5,
        count / (2.0 * positives),
        count / (2.0 * negatives),
    )
    denominator = tf.reduce_sum(sample_weights)

    def cond(step: tf.Tensor, _weights: tf.Tensor, _bias: tf.Tensor) -> tf.Tensor:
        return step < STEPS

    def body(
        step: tf.Tensor, current_weights: tf.Tensor, current_bias: tf.Tensor
    ) -> tuple[tf.Tensor, tf.Tensor, tf.Tensor]:
        probability = tf.math.sigmoid(tf.linalg.matvec(x, current_weights) + current_bias)
        error = (probability - y) * sample_weights
        weight_gradient = (
            tf.linalg.matvec(x, error, transpose_a=True) / denominator
            + 2.0 * L2 * current_weights
        )
        bias_gradient = tf.reduce_sum(error) / denominator
        return (
            step + 1,
            current_weights - LEARNING_RATE * weight_gradient,
            current_bias - LEARNING_RATE * bias_gradient,
        )

    _, weights, bias = tf.while_loop(
        cond, body, (tf.constant(0), weights, bias), parallel_iterations=1
    )
    return weights, bias


def fit(x: np.ndarray, y: np.ndarray) -> dict[str, np.ndarray | float]:
    impute, center, scale = robust_fit(x)
    transformed = robust_apply(x, impute, center, scale)
    weights, bias = train_logistic_tensorflow(
        tf.convert_to_tensor(transformed), tf.convert_to_tensor(y.astype("float32"))
    )
    return {
        "impute": impute,
        "center": center,
        "scale": scale,
        "weights": weights.numpy(),
        "bias": float(bias.numpy()),
    }


def predict_probability(x: np.ndarray, trained: dict) -> np.ndarray:
    transformed = robust_apply(x, trained["impute"], trained["center"], trained["scale"])
    logits = transformed @ trained["weights"] + trained["bias"]
    return (1.0 / (1.0 + np.exp(-logits))).astype("float32")


def metrics(y: np.ndarray, predicted: np.ndarray) -> dict:
    matrix = confusion_matrix(y, predicted, labels=[0, 1])
    tn, fp, fn, tp = [int(value) for value in matrix.ravel()]
    total = len(y)
    correct = int(np.sum(y == predicted))
    p = correct / total
    z = 1.959963984540054
    denominator = 1 + z * z / total
    center = (p + z * z / (2 * total)) / denominator
    margin = z * math.sqrt((p * (1 - p) + z * z / (4 * total)) / total) / denominator
    return {
        "samples": total,
        "correct": correct,
        "accuracy": float(accuracy_score(y, predicted)),
        "balanced_accuracy": float(balanced_accuracy_score(y, predicted)),
        "fault_recall": float(tp / (tp + fn)) if tp + fn else None,
        "ok_recall": float(tn / (tn + fp)) if tn + fp else None,
        "confusion_matrix_ok_fault": [[tn, fp], [fn, tp]],
        "accuracy_wilson_95": [max(0.0, center - margin), min(1.0, center + margin)],
    }


def source_separated_evaluation(frame: pd.DataFrame) -> tuple[dict, np.ndarray]:
    x = frame[FEATURE_NAMES].replace([np.inf, -np.inf], np.nan).to_numpy("float32")
    y = (frame["label"] == "fault").to_numpy("int32")
    groups = frame["source_group"].to_numpy()
    probability = np.zeros(len(frame), dtype="float32")
    for train_indices, test_indices in LeaveOneGroupOut().split(x, y, groups):
        trained = fit(x[train_indices], y[train_indices])
        probability[test_indices] = predict_probability(x[test_indices], trained)
    predicted = (probability >= 0.5).astype("int32")
    return metrics(y, predicted), probability


def select_operating_threshold(frame: pd.DataFrame, probability: np.ndarray) -> tuple[float, dict]:
    """Select a coarse threshold on source-held-out development predictions.

    This is an operating-point choice on development evidence, not a fresh-test
    estimate. A coarse 0.05 grid avoids fitting a fragile exact cutoff to 48
    samples. Balanced accuracy is primary; fault recall and accuracy break ties.
    """
    y = (frame["label"] == "fault").to_numpy("int32")
    candidates = np.arange(0.10, 0.91, 0.05)
    ranked = []
    for threshold in candidates:
        result = metrics(y, (probability >= threshold).astype("int32"))
        ranked.append((result["balanced_accuracy"], result["fault_recall"], result["accuracy"], float(threshold), result))
    _, _, _, threshold, result = max(ranked)
    result["threshold"] = threshold
    result["selection_note"] = (
        "Threshold selected on source-held-out development predictions using a coarse 0.05 grid; "
        "requires untouched external validation."
    )
    return threshold, result


def selective_policy_metrics(frame: pd.DataFrame, probability: np.ndarray, ok_below: float = .40, fault_at_or_above: float = .60) -> dict:
    """Evaluate a coach-safe policy that abstains near the decision boundary."""
    y = (frame["label"] == "fault").to_numpy("int32")
    retained = (probability < ok_below) | (probability >= fault_at_or_above)
    retained_y = y[retained]
    retained_prediction = (probability[retained] >= fault_at_or_above).astype("int32")
    result = metrics(retained_y, retained_prediction)
    result.update({
        "ok_below": ok_below,
        "fault_at_or_above": fault_at_or_above,
        "abstain_from_inclusive": ok_below,
        "abstain_to_exclusive": fault_at_or_above,
        "abstentions": int((~retained).sum()),
        "coverage": float(retained.mean()),
        "total_development_samples": int(len(frame)),
        "policy_note": "Return uncertain/request another clean stance inside the abstention band; thresholds selected on development evidence only.",
    })
    return result


def historical_v26_evaluation(v25: pd.DataFrame, v27: pd.DataFrame) -> dict:
    """Reproduce the old untouched split; it is historical and already spent."""
    test = v27[~v27["sample_id"].isin(set(v25["sample_id"]))].copy()
    x_train = v25[FEATURE_NAMES].replace([np.inf, -np.inf], np.nan).to_numpy("float32")
    y_train = (v25["label"] == "fault").to_numpy("int32")
    x_test = test[FEATURE_NAMES].replace([np.inf, -np.inf], np.nan).to_numpy("float32")
    y_test = (test["label"] == "fault").to_numpy("int32")
    trained = fit(x_train, y_train)
    probability = predict_probability(x_test, trained)
    result = metrics(y_test, (probability >= 0.5).astype("int32"))
    result["note"] = "Retrospective only: v26 labels/results were already inspected before this trainer."
    result["sample_ids"] = test["sample_id"].tolist()
    return result


def export_models(trained: dict, all_x: np.ndarray) -> tuple[Path, Path, float]:
    # Absorb robust scaling into a raw-input Dense layer for a tiny app model.
    raw_weights = trained["weights"] / trained["scale"]
    raw_bias = trained["bias"] - float(np.sum(raw_weights * trained["center"]))
    model = tf.keras.Sequential(
        [
            tf.keras.Input(shape=(len(FEATURE_NAMES),), name="ready_posture_features"),
            tf.keras.layers.Dense(1, activation="sigmoid", name="fault_probability"),
        ],
        name="receiving_ready_posture",
    )
    model.layers[0].set_weights(
        [raw_weights.reshape((-1, 1)).astype("float32"), np.array([raw_bias], "float32")]
    )
    keras_path = OUTPUT_DIR / "receiving_ready_posture.keras"
    tflite_path = OUTPUT_DIR / "receiving_ready_posture.tflite"
    model.save(keras_path)
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_path.write_bytes(converter.convert())

    finite_x = np.where(np.isfinite(all_x), all_x, trained["impute"]).astype("float32")
    keras_probability = model(finite_x, training=False).numpy().reshape(-1)
    expected_probability = predict_probability(all_x, trained)
    max_difference = float(np.max(np.abs(keras_probability - expected_probability)))
    if max_difference > 1e-5:
        raise RuntimeError(f"Exported model mismatch: {max_difference}")
    return keras_path, tflite_path, max_difference


def main() -> int:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    frame = pd.read_csv(DATASET)
    v25 = pd.read_csv(V25_DATASET)
    v27 = pd.read_csv(V27_DATASET)
    required = {"sample_id", "label", "source_group", *FEATURE_NAMES}
    missing = sorted(required - set(frame.columns))
    if missing:
        raise SystemExit(f"Dataset is missing columns: {missing}")
    if set(frame["label"]) != {"ok", "fault"}:
        raise SystemExit("Expected exactly the labels 'ok' and 'fault'.")

    evaluation, probability = source_separated_evaluation(frame)
    operating_threshold, operating_evaluation = select_operating_threshold(frame, probability)
    selective_evaluation = selective_policy_metrics(frame, probability)
    historical = historical_v26_evaluation(v25, v27)
    all_x = frame[FEATURE_NAMES].replace([np.inf, -np.inf], np.nan).to_numpy("float32")
    all_y = (frame["label"] == "fault").to_numpy("int32")
    trained = fit(all_x, all_y)
    full_fit_probability = predict_probability(all_x, trained)
    full_fit_metrics = metrics(all_y, (full_fit_probability >= 0.5).astype("int32"))
    keras_path, tflite_path, export_difference = export_models(trained, all_x)

    pose_quality = None
    if VOLLEYBALL_MPJPE_REPORT.exists():
        mpjpe_payload = json.loads(
            VOLLEYBALL_MPJPE_REPORT.read_text(encoding="utf-8")
        )
        visibility_gated = mpjpe_payload.get("per_joint_visibility_gated_metrics")
        if visibility_gated is None:
            visibility_gated = mpjpe_payload["app_quality_gated_metrics"]
        pose_quality = {
            "report": str(VOLLEYBALL_MPJPE_REPORT),
            "report_sha256": sha256(VOLLEYBALL_MPJPE_REPORT),
            "volleyball_specific": True,
            "dataset": mpjpe_payload["dataset"],
            "metric": mpjpe_payload["metric"],
            "test_images": mpjpe_payload["selected_images"],
            "image_detection_rate": mpjpe_payload["image_detection_rate"],
            "detection_inclusive_reliable_joint_coverage": visibility_gated[
                "detection_inclusive_reliable_joint_coverage"
            ],
            "mpjpe_pixels": mpjpe_payload["all_detected_joint_metrics"]["mpjpe_pixels"],
            "per_joint_visibility_gated_mpjpe_pixels": visibility_gated["mpjpe_pixels"],
            "pck_at_0_2_torso": mpjpe_payload["all_detected_joint_metrics"]["pck_at_0_2_torso"],
            "note": (
                "This still-image metric filters individual joints by visibility and bounds. "
                "It does not reproduce the app's 32-frame quality gate. Pose placement quality "
                "is separate from proper/fault classification accuracy."
            ),
        }

    general_pose_quality = None
    if GENERAL_MPJPE_REPORT.exists():
        general_payload = json.loads(
            GENERAL_MPJPE_REPORT.read_text(encoding="utf-8")
        )
        general_pose_quality = {
            "report": str(GENERAL_MPJPE_REPORT),
            "report_sha256": sha256(GENERAL_MPJPE_REPORT),
            "volleyball_specific": False,
            "dataset": general_payload["dataset"],
            "metric": general_payload["metric"],
            "test_images": general_payload["selected_images"],
            "image_detection_rate": general_payload["image_detection_rate"],
            "mpjpe_pixels": general_payload["all_detected_joint_metrics"]["mpjpe_pixels"],
            "note": "Mixed-sport pose sanity check; it is not volleyball evidence.",
        }

    training_copy = OUTPUT_DIR / f"ready_posture_training_{DATASET_VERSION}.csv"
    frame.to_csv(training_copy, index=False)
    predictions_path = OUTPUT_DIR / "source_separated_predictions.csv"
    with predictions_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(
            ["sample_id", "source_group", "label", "fault_probability", "prediction", "correct"]
        )
        for row, value in zip(frame.itertuples(index=False), probability):
            predicted_label = "fault" if value >= 0.5 else "ok"
            writer.writerow(
                [
                    row.sample_id,
                    row.source_group,
                    row.label,
                    f"{float(value):.8f}",
                    predicted_label,
                    predicted_label == row.label,
                ]
            )

    pre_update_v29 = json.loads(V29_PRE_UPDATE_RESULT.read_text(encoding="utf-8"))
    deployment_metadata_path = OUTPUT_DIR / "deployment_metadata.json"
    deployment_metadata_path.write_text(json.dumps({
        "scope": "insufficient knee bend / too-upright forearm-pass ready posture",
        "features_in_order": FEATURE_NAMES,
        "single_cutoff": operating_threshold,
        "recommended_selective_policy": {
            "ok_if_probability_below": selective_evaluation["ok_below"],
            "fault_if_probability_at_or_above": selective_evaluation["fault_at_or_above"],
            "otherwise": "uncertain_request_another_clean_stance",
        },
        "threshold_status": "development-selected; external validation required",
        "tflite": str(tflite_path),
        "tflite_sha256": sha256(tflite_path),
    }, indent=2), encoding="utf-8")
    report = {
        "model": "TensorFlow class-balanced logistic receiving-ready classifier",
        "scope": "insufficient knee bend / too-upright forearm-pass ready posture",
        "dataset": str(DATASET),
        "dataset_sha256": sha256(DATASET),
        "samples": int(len(frame)),
        "video_sources": int(frame["source_group"].nunique()),
        "class_counts": {key: int(value) for key, value in frame["label"].value_counts().items()},
        "features_in_order": FEATURE_NAMES,
        "evaluation_protocol": "leave-one-video-source-out; preprocessing fitted inside each fold",
        "source_separated_metrics": evaluation,
        "source_separated_operating_metrics": operating_evaluation,
        "source_separated_selective_policy_metrics": selective_evaluation,
        "full_fit_metrics": full_fit_metrics,
        "historical_v26_metrics": historical,
        "pre_update_v29_paired_check": {
            "report": str(V29_PRE_UPDATE_RESULT),
            "report_sha256": sha256(V29_PRE_UPDATE_RESULT),
            "candidate_model_sha256": pre_update_v29["candidate_model_sha256"],
            "samples_scored": pre_update_v29["samples_scored"],
            "correct": pre_update_v29["correct"],
            "accuracy": pre_update_v29["accuracy"],
            "rows": pre_update_v29["rows"],
            "abstentions": pre_update_v29["abstentions"],
            "note": "This check was sealed and scored before v29 examples were added to development; it is now spent.",
        },
        "target_accuracy": MIN_REQUIRED_ACCURACY,
        "target_met": evaluation["accuracy"] >= MIN_REQUIRED_ACCURACY,
        "requested_accuracy_target": 1.0,
        "requested_accuracy_target_met": evaluation["accuracy"] >= 1.0,
        "pose_quality_mpjpe": pose_quality,
        "general_pose_sanity_check": general_pose_quality,
        "final_training": {
            "framework": f"TensorFlow {tf.__version__}",
            "optimizer": "deterministic full-batch gradient descent",
            "steps": STEPS,
            "learning_rate": LEARNING_RATE,
            "l2": L2,
            "class_balanced": True,
            "impute": trained["impute"].tolist(),
            "center": trained["center"].tolist(),
            "scale": trained["scale"].tolist(),
            "scaled_weights": trained["weights"].tolist(),
            "scaled_bias": trained["bias"],
        },
        "artifacts": {
            "keras": str(keras_path),
            "keras_sha256": sha256(keras_path),
            "tflite": str(tflite_path),
            "tflite_sha256": sha256(tflite_path),
            "training_copy": str(training_copy),
            "predictions": str(predictions_path),
            "deployment_metadata": str(deployment_metadata_path),
            "deployment_metadata_sha256": sha256(deployment_metadata_path),
            "export_max_probability_difference": export_difference,
        },
        "limitations": [
            (
                f"Development evidence has only {len(frame)} clips and "
                f"{int((frame['label'] == 'fault').sum())} fault clips."
            ),
            "The historical v26 set has already been inspected and is no longer a fresh test.",
            "The v29 paired source was scored before training but is now spent and cannot support a post-update generalization claim.",
            "A new untouched, source-disjoint validation set is required for a final real-world claim.",
        ],
    }
    report_path = OUTPUT_DIR / "training_report.json"
    report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    summary_path = OUTPUT_DIR / "training_result.txt"
    summary_path.write_text(
        "\n".join(
            [
                "Volleyball Lab TensorFlow receiving-ready model",
                f"Source-separated accuracy at fixed 0.50: {evaluation['accuracy'] * 100:.2f}% ({evaluation['correct']}/{evaluation['samples']})",
                f"Development-selected operating threshold: {operating_threshold:.2f}",
                f"Source-separated operating accuracy: {operating_evaluation['accuracy'] * 100:.2f}% ({operating_evaluation['correct']}/{operating_evaluation['samples']})",
                f"Operating balanced accuracy: {operating_evaluation['balanced_accuracy'] * 100:.2f}%",
                f"Operating fault recall: {operating_evaluation['fault_recall'] * 100:.2f}%",
                f"Operating OK recall: {operating_evaluation['ok_recall'] * 100:.2f}%",
                f"Coach-safe selective accuracy: {selective_evaluation['accuracy'] * 100:.2f}% ({selective_evaluation['correct']}/{selective_evaluation['samples']})",
                f"Selective coverage: {selective_evaluation['coverage'] * 100:.2f}% ({selective_evaluation['abstentions']} uncertain)",
                f"Historical v26 accuracy: {historical['accuracy'] * 100:.2f}% ({historical['correct']}/{historical['samples']})",
                f"80% target met: {report['target_met']}",
                f"Requested 100% unseen-source target met: {report['requested_accuracy_target_met']}",
                (
                    f"Volleyball pose MPJPE: {pose_quality['mpjpe_pixels']:.2f}px; "
                    "per-joint visibility-gated "
                    f"{pose_quality['per_joint_visibility_gated_mpjpe_pixels']:.2f}px"
                    if pose_quality
                    else "Volleyball pose MPJPE: not evaluated"
                ),
                "Fresh external test still required before claiming production accuracy.",
            ]
        )
        + "\n",
        encoding="utf-8",
    )
    print(summary_path.read_text(encoding="utf-8"))
    print(f"Model: {tflite_path}")
    print(f"Report: {report_path}")
    return 0 if report["target_met"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
