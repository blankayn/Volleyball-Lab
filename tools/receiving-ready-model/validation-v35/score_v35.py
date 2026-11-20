"""Score frozen deployed v29 once on the prediction-blind v35 holdout."""
from __future__ import annotations

import csv
import hashlib
import json
import math
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import tensorflow as tf

HERE = Path(__file__).resolve().parent
MANIFEST = HERE / "sealed_manifest.json"
LABEL_LOCK = HERE / "label_interval_lock.json"
LOCK = HERE / "pre_inference_lock.json"
PROTOCOL = HERE / "scoring_protocol_lock.json"
EXTRACTION = HERE / "extraction_log.json"
AUDIT = HERE / "pre_score_quality_audit.json"
VISUAL = HERE / "pre_score_visual_review.json"
FEATURES = HERE / "sealed_features"
WINDOWS_CSV = HERE / "rolling_window_predictions.csv"
CLIPS_CSV = HERE / "terminal_clip_predictions.csv"
RESULT = HERE / "v35_external_validation_report.json"

REQUIRED = [0, 11, 12, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32]
ANKLES = [27, 28]
MIN_VISIBILITY = 0.40
MIN_RELIABLE_FRACTION = 0.50
WINDOW = 32
MAX_GAP_S = 0.50


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def continuous_runs(indices: np.ndarray, fps: float) -> list[np.ndarray]:
    if not len(indices):
        return []
    split_at = np.where(np.diff(indices) / fps > MAX_GAP_S)[0] + 1
    return [part for part in np.split(indices, split_at) if len(part)]


def resample_finite(values: np.ndarray) -> np.ndarray | None:
    values = np.asarray(values, dtype=float)
    finite = np.flatnonzero(np.isfinite(values))
    if not len(finite):
        return None
    return np.interp(np.arange(len(values), dtype=float), finite, values[finite])


def descriptor(knees: np.ndarray, trunks: np.ndarray) -> np.ndarray | None:
    knees = resample_finite(knees)
    trunks = resample_finite(trunks)
    if knees is None or trunks is None:
        return None
    return np.asarray([
        np.quantile(knees, 0.50), np.quantile(knees, 0.10), np.quantile(knees, 0.90),
        np.quantile(trunks, 0.50), np.quantile(trunks, 0.10), np.quantile(trunks, 0.90),
    ], dtype=np.float32)


def candidate_windows(name: str) -> list[dict]:
    landmark_path = FEATURES / name / f"{name}_landmarks.npz"
    frame_path = FEATURES / name / f"{name}_frames.csv"
    with frame_path.open("r", newline="", encoding="utf-8") as handle:
        frame_table = {int(row["frame"]): row for row in csv.DictReader(handle)}
    with np.load(landmark_path, allow_pickle=True) as data:
        image = np.asarray(data["image"], dtype=float)
        visibility = np.asarray(data["visibility"], dtype=float)
        valid = np.asarray(data["valid"], dtype=bool)
        segment_of_frame = np.asarray(data["segment_of_frame"], dtype=int)
        fps = float(data["fps"])
    selected = np.flatnonzero(valid & (segment_of_frame == 0))
    rows = []
    for run in continuous_runs(selected, fps):
        for offset in range(0, len(run) - WINDOW + 1):
            indices = run[offset:offset + WINDOW]
            window_image = image[indices]
            window_visibility = visibility[indices]
            in_frame = (
                (window_image[:, :, 0] >= 0) & (window_image[:, :, 0] <= 1) &
                (window_image[:, :, 1] >= 0) & (window_image[:, :, 1] <= 1)
            )
            reliable = np.all(
                in_frame[:, REQUIRED] & (window_visibility[:, REQUIRED] >= MIN_VISIBILITY),
                axis=1,
            )
            reliable_fraction = float(np.mean(reliable))
            ankle_visibility = float(np.median(window_visibility[:, ANKLES]))
            def column(key: str) -> np.ndarray:
                values = []
                for index in indices:
                    raw = frame_table[int(index)].get(key, "")
                    try:
                        values.append(float(raw))
                    except (TypeError, ValueError):
                        values.append(float("nan"))
                return np.asarray(values, dtype=float)
            features = descriptor(column("knee_mean"), column("trunk_forward_lean"))
            passes = bool(
                features is not None
                and reliable_fraction >= MIN_RELIABLE_FRACTION
                and ankle_visibility >= MIN_VISIBILITY
            )
            rows.append({
                "start_frame": int(indices[0]),
                "end_frame": int(indices[-1]),
                "start_s": float(indices[0] / fps),
                "end_s": float(indices[-1] / fps),
                "reliable_frame_fraction": reliable_fraction,
                "ankle_visibility_median": ankle_visibility,
                "passes_quality_gate": passes,
                "features": features,
            })
    return rows


