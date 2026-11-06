"""Frozen continuous-video MediaPipe evaluation on MADS Volleyball camera 1.

Requires original Sports_Volleyball_C1.avi, Sports_Volleyball_GT.mat and
Calib_Cam1.mat from the authors' release. No per-skill labels are inferred.
Camera extrinsics are supplied calibration, never fitted to model predictions.
"""
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path

import numpy as np

from benchmark_mpi3dhp import digest, hip_relative
from evaluate_mpjpe import JOINT_INDEX, evaluate, read_annotations

REFERENCE_INDEX = dict(LEFT_SHOULDER=10, RIGHT_SHOULDER=14, LEFT_ELBOW=11,
    RIGHT_ELBOW=15, LEFT_WRIST=12, RIGHT_WRIST=16, LEFT_HIP=2, RIGHT_HIP=6,
    LEFT_KNEE=3, RIGHT_KNEE=7, LEFT_ANKLE=4, RIGHT_ANKLE=8)


def camera_coordinates(points, rotation_vector, translation):
    import cv2
    points = np.asarray(points, dtype=float)
    if points.shape != (19, 3) or not np.isfinite(points).all():
        raise ValueError('MADS requires a finite 19-joint reference pose')
    rotation, _ = cv2.Rodrigues(np.asarray(rotation_vector, dtype=float).reshape(3))
    return points @ rotation.T + np.asarray(translation, dtype=float).reshape(3)


