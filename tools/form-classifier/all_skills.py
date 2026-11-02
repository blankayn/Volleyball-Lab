"""Audit, extract, and train separate form models for all five volleyball skills.

Exports are development candidates, never automatically installed in Android.
Exit 2 means at least one skill still needs data; exit 1 means a processing error.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
from collections import Counter
from pathlib import Path
import subprocess
import sys

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
SKILLS = ("serve", "pass", "set", "spike", "block")
LABELS = {"ok", "fault"}


def read_manifest(path: Path) -> list[dict]:
    """Only reviewed, pre-cut single-form clips may supply ground-truth labels."""
    with path.open(encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        required = {"video", "skill", "label", "source_group", "reviewer"}
        if not required.issubset(reader.fieldnames or []):
            raise ValueError(f"Manifest needs columns: {', '.join(sorted(required))}")
        rows = list(reader)
    if not rows:
        raise ValueError("Manifest contains no reviewed clips")
    result, seen = [], set()
    content_labels = {}
    for number, row in enumerate(rows, 2):
        row = {key: (row.get(key) or "").strip() for key in required}
        if not all(row.values()):
            raise ValueError(f"Row {number}: every required field must be filled")
        if row["skill"] not in SKILLS or row["label"] not in LABELS:
            raise ValueError(f"Row {number}: use a canonical skill and ok/fault label")
        video = (path.parent / row["video"]).resolve()
        if not video.is_file():
            raise ValueError(f"Row {number}: video does not exist: {video}")
        if video in seen:
            raise ValueError(f"Row {number}: duplicate video: {video}")
        seen.add(video)
        digest = hashlib.sha256(video.read_bytes()).hexdigest()
        # Renaming the same video must not manufacture an independent source.
        if digest in content_labels:
            raise ValueError(f"Row {number}: duplicate video content")
        content_labels[digest] = row
        result.append({**row, "video": str(video), "sha256": digest})
    return result


def assess(X, y, groups, feature_names) -> dict:
    counts = Counter(y.tolist())
    sources = sorted(set(groups.tolist()))
    reasons = []
    if not len(X):
        reasons.append("No eligible labelled segments")
    else:
        if X.ndim != 3 or X.shape[1] != 32 or X.shape[2] != len(feature_names):
            reasons.append("Expected 32-frame sequences with named features")
        elif not np.isfinite(X).any():
            reasons.append("No features have finite observations")
        if set(counts) != LABELS:
            reasons.append("Both ok and fault form labels are required; action labels are not form labels")
        if len(sources) < 3:
            reasons.append("Need at least three independent source groups for development evaluation")
        for label in sorted(LABELS):
            if len(set(groups[y == label].tolist())) < 2:
                reasons.append(f"{label} needs at least two source groups so every training fold retains both labels")
    return {
        "status": "needs_data" if reasons else "ready_for_development_training",
        "samples": len(y), "class_counts": dict(counts), "source_groups": sources,
        "reasons": reasons, "accuracy": None, "deployment_approved": False,
    }


def run(script: Path, *arguments: str) -> None:
    subprocess.run([sys.executable, str(script), *map(str, arguments)], check=True)


def verify_export(folder: Path, X: np.ndarray) -> None:
    """Run the exported model using its exact published normalization contract."""
    import tensorflow as tf

    metadata = json.loads((folder / "form_classifier.json").read_text(encoding="utf-8"))
    model = folder / "form_classifier.tflite"
    if hashlib.sha256(model.read_bytes()).hexdigest() != metadata["model_sha256"]:
        raise ValueError("Export checksum mismatch")
    mean = np.asarray(metadata["normalisation"]["mean"])
    std = np.asarray(metadata["normalisation"]["std"])
    sample = np.where(np.isfinite(X[:1]), (X[:1] - mean) / std, 0).astype(np.float32)
    for name in metadata["disabled_features"]:
        sample[..., metadata["feature_names"].index(name)] = 0
    interpreter = tf.lite.Interpreter(model_path=str(model))
    interpreter.allocate_tensors()
    interpreter.set_tensor(interpreter.get_input_details()[0]["index"], sample)
    interpreter.invoke()
    prediction = interpreter.get_tensor(interpreter.get_output_details()[0]["index"])
    if prediction.shape != (1, 2) or not np.isfinite(prediction).all():
        raise ValueError("Export did not produce two finite class probabilities")
    if (prediction < 0).any() or (prediction > 1).any() or not np.allclose(prediction.sum(), 1, atol=1e-4):
        raise ValueError("Export returned invalid probabilities")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=ROOT / "tools/pose-pipeline/data")
    parser.add_argument("--out", type=Path, default=ROOT / "outputs/all-skills-ml")
    parser.add_argument("--manifest", type=Path, help="Extract reviewed pre-cut clips first")
    parser.add_argument("--train", action="store_true", help="Evaluate and export eligible skills")
    parser.add_argument("--development-only-fit", action="store_true",
                        help="Allow a two-class fit with too few sources; never report accuracy or approve deployment")
    parser.add_argument("--epochs", type=int, default=120)
    parser.add_argument("--min-usable", type=float, default=0.5)
    parser.add_argument("--quality-field", choices=("usable_frac", "feature_usable_frac"), default="usable_frac")
    args = parser.parse_args()
    if args.epochs < 1 or not 0 <= args.min_usable <= 1:
        parser.error("epochs must be positive and min-usable must be between 0 and 1")
    args.out.mkdir(parents=True, exist_ok=True)
    report = {"scope": "Separate form-demonstration sequence classifiers; not complete skill evaluation or the deployed receiving-ready model",
              "deployment_approved": False, "overall_accuracy": None, "skills": {}}
    provenance_path = args.data / "provenance.json"
    if provenance_path.is_file():
        provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
        report["label_origin"] = provenance.get("label_origin", "unspecified")
        report["coach_verified"] = provenance.get("coach_verified", False)
        report["quality_profile"] = provenance.get("quality_profile", "legacy-all33-visibility")
    report["min_usable"] = args.min_usable
    report["quality_field"] = args.quality_field
    destination = args.out / "status.json"

    def save():
        destination.write_text(json.dumps(report, indent=2, allow_nan=False), encoding="utf-8")

    save()
    try:
        # Validate the entire manifest before starting extraction.
        reviewed_directories = []
        if args.manifest:
            rows = read_manifest(args.manifest.resolve())
            for row in rows:
                # Stable names prevent clips with identical basenames colliding.
                clip_dir = args.data / "reviewed" / row["sha256"]
                reviewed_directories.append(clip_dir)
                run(ROOT / "tools/pose-pipeline/extract.py", row["video"], "--out", clip_dir,
                    "--skill", row["skill"], "--label", row["label"],
                    "--source-group", row["source_group"])
                extraction = json.loads((clip_dir / "manifest.json").read_text(encoding="utf-8"))
                if extraction["totals"]["failed"] or extraction["totals"]["clips"] != 1:
                    raise ValueError(f"Extraction failed for {row['video']}")
            (args.out / "reviewed-manifest.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")

        import train

        # Each extraction directory is loaded independently; never pool skills.
        directories = reviewed_directories if args.manifest else [args.data]
        for skill in SKILLS:
            try:
                parts = [train.load(directory, skill, args.min_usable, args.quality_field) for directory in directories]
                parts = [part for part in parts if len(part[0])]
                if parts:
                    names = parts[0][3]
                    if any(part[3] != names for part in parts):
                        raise ValueError("Feature order differs between extraction directories")
                    X, y, groups = (np.concatenate([part[i] for part in parts]) for i in range(3))
                else:
                    X, y, groups, names = np.empty(0), np.array([], dtype=str), np.array([], dtype=str), []
                status = assess(X, y, groups, names)
                report["skills"][skill] = status
                unvalidated = bool(status["reasons"]) and args.development_only_fit and (
                    set(y.tolist()) == LABELS and X.ndim == 3 and X.shape[1] == 32 and
                    X.shape[2] == len(names) and np.isfinite(X).any() and min(Counter(y.tolist()).values()) >= 2
                )
                if args.train and (not status["reasons"] or unvalidated):
                    folder = args.out / skill
                    folder.mkdir(exist_ok=True)
                    # Both evaluation and final fit consume the same isolated snapshot.
                    snapshot = folder / "data"
                    snapshot.mkdir(exist_ok=True)
                    np.savez_compressed(snapshot / "reviewed_sequences.npz", schema_version=train.SCHEMA_VERSION,
                        sequences=X, labels=y, skill=np.repeat(skill, len(y)), source_group=groups,
                        feature_names=names, too_short=np.zeros(len(y), dtype=bool), usable_frac=np.ones(len(y)),
                        feature_usable_frac=np.ones(len(y)))
                    evaluation = None
                    if not unvalidated:
                        run(Path(__file__).with_name("evaluate_loso.py"), "--data", snapshot,
                            "--skill", skill, "--epochs", args.epochs, "--quality-field", args.quality_field,
                            "--out", folder / "evaluation.json")
                        evaluation = json.loads((folder / "evaluation.json").read_text(encoding="utf-8"))
                        if evaluation["folds_evaluated"] != len(status["source_groups"]):
                            raise ValueError("Evaluation skipped a source fold")
                    run(Path(__file__).with_name("train.py"), "--data", snapshot, "--skill", skill,
                        "--epochs", args.epochs, "--quality-field", args.quality_field, "--train-all", "--out", folder)
                    verify_export(folder, X)
                    if "label_origin" in report:
                        metadata_path = folder / "form_classifier.json"
                        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
                        metadata["metrics"].update(label_origin=report["label_origin"],
                            coach_verified=report["coach_verified"], min_usable=args.min_usable,
                            quality_profile=report["quality_profile"])
                        metadata_path.write_text(json.dumps(metadata, indent=2, allow_nan=False), encoding="utf-8")
                    status.update(status="unvalidated_candidate" if unvalidated else "development_candidate",
                                  accuracy=None if unvalidated else evaluation["aggregate_accuracy"],
                                  evaluation=None if unvalidated else str(folder / "evaluation.json"), export_verified=True)
            except (ValueError, OSError, subprocess.CalledProcessError, SystemExit) as error:
                report["skills"][skill] = {"status": "error", "reason": str(error), "accuracy": None}
            save()
            print(f"{skill}: {report['skills'][skill]['status']}", flush=True)
    except (ValueError, OSError, subprocess.CalledProcessError, ImportError) as error:
        report["error"] = str(error)
        save()
        print(str(error), file=sys.stderr)
        return 1
    save()
    print(f"Report: {destination}")
    statuses = [s["status"] for s in report["skills"].values()]
    return 1 if "error" in statuses else 2 if any(s in statuses for s in ("needs_data", "unvalidated_candidate")) else 0


if __name__ == "__main__":
    raise SystemExit(main())
