"""Coverage for exactly the landmarks consumed by pose-pipeline/features.py.

Keeps the original all-33-landmark usable_frac intact. The alternate profile
must be selected explicitly and also rejects out-of-frame body coordinates.
It does not identify the correct person or validate a coaching label.
"""
from pathlib import Path
import numpy as np

PROFILE = "features-v2-required13-visibility0.5-image-bounds"
# Nose; shoulders, elbows, wrists; hips, knees, ankles. These are the indices
# read by all 24 features, including forward_axis, penetration and jump scale.
REQUIRED = (0, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)


def reliable_frames(image, world, visibility, valid):
    image, world, visibility, valid = map(np.asarray, (image, world, visibility, valid))
    count = len(valid)
    if image.shape != (count, 33, 2) or world.shape != (count, 33, 3) or visibility.shape != (count, 33):
        raise ValueError("Expected aligned 33-landmark arrays")
    observed = image[:, REQUIRED, :]
    return (valid.astype(bool) & np.isfinite(observed).all(axis=(1, 2))
            & ((observed >= 0) & (observed <= 1)).all(axis=(1, 2))
            & np.isfinite(world[:, REQUIRED, :]).all(axis=(1, 2))
            & (visibility[:, REQUIRED] >= 0.5).all(axis=1))


def annotate(sequence_path: Path, destination: Path) -> None:
    landmark_path = sequence_path.with_name(sequence_path.name.replace("_sequences.npz", "_landmarks.npz"))
    with np.load(sequence_path, allow_pickle=True) as source, np.load(landmark_path) as landmarks:
        bundle = {key: source[key] for key in source.files}
        reliable = reliable_frames(landmarks["image"], landmarks["world"],
                                   landmarks["visibility"], landmarks["valid"])
        frame_segments = landmarks["segment_of_frame"]
        fractions = []
        for segment in bundle["segments"]:
            mask = frame_segments == segment
            if not mask.any():
                raise ValueError(f"No original frames for segment {segment}")
            fractions.append(float(reliable[mask].mean()))
        bundle["feature_usable_frac"] = np.array(fractions)
        bundle["feature_quality_profile"] = np.array(PROFILE)
    destination.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(destination, **bundle)
