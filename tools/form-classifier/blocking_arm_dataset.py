"""Build a narrowly scoped blocking arm-reach/hold dataset from reviewed clips.

Only the fixed visible arm and torso anchors establish quality. Lower-body coordinates never
enter this task. Unobserved values remain NaN with an explicit observation mask;
nearest-frame temporal sampling never invents observations across occlusion.
This does not label a whole block, ball contact, jump, footwork or landing.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "pose-pipeline"))
import features

SCOPE = "block_visible_arm_reach_hold"
PROFILE = "blocking-visible-arm7-v1-visibility0.5-image-bounds-nearest-mask"
ANCHORS = (0, 11, 12, 23, 24)
ARMS = {"left": (11, 13, 15, 23), "right": (12, 14, 16, 24)}
NAMES = ("visible_elbow_angle", "visible_shoulder_angle", "visible_wrist_above_shoulder_m",
         "visible_wrist_above_nose_m", "visible_wrist_forward_m", "visible_wrist_rise_image", "observed_frame")


def reliable_frames(image, world, visibility, valid, arm="left"):
    image, world, visibility, valid = map(np.asarray, (image, world, visibility, valid))
    count = len(valid)
    if image.shape != (count, 33, 2) or world.shape != (count, 33, 3) or visibility.shape != (count, 33):
        raise ValueError("Expected aligned 33-landmark arrays")
    if arm not in ARMS:
        raise ValueError("Unknown observed arm")
    _, elbow, wrist, _ = ARMS[arm]
    required = (*ANCHORS, elbow, wrist)
    points = image[:, required]
    return (valid.astype(bool) & np.isfinite(points).all((1, 2))
            & ((points >= 0) & (points <= 1)).all((1, 2))
            & np.isfinite(world[:, required]).all((1, 2))
            & np.isfinite(visibility[:, required]).all(1)
            & (visibility[:, required] >= .5).all(1))


def choose_arm(image, world, visibility, valid):
    # One decision per complete arm cycle, before consulting any class label.
    coverage = {arm: reliable_frames(image, world, visibility, valid, arm).mean() for arm in ARMS}
    return max(ARMS, key=lambda arm: coverage[arm])


def frame_vector(image, world, arm="left"):
    shoulder, elbow, wrist, hip = ARMS[arm]
    return np.array([features.angle_at(world[shoulder], world[elbow], world[wrist]),
        features.angle_at(world[hip], world[shoulder], world[elbow]),
        world[shoulder, 1] - world[wrist, 1], world[0, 1] - world[wrist, 1],
        features.along_forward(world, world[shoulder], world[wrist]),
        image[shoulder, 1] - image[wrist, 1]], dtype=np.float32)


def sequence(image, world, visibility, valid, arm="left"):
    mask = reliable_frames(image, world, visibility, valid, arm)
    raw = np.full((len(image), len(NAMES)), np.nan, dtype=np.float32)
    for index in np.flatnonzero(mask):
        values = frame_vector(image[index], world[index], arm)
        if not np.isfinite(values).all():
            mask[index] = False
        else:
            raw[index, :-1] = values
    raw[:, -1] = mask.astype(np.float32)
    if not len(raw):
        raise ValueError("Empty original segment")
    indices = np.rint(np.linspace(0, len(raw) - 1, 32)).astype(int)
    return raw[indices], float(mask.mean())


def build(source: Path, destination: Path):
    landmark_path = source.with_name(source.name.replace("_sequences.npz", "_landmarks.npz"))
    with np.load(source, allow_pickle=True) as original, np.load(landmark_path) as landmarks:
        bundle = {key: original[key] for key in original.files}
        if "task_scope" not in bundle or set(np.asarray(bundle["task_scope"]).reshape(-1).tolist()) != {SCOPE}:
            raise ValueError("Only explicitly reviewed block_visible_arm_reach_hold clips may enter this recipe")
        if set(np.asarray(bundle["skill"]).reshape(-1).tolist()) != {"block"}:
            raise ValueError("Blocking arm recipe cannot consume another skill")
        if "source_sha256" not in bundle:
            raise ValueError("Original source hash is required")
        if len(bundle["segments"]) != 1:
            raise ValueError("Each reviewed arm cycle must remain one continuous shot; re-cut rather than count partial shots")
        sequences, fractions, counts, arms = [], [], [], []
        for segment in bundle["segments"]:
            selected = landmarks["segment_of_frame"] == segment
            arm = choose_arm(landmarks["image"][selected], landmarks["world"][selected],
                             landmarks["visibility"][selected], landmarks["valid"][selected])
            X, quality = sequence(landmarks["image"][selected], landmarks["world"][selected],
                                  landmarks["visibility"][selected], landmarks["valid"][selected], arm)
            sequences.append(X)
            fractions.append(quality)
            counts.append(int(selected.sum()))
            arms.append(arm)
        bundle.update(sequences=np.array(sequences), feature_names=np.array(NAMES),
            feature_usable_frac=np.array(fractions), feature_quality_profile=np.array(PROFILE),
            feature_recipe=np.array("blocking_arm_dataset_v1"), original_frame_counts=np.array(counts),
            observed_arm=np.array(arms),
            too_short=np.asarray(bundle["too_short"], dtype=bool) | (np.array(counts) < 10),
            source_feature_bundle_sha256=np.array(hashlib.sha256(source.read_bytes()).hexdigest()))
    destination.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(destination, **bundle)
    return {"file": destination.name, "task_scope": SCOPE, "labels": bundle["labels"].tolist(),
            "quality": fractions, "observed_arm": arms, "original_frame_counts": counts,
            "sha256": hashlib.sha256(destination.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--extracted", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    paths = sorted(args.extracted.glob("*_sequences.npz"))
    if not paths:
        raise ValueError("No reviewed source feature bundles")
    records = [build(path, args.out / path.name) for path in paths]
    provenance = {"task_scope": SCOPE, "feature_recipe": "blocking_arm_dataset_v1",
        "quality_profile": PROFILE, "torso_anchors": ANCHORS, "arm_landmarks": ARMS, "feature_names": NAMES,
        "coach_verified": False, "deployment_approved": False,
        "excluded_assessments": ["hidden arm", "footwork", "landing", "jump height", "airborne duration", "ball contact timing"],
        "temporal_sampling": "32 nearest original frames; unobserved values remain NaN and observed_frame=0",
        "label_scope": "Provisional agreement with creator's visible-arm-reach/hold demonstrations only; not a whole block grade",
        "source_extraction_provenance_sha256": hashlib.sha256((args.extracted / "provenance.json").read_bytes()).hexdigest(),
        "files": records}
    (args.out / "provenance.json").write_text(json.dumps(provenance, indent=2, allow_nan=False), encoding="utf-8")
    print(json.dumps(records, indent=2), flush=True)


if __name__ == "__main__":
    main()