def run(data, model, out):
    import cv2
    import mediapipe as mp
    from mediapipe.tasks import python as mp_python
    from mediapipe.tasks.python import vision
    from scipy.io import loadmat
    from prepare_mads_benchmark import MANIFEST, verify

    manifest = json.loads(MANIFEST.read_text())
    for entry in manifest['members']:
        verify((data/entry['file']).read_bytes(), entry)
    video = data/'Sports_Volleyball_C1.avi'
    reference_path = data/'Sports_Volleyball_GT.mat'
    calibration_path = data/'Calib_Cam1.mat'
    poses = loadmat(reference_path)['GTpose2'][0]
    calibration = loadmat(calibration_path)
    capture = cv2.VideoCapture(str(video))
    count = int(capture.get(cv2.CAP_PROP_FRAME_COUNT))
    fps = capture.get(cv2.CAP_PROP_FPS)
    if count != len(poses) or count != 990 or fps != 15:
        capture.release()
        raise ValueError('Expected synchronized original 990-frame, 15-fps release')
    valid = [i for i, p in enumerate(poses) if p.shape == (19, 3) and np.isfinite(p).all()]
    out.mkdir(parents=True, exist_ok=False)
    selection = dict(protocol='all finite reference frames; no prediction-dependent selection',
        category='volleyball_unspecified', zero_based_frame_indices=valid,
        excluded_invalid_reference_frames=sorted(set(range(count))-set(valid)),
        video_sha256=digest(video), annotations_sha256=digest(reference_path),
        calibration_sha256=digest(calibration_path), model_sha256=digest(model))
    (out/'selection-before-inference.json').write_text(json.dumps(selection, indent=2))
    world = np.full((count, 33, 3), np.nan, dtype=np.float32)
    visibility = np.zeros((count, 33), dtype=np.float32)
    images = np.full((count, 33, 2), np.nan, dtype=np.float32)
    detected = np.zeros(count, dtype=bool)
    options = vision.PoseLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=str(model)),
        running_mode=vision.RunningMode.VIDEO, num_poses=1,
        min_pose_detection_confidence=.65, min_pose_presence_confidence=.65,
        min_tracking_confidence=.65)
    rows, tiles = [], []
    overlay_indices = set(np.linspace(0, count-1, 12, dtype=int).tolist())
    try:
        with vision.PoseLandmarker.create_from_options(options) as detector:
            for index in range(count):
                ok, frame = capture.read()
                if not ok:
                    raise ValueError(f'Video ended before reference frame {index}')
                result = detector.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB,
                    data=cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)), round(index*1000/fps))
                if result.pose_world_landmarks and result.pose_landmarks:
                    points = np.array([[p.x,p.y,p.z] for p in result.pose_world_landmarks[0]])
                    world[index] = hip_relative(points, 23, 24)
                    visibility[index] = [p.visibility for p in result.pose_landmarks[0]]
                    images[index] = [[p.x,p.y] for p in result.pose_landmarks[0]]
                    detected[index] = True
                if index in valid:
                    camera = camera_coordinates(poses[index], calibration['om_ext'], calibration['T_ext'])
                    truth = hip_relative(camera, 2, 6)
                    for name, reference_index in REFERENCE_INDEX.items():
                        rows.append(('Sports_Volleyball_C1', 'volleyball_unspecified', index,
                                     name, *truth[reference_index]))
                    if index in overlay_indices:
                        projected, _ = cv2.projectPoints(poses[index], calibration['om_ext'],
                            calibration['T_ext'], calibration['KK'], calibration['kc'])
                        for name, reference_index in REFERENCE_INDEX.items():
                            cv2.circle(frame, tuple(np.round(projected[reference_index,0]).astype(int)),
                                       5, (0,255,0), 1)
                            p = images[index,JOINT_INDEX[name]]
                            if np.isfinite(p).all():
                                cv2.circle(frame, tuple(np.round(p*[frame.shape[1],frame.shape[0]]).astype(int)),
                                           3, (255,80,0), -1)
                        cv2.putText(frame, f'frame {index} green=reference blue=AI', (5,22),
                                    cv2.FONT_HERSHEY_SIMPLEX, .45, (0,0,255), 1)
                        tiles.append(frame)
                if index % 100 == 0:
                    print(f'Processed {index}/{count}', flush=True)
    finally:
        capture.release()
    predictions = out/'predictions'
    predictions.mkdir()
    np.savez_compressed(predictions/'Sports_Volleyball_C1_landmarks.npz', schema_version=2,
        world=world, visibility=visibility, image=images,
        source_frame_index=np.arange(count), timestamp_ms=np.round(np.arange(count)*1000/fps),
        model_sha256=digest(model))
    truth_path = out/'ground_truth_3d.csv'
    with truth_path.open('w', newline='', encoding='utf-8') as stream:
        writer = csv.writer(stream)
        writer.writerow(('clip','skill','frame','joint','x_mm','y_mm','z_mm'))
        writer.writerows(rows)
    annotations = read_annotations(truth_path)
    primary = evaluate(predictions, annotations, min_visibility=0)
    filtered = evaluate(predictions, annotations, min_visibility=.5)
    report = dict(dataset='MADS', sequence='Sports_Volleyball', camera=1,
        scope='single athlete, one laboratory volleyball demonstration; skill labels unavailable',
        reference_field='GTpose2', reference_units='millimetres',
        coordinate_transform='supplied Rodrigues rotation and translation to camera axes, then each skeleton hip-midpoint relative',
        rotation_fitted=False, scale_fitted=False, procrustes_alignment=False,
        joint_mapping=REFERENCE_INDEX, model_sha256=digest(model), mediapipe_version=mp.__version__,
        running_mode='VIDEO', fps=fps, video_frames=count, valid_reference_frames=len(valid),
        excluded_invalid_reference_frames=count-len(valid),
        detection_coverage_all_frames=float(detected.mean()),
        detection_coverage_valid_reference_frames=float(detected[valid].mean()),
        primary_all_finite_joints=primary, visibility_filtered_diagnostic=filtered,
        selection_sha256=digest(out/'selection-before-inference.json'),
        ground_truth_csv_sha256=digest(truth_path),
        five_skill_volleyball_validated=False, manuscript_target_validated=False,
        caveats=['No Android LIVE_STREAM performance measurement',
            'Not the official MADS 19-joint bias-corrected tracking protocol; no fitted per-joint offsets',
            'Source actor demonstrated movements without a ball or racket',
            'One athlete and one view cannot establish unseen-player generalization',
            'Approximate landmark to motion-capture joint-centre correspondences',
            'No independent serving/receiving/setting/spiking/blocking labels',
            'Includes initial calibration pose and transitions; no action-only subset',
            'Pretraining overlap with MediaPipe is unknown',
            'Visibility filtering is diagnostic only; missing detections are not zero error'])
    (out/'report.json').write_text(json.dumps(report, indent=2, allow_nan=False))
    if tiles:
        columns=3
        while len(tiles)%columns:
            tiles.append(np.zeros_like(tiles[0]))
        cv2.imwrite(str(out/'joint-correspondence-check.jpg'), np.concatenate([
            np.concatenate(tiles[i:i+columns], axis=1) for i in range(0,len(tiles),columns)], axis=0))
    print(json.dumps(dict(primary=primary['overall'],coverage=primary['coverage'],
                          five_skill_target_met=primary['five_skill_target_met']), indent=2))
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, required=True)
    parser.add_argument('--model', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    run(args.data, args.model, args.out)