class FrozenModel:
    def __init__(self, path: Path):
        self.interpreter = tf.lite.Interpreter(model_path=str(path))
        self.interpreter.allocate_tensors()
        self.input = self.interpreter.get_input_details()[0]
        self.output = self.interpreter.get_output_details()[0]

    def predict(self, features: np.ndarray) -> float:
        self.interpreter.set_tensor(
            self.input["index"], features.reshape(1, -1).astype(self.input["dtype"])
        )
        self.interpreter.invoke()
        return float(self.interpreter.get_tensor(self.output["index"]).reshape(-1)[0])


def decide(probability: float, ok_below: float, fault_at_or_above: float) -> str:
    if probability < ok_below:
        return "ok"
    if probability >= fault_at_or_above:
        return "fault"
    return "uncertain"


def wilson(correct: int, total: int) -> list[float] | None:
    if not total:
        return None
    p = correct / total
    z = 1.959963984540054
    denominator = 1 + z * z / total
    center = (p + z * z / (2 * total)) / denominator
    margin = z * math.sqrt((p * (1 - p) + z * z / (4 * total)) / total) / denominator
    return [max(0.0, center - margin), min(1.0, center + margin)]


def metrics(rows: list[dict], manifest: list[dict]) -> dict:
    retained = [row for row in rows if row["status"] == "scored"]
    decided = [row for row in retained if row["terminal_decision"] != "uncertain"]
    tn = sum(row["label"] == "ok" and row["terminal_decision"] == "ok" for row in decided)
    fp = sum(row["label"] == "ok" and row["terminal_decision"] == "fault" for row in decided)
    fn = sum(row["label"] == "fault" and row["terminal_decision"] == "ok" for row in decided)
    tp = sum(row["label"] == "fault" and row["terminal_decision"] == "fault" for row in decided)
    correct = tn + tp
    total = len(decided)
    ok_recall = tn / (tn + fp) if tn + fp else None
    fault_recall = tp / (tp + fn) if tp + fn else None
    recalls = [value for value in (ok_recall, fault_recall) if value is not None]
    manifest_counts = Counter(row["label"] for row in manifest)
    effective_ok_recall = tn / manifest_counts["ok"] if manifest_counts["ok"] else None
    effective_fault_recall = tp / manifest_counts["fault"] if manifest_counts["fault"] else None
    return {
        "manifest_clips": len(manifest),
        "quality_retained_clips": len(retained),
        "quality_coverage": len(retained) / len(manifest),
        "decided_clips": total,
        "decision_coverage_all_manifest": total / len(manifest),
        "decision_coverage_quality_retained": total / len(retained) if retained else 0.0,
        "correct": correct,
        "conditional_accuracy": correct / total if total else None,
        "conditional_accuracy_wilson_95": wilson(correct, total),
        "balanced_accuracy_decided": sum(recalls) / len(recalls) if recalls else None,
        "fault_recall_decided": fault_recall,
        "ok_recall_decided": ok_recall,
        "effective_fault_recall_all_manifest": effective_fault_recall,
        "effective_ok_recall_all_manifest": effective_ok_recall,
        "confusion_matrix_ok_fault_decided": [[tn, fp], [fn, tp]],
        "quality_abstentions": sum(row["status"] == "quality_abstention" for row in rows),
        "uncertain_after_quality": sum(
            row["status"] == "scored" and row["terminal_decision"] == "uncertain"
            for row in rows
        ),
    }


