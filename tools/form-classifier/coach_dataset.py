"""Auditable pose-sequence ingestion and linked player/video split identity."""
from __future__ import annotations

import csv
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import numpy as np

from evaluation import connected_groups

SKILLS = ("serve", "pass", "set", "spike", "block")
CLASSES = ["fault", "ok"]


@dataclass
class Dataset:
    X: np.ndarray
    y: np.ndarray
    feature_names: list[str]
    rows: list[dict]
    groups: np.ndarray
    excluded: int


def read_registry(path: Path | None) -> dict:
    if path is None:
        return {}
    with path.open(encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    result = {}
    for row in rows:
        identifier = (row.get("sample_id") or "").strip()
        if not identifier or identifier in result:
            raise ValueError("Registry requires unique nonempty sample_id values")
        result[identifier] = {key: (value or "").strip() for key, value in row.items() if key}
    return result


def load_skill(directory: Path, skill: str, registry: dict, quality_field: str, minimum: float) -> Dataset:
    if skill not in SKILLS or not 0 < minimum <= 1:
        raise ValueError("Unknown skill or invalid positive quality threshold")
    X, y, rows, names, excluded = [], [], [], [], 0
    for path in sorted(directory.glob("*_sequences.npz")):
        with np.load(path, allow_pickle=True) as bundle:
            seq = np.asarray(bundle["sequences"], dtype=np.float32)
            labels = np.asarray(bundle["labels"]).astype(str)
            tags = np.asarray(bundle["skill"]).astype(str)
            if not (tags == skill).any():
                continue
            if int(bundle["schema_version"]) != 2:
                raise ValueError(f"{path.name}: regenerate world-space schema-v2 features")
            feature_names = bundle["feature_names"].astype(str).tolist()
            if seq.ndim != 3 or seq.shape[1] != 32 or seq.shape[2] != len(feature_names):
                raise ValueError(f"{path.name}: expected 32 frames and named feature columns")
            if not names:
                names = feature_names
            if names != feature_names:
                raise ValueError("Feature order differs between source bundles")
            count = len(seq)
            if quality_field not in bundle or "too_short" not in bundle:
                raise ValueError(f"{path.name}: required quality/length metadata missing")
            quality = np.asarray(bundle[quality_field], dtype=float)
            short = np.asarray(bundle["too_short"], dtype=bool)
            ids = bundle["segments"] if "segments" in bundle else np.arange(count)
            sources = np.asarray(bundle["source_group"]).astype(str) if "source_group" in bundle else np.repeat(path.stem, count)
            if sources.ndim == 0:
                sources = np.repeat(sources, count)
            hashes = np.asarray(bundle["source_sha256"]).astype(str) if "source_sha256" in bundle else np.repeat("", count)
            if hashes.ndim == 0:
                hashes = np.repeat(hashes, count)
            scopes = np.asarray(bundle["task_scope"]).astype(str) if "task_scope" in bundle else np.repeat("unspecified_form", count)
            if scopes.ndim == 0:
                scopes = np.repeat(scopes, count)
            arms = np.asarray(bundle["observed_arm"]).astype(str) if "observed_arm" in bundle else np.repeat("not_specified", count)
            if arms.ndim == 0:
                arms = np.repeat(arms, count)
            if any(np.asarray(value).shape != (count,) for value in (labels, tags, quality, short, ids, sources, hashes, scopes, arms)):
                raise ValueError(f"{path.name}: segment metadata dimensions disagree")
            for index in np.flatnonzero(tags == skill):
                if labels[index] == "unlabelled" or short[index] or not np.isfinite(quality[index]) or quality[index] < minimum:
                    excluded += 1
                    continue
                if labels[index] not in CLASSES:
                    raise ValueError(f"{path.name}: action labels cannot substitute for form labels")
                identifier = f"{path.stem.removesuffix('_sequences')}:{ids[index]}"
                recorded = registry.get(identifier, {})
                if recorded.get("source_group") and recorded["source_group"] != sources[index]:
                    raise ValueError(f"{identifier}: registry changes the original source group")
                if recorded.get("skill") and recorded["skill"] != skill:
                    raise ValueError(f"{identifier}: registry skill disagrees with extraction")
                if recorded.get("label") and recorded["label"] != labels[index]:
                    raise ValueError(f"{identifier}: re-extract after correcting a label")
                extracted_hash = str(hashes[index])
                registry_hash = recorded.get("source_sha256", "")
                if extracted_hash and registry_hash and extracted_hash != registry_hash:
                    raise ValueError(f"{identifier}: registry changes the original source hash")
                source_hash = extracted_hash or registry_hash
                if source_hash and not re.fullmatch(r"[a-f0-9]{64}", source_hash):
                    raise ValueError(f"{identifier}: invalid original-source SHA-256")
                scope = str(scopes[index])
                if not re.fullmatch(r"[a-z][a-z0-9_]{0,79}", scope):
                    raise ValueError(f"{identifier}: invalid task scope")
                if recorded.get("task_scope") and recorded["task_scope"] != scope:
                    raise ValueError(f"{identifier}: registry changes the original task scope")
                arm = str(arms[index])
                if arm not in {"left", "right", "not_specified"}:
                    raise ValueError(f"{identifier}: invalid observed arm")
                if recorded.get("observed_arm") and recorded["observed_arm"] != arm:
                    raise ValueError(f"{identifier}: registry changes the observed arm")
                sequence = np.where(np.isfinite(seq[index]), seq[index], np.nan).astype("<f4")
                if "observed_frame" in feature_names:
                    mask_index = feature_names.index("observed_frame")
                    mask = sequence[:, mask_index]
                    measurements = np.delete(sequence, mask_index, axis=1)
                    if (not np.isin(mask, [0., 1.]).all()
                            or not np.isfinite(measurements[mask == 1]).all()
                            or not np.isnan(measurements[mask == 0]).all()):
                        raise ValueError(f"{identifier}: observation mask disagrees with measured values")
                row = {**recorded, "sample_id": identifier, "source_group": str(sources[index]),
                       "feature_quality_profile": str(bundle["feature_quality_profile"]) if "feature_quality_profile" in bundle else "unrecorded",
                       "feature_recipe": str(bundle["feature_recipe"]) if "feature_recipe" in bundle else "legacy_pose_features_v2",
                       "pose_configuration": str(bundle["pose_configuration"]) if "pose_configuration" in bundle else "{}",
                       "observed_arm": arm,
                       "source_sha256": source_hash,
                       "task_scope": scope,
                       "skill": skill, "label": str(labels[index]), "quality": float(quality[index]),
                       "sequence_sha256": hashlib.sha256(sequence.tobytes()).hexdigest()}
                rows.append(row)
                X.append(seq[index])
                y.append(CLASSES.index(labels[index]))
    if len({row["sample_id"] for row in rows}) != len(rows):
        raise ValueError("Duplicate sample identifiers")
    if len({row["task_scope"] for row in rows}) > 1:
        raise ValueError("Mixed task scopes: keep narrow coaching tasks in separate datasets")
    if len({(row['feature_recipe'], row['feature_quality_profile']) for row in rows}) > 1:
        raise ValueError('Mixed feature recipes or quality profiles: regenerate one consistent dataset')
    configurations = set()
    for row in rows:
        configuration = json.loads(row["pose_configuration"])
        if not isinstance(configuration, dict):
            raise ValueError("Pose configuration must be a JSON object")
        row["pose_configuration"] = json.dumps(configuration, sort_keys=True, separators=(",", ":"))
        configurations.add(row["pose_configuration"])
    if len(configurations) > 1:
        raise ValueError("Mixed pose configurations: re-extract using one detector configuration")
    return Dataset(np.asarray(X, dtype=np.float32), np.asarray(y, dtype=int), names, rows,
                   connected_groups(rows), excluded)


def locked_partitions(data: Dataset) -> dict[str, np.ndarray]:
    from evaluation import assert_disjoint

    roles = ("train", "tune", "calibration", "test")
    for row in data.rows:
        if row.get("partition") not in roles:
            raise ValueError("Locked evaluation requires train/tune/calibration/test roles for every sample")
        if not row.get("athlete_id") or row["athlete_id"].lower() in {"unknown", "unverified", "none"}:
            raise ValueError("Unseen-player evaluation requires known athlete identities")
        for flag in ("label_verified", "actor_verified", "complete_rep", "athlete_verified"):
            if row.get(flag, "").lower() != "true":
                raise ValueError(f"Locked evaluation requires independently checked {flag}")
    partitions = {role: np.array([i for i, row in enumerate(data.rows) if row["partition"] == role], dtype=int) for role in roles}
    assert_disjoint(data.groups, *partitions.values())
    scopes = {row.get("task_scope", "unspecified_form") for row in data.rows}
    if len(scopes) != 1 or "unspecified_form" in scopes or any(
            not isinstance(scope, str) or not re.fullmatch(r"[a-z][a-z0-9_]{0,79}", scope) for scope in scopes):
        raise ValueError("Locked evaluation requires an explicit consistent task scope in the feature bundles")
    for role, indices in partitions.items():
        if set(data.y[indices].tolist()) != {0, 1}:
            raise ValueError(f"{role} needs both correct and faulty demonstrations")
    if any((data.y[partitions["calibration"]] == label).sum() < 10 for label in range(2)):
        raise ValueError("Calibration needs at least ten independent reviewed samples per class")
    if len(set(data.groups[partitions["calibration"]])) < 3 or len(set(data.groups[partitions["test"]])) < 3:
        raise ValueError("Calibration and test each need at least three linked player/source groups")
    return partitions
