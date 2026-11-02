"""Timestamp-based v2 blocking-arm features, matching emitted Android poses.

This is a distinct recipe: v1 weights must never be relabelled as v2 weights.
No interpolation across occlusion, missing detections or dropped-frame gaps.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
from blocking_arm_dataset import ARMS, ANCHORS, NAMES, SCOPE, frame_vector

RECIPE = 'blocking_arm_time_v2'
PROFILE = 'blocking-visible-arm7-v2-visibility-presence0.5-time50ms-mask'
MAX_NEAREST_MS = 50


def sample_indices(timestamps):
    times = np.asarray(timestamps)
    if times.ndim != 1 or len(times) < 10 or not np.issubdtype(times.dtype,np.integer) or (times < 0).any():
        raise ValueError('At least ten nonnegative integer millisecond timestamps required')
    if np.any(np.diff(times) <= 0) or np.any(np.diff(times) > 500):
        raise ValueError('Timestamp reset or tracking gap over 500ms')
    if not 350 <= times[-1] - times[0] <= 6000:
        raise ValueError('Arm window must span 350 to 6000ms')
    relative = times - times[0]
    targets = np.arange(32,dtype=np.int64) * relative[-1]
    # argmin chooses the earlier original frame on an exact tie, also on Android.
    distances = np.abs(relative[:,None] * 31 - targets[None,:])
    indices = distances.argmin(axis=0)
    observed = distances[indices,np.arange(32)] <= MAX_NEAREST_MS * 31
    return indices, observed


def window(image, world, visibility, presence, detected, timestamps):
    image, world, visibility, presence = map(np.asarray, (image, world, visibility, presence))
    count = len(image)
    if (image.shape != (count, 33, 2) or world.shape != (count, 33, 3)
            or visibility.shape != (count, 33) or presence.shape != (count, 33)
            or np.asarray(detected).shape != (count,) or np.asarray(timestamps).shape != (count,)):
        raise ValueError('Expected aligned image/world/confidence/detection/time arrays')
    emitted = np.asarray(detected, dtype=bool)
    image, world, visibility, presence, times = [v[emitted] for v in
        (image, world, visibility, presence, np.asarray(timestamps))]
    indices, close = sample_indices(times)
    torso = (11, 12, 23, 24)
    torso_length = np.linalg.norm((world[:,11] + world[:,12] - world[:,23] - world[:,24]) / 2, axis=1)
    common = ((presence[:,torso] >= .5).all(1) & np.isfinite(presence[:,torso]).all(1)
              & np.isfinite(torso_length) & (torso_length >= .15))
    candidates = {}
    for arm, (_, elbow, wrist, _) in ARMS.items():
        required = (*ANCHORS, elbow, wrist)
        points = image[:,required]
        reliable = (common & np.isfinite(points).all((1,2)) & ((points>=0)&(points<=1)).all((1,2))
            & np.isfinite(world[:,required]).all((1,2)) & np.isfinite(visibility[:,required]).all(1)
            & (visibility[:,required]>=.5).all(1) & np.isfinite(presence[:,required]).all(1)
            & (presence[:,required]>=.5).all(1))
        values = np.full((32,len(NAMES)), np.nan, dtype=np.float32)
        values[:,-1] = 0
        for row, index in enumerate(indices):
            if close[row] and reliable[index]:
                measurements = frame_vector(image[index],world[index],arm)
                if np.isfinite(measurements).all():
                    values[row,:-1] = measurements
                    values[row,-1] = 1
        candidates[arm] = values
    arm = max(ARMS, key=lambda side: float(candidates[side][:,-1].mean()))
    return candidates[arm], float(candidates[arm][:,-1].mean()), arm, len(times)


def build(source, destination):
    landmarks_path = source.with_name(source.name.replace('_sequences.npz','_landmarks.npz'))
    with np.load(source,allow_pickle=True) as original, np.load(landmarks_path) as landmarks:
        bundle = {key:original[key] for key in original.files}
        if set(bundle.get('task_scope',[])) != {SCOPE} or set(bundle['skill']) != {'block'}:
            raise ValueError('Explicit reviewed blocking arm scope required')
        if len(bundle['segments']) != 1 or 'source_sha256' not in bundle:
            raise ValueError('One complete reviewed shot and original source hash required')
        selected = landmarks['segment_of_frame'] == bundle['segments'][0]
        X, quality, arm, count = window(*(landmarks[key][selected] for key in
            ('image','world','visibility','presence','detected','timestamps_ms')))
        bundle.update(sequences=X[None],feature_names=np.array(NAMES),feature_usable_frac=np.array([quality]),
            feature_quality_profile=np.array(PROFILE),feature_recipe=np.array(RECIPE),observed_arm=np.array([arm]),
            original_frame_counts=np.array([count]),too_short=np.array([count<10]),
            source_feature_bundle_sha256=np.array(hashlib.sha256(source.read_bytes()).hexdigest()))
    destination.parent.mkdir(parents=True,exist_ok=True)
    np.savez_compressed(destination,**bundle)
    return dict(file=destination.name,quality=quality,observed_arm=arm,emitted_frames=count)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--extracted',type=Path,required=True)
    parser.add_argument('--out',type=Path,required=True)
    args=parser.parse_args()
    paths=sorted(args.extracted.glob('*_sequences.npz'))
    if not paths: raise ValueError('No reviewed feature bundles')
    records=[build(path,args.out/path.name) for path in paths]
    result=dict(feature_recipe=RECIPE,quality_profile=PROFILE,task_scope=SCOPE,
        coach_verified=False,deployment_approved=False,max_nearest_ms=MAX_NEAREST_MS,
        source_provenance_sha256=hashlib.sha256((args.extracted/'provenance.json').read_bytes()).hexdigest(),files=records)
    (args.out/'provenance.json').write_text(json.dumps(result,indent=2))
    print(json.dumps(result,indent=2))

if __name__=='__main__': main()