def main() -> int:
    if RESULT.exists() or WINDOWS_CSV.exists() or CLIPS_CSV.exists():
        raise SystemExit("v35 was already scored; refusing to overwrite")
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    label_lock = json.loads(LABEL_LOCK.read_text(encoding="utf-8"))
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    protocol = json.loads(PROTOCOL.read_text(encoding="utf-8"))
    audit = json.loads(AUDIT.read_text(encoding="utf-8"))
    visual = json.loads(VISUAL.read_text(encoding="utf-8"))
    if digest(MANIFEST) != lock["manifest_sha256"] or digest(MANIFEST) != protocol["manifest_sha256"]:
        raise RuntimeError("Manifest changed after lock")
    if digest(LABEL_LOCK) != lock["label_interval_lock_sha256"]:
        raise RuntimeError("Label/interval lock changed")
    if digest(LOCK) != protocol["pre_inference_lock_sha256"]:
        raise RuntimeError("Pre-inference lock changed")
    if digest(EXTRACTION) != audit["extraction_log_sha256"]:
        raise RuntimeError("Extraction changed after quality audit")
    if digest(VISUAL) != audit["visual_review_sha256"]:
        raise RuntimeError("Visual review changed after quality audit")
    if not audit.get("performed_before_candidate_scoring"):
        raise RuntimeError("Missing pre-score quality audit")
    model_meta = lock["model"]
    if digest(Path(model_meta["path"])) != model_meta["sha256"]:
        raise RuntimeError("Frozen v29 model changed")
    if digest(Path(lock["pose_model"])) != lock["pose_model_sha256"]:
        raise RuntimeError("Frozen pose model changed")

    with Path(label_lock["training_data"]).open("r", newline="", encoding="utf-8") as handle:
        training_sources = {str(row["source_group"]) for row in csv.DictReader(handle)}
    validation_sources = {row["id"] for row in manifest["sources"]}
    overlap = sorted(validation_sources & training_sources)
    if overlap:
        raise RuntimeError(f"Validation/training source overlap: {overlap}")

    policy = lock["selective_policy"]
    ok_below = float(policy["ok_if_probability_below"])
    fault_at_or_above = float(policy["fault_if_probability_at_or_above"])
    model = FrozenModel(Path(model_meta["path"]))
    audit_by_name = {row["name"]: row for row in audit["rows"]}
    visual_by_name = {row["name"]: row for row in visual["rows"]}
    clip_rows = []
    rolling_rows = []

    for segment in manifest["segments"]:
        name = segment["name"]
        windows = candidate_windows(name)
        quality_windows = [row for row in windows if row["passes_quality_gate"]]
        expected = audit_by_name[name]
        if len(windows) != expected["candidate_32_pose_windows"]:
            raise RuntimeError(f"Candidate-window count changed for {name}")
        if len(quality_windows) != expected["quality_passing_32_pose_windows"]:
            raise RuntimeError(f"Quality-window count changed for {name}")
        actual_final_passes = bool(windows and windows[-1]["passes_quality_gate"])
        if actual_final_passes != expected["actual_final_candidate_passes_quality"]:
            raise RuntimeError(f"Terminal quality changed for {name}")
        retained = bool(expected["retained_before_scoring"])
        if not retained:
            clip_rows.append({
                "sample_id": name,
                "source_group": segment["source_id"],
                "label": segment["label"],
                "status": "quality_abstention",
                "abstention_reason": expected["abstention_reason"],
                "candidate_windows": len(windows),
                "quality_windows": len(quality_windows),
                "terminal_start_s": None,
                "terminal_end_s": None,
                "terminal_fault_probability": None,
                "terminal_decision": None,
                "terminal_correct": None,
            })
            continue
        if not visual_by_name[name]["visual_tracks_intended_subject"]:
            raise RuntimeError(f"Retained clip failed visual review: {name}")
        terminal = windows[-1]
        if not terminal["passes_quality_gate"] or terminal["features"] is None:
            raise RuntimeError(f"Retained terminal window is not quality-passing: {name}")
        probability = model.predict(terminal["features"])
        decision = decide(probability, ok_below, fault_at_or_above)
        clip_rows.append({
            "sample_id": name,
            "source_group": segment["source_id"],
            "label": segment["label"],
            "status": "scored",
            "abstention_reason": None,
            "candidate_windows": len(windows),
            "quality_windows": len(quality_windows),
            "terminal_start_s": terminal["start_s"],
            "terminal_end_s": terminal["end_s"],
            "terminal_fault_probability": probability,
            "terminal_decision": decision,
            "terminal_correct": decision == segment["label"] if decision != "uncertain" else None,
        })
        for ordinal, window in enumerate(windows):
            if not window["passes_quality_gate"]:
                continue
            window_probability = model.predict(window["features"])
            window_decision = decide(window_probability, ok_below, fault_at_or_above)
            rolling_rows.append({
                "sample_id": name,
                "source_group": segment["source_id"],
                "label": segment["label"],
                "window_ordinal": ordinal,
                "start_s": window["start_s"],
                "end_s": window["end_s"],
                "fault_probability": window_probability,
                "decision": window_decision,
                "correct": window_decision == segment["label"] if window_decision != "uncertain" else None,
            })

    with CLIPS_CSV.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(clip_rows[0].keys()))
        writer.writeheader()
        writer.writerows(clip_rows)
    with WINDOWS_CSV.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rolling_rows[0].keys()))
        writer.writeheader()
        writer.writerows(rolling_rows)

    terminal_metrics = metrics(clip_rows, manifest["segments"])
    source_rows = []
    for source in sorted(validation_sources):
        group = [row for row in clip_rows if row["source_group"] == source]
        decided = [row for row in group if row["status"] == "scored" and row["terminal_decision"] != "uncertain"]
        source_rows.append({
            "source_group": source,
            "manifest_clips": len(group),
            "quality_retained": sum(row["status"] == "scored" for row in group),
            "decided": len(decided),
            "correct": sum(row["terminal_correct"] is True for row in decided),
            "conditional_accuracy": (
                sum(row["terminal_correct"] is True for row in decided) / len(decided)
                if decided else None
            ),
            "labels": sorted({row["label"] for row in group}),
        })
    result = {
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "status": "fresh_source_disjoint_prediction_blind_external_validation_complete",
        "scope": manifest["scope"],
        "manifest_sha256": digest(MANIFEST),
        "label_interval_lock_sha256": digest(LABEL_LOCK),
        "pre_inference_lock_sha256": digest(LOCK),
        "scoring_protocol_lock_sha256": digest(PROTOCOL),
        "extraction_log_sha256": digest(EXTRACTION),
        "pre_score_quality_audit_sha256": digest(AUDIT),
        "pre_score_visual_review_sha256": digest(VISUAL),
        "model": model_meta,
        "pose_model_sha256": lock["pose_model_sha256"],
        "training_data_sha256": label_lock["training_data_sha256"],
        "validation_training_source_overlap": overlap,
        "policy": policy,
        "primary_unit": protocol["primary_unit"],
        "metrics": terminal_metrics,
        "clips": clip_rows,
        "source_group_analysis": {
            "unique_source_groups": len(validation_sources),
            "fault_source_groups": len({row["source_id"] for row in manifest["segments"] if row["label"] == "fault"}),
            "ok_source_groups": len({row["source_id"] for row in manifest["segments"] if row["label"] == "ok"}),
            "rows": source_rows,
            "note": "Multiple clips from one source are correlated; clip counts do not equal independent source counts.",
        },
        "descriptive_rolling_windows": {
            "quality_passing_windows_scored": len(rolling_rows),
            "correct": sum(row["correct"] is True for row in rolling_rows),
            "wrong": sum(row["correct"] is False for row in rolling_rows),
            "uncertain": sum(row["decision"] == "uncertain" for row in rolling_rows),
            "correct_fraction": (
                sum(row["correct"] is True for row in rolling_rows) / len(rolling_rows)
                if rolling_rows else None
            ),
            "note": "Overlapping rolling windows are correlated and are not independent samples.",
        },
        "artifacts": {
            "terminal_clip_predictions": str(CLIPS_CSV),
            "terminal_clip_predictions_sha256": digest(CLIPS_CSV),
            "rolling_window_predictions": str(WINDOWS_CSV),
            "rolling_window_predictions_sha256": digest(WINDOWS_CSV),
            "overlay_sheet": str(HERE / "v35-overlay-audit-sheet.jpg"),
            "overlay_sheet_sha256": digest(HERE / "v35-overlay-audit-sheet.jpg"),
        },
        "limitations": [
            "Only 14 of 20 manifest clips passed the actual-final app-equivalent pose/visual quality gate.",
            "The retained class balance is uneven because quality filtering kept more fault than OK clips.",
            "The 20 clips come from seven source videos; repeated clips from a source are correlated.",
            "Fault evidence is concentrated in four source groups, including six retained clips from one Popelka video.",
            "Labels use explicit coaching narration/on-screen before-after material plus visual review rather than independent multi-rater annotation.",
            "This validates only too-upright/insufficient-knee-bend forearm-pass posture, not all volleyball skills.",
            "MPJPE is a separate pose-landmark placement metric and is not inferred from classifier accuracy.",
            "v35 is spent after this one frozen evaluation and must not be reused to tune thresholds or retrain a candidate claimed against it.",
        ],
    }
    RESULT.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({
        "metrics": terminal_metrics,
        "source_groups": result["source_group_analysis"],
        "clips": clip_rows,
        "report": str(RESULT),
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
