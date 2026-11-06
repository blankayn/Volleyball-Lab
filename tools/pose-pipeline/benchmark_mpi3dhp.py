"""Frozen MediaPipe IMAGE-mode pilot against raw MPI-INF-3DHP test annot3.

This is a 12-joint, hip-midpoint-relative general-pose benchmark. It is neither
the official 17-joint universal-skeleton protocol nor five-skill volleyball
validation. No rotation/scale fitting or model tuning occurs here.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
from pathlib import Path

import numpy as np

from evaluate_mpjpe import JOINT_INDEX, evaluate, read_annotations

# Official 'relevant' 17-joint order from mpii_get_joint_set.m, zero based.
REFERENCE_INDEX = dict(LEFT_SHOULDER=5, RIGHT_SHOULDER=2, LEFT_ELBOW=6,
    RIGHT_ELBOW=3, LEFT_WRIST=7, RIGHT_WRIST=4, LEFT_HIP=11, RIGHT_HIP=8,
    LEFT_KNEE=12, RIGHT_KNEE=9, LEFT_ANKLE=13, RIGHT_ANKLE=10)


def hip_relative(points, left, right):
    points = np.asarray(points, dtype=float)
    if points.ndim != 2 or points.shape[1] != 3:
        raise ValueError('Expected named 3D points')
    hips = points[[left, right]]
    if not np.isfinite(hips).all():
        raise ValueError('Both hip positions must be measurable')
    return points - hips.mean(axis=0)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def run(selection_path, annotations_path, images, model_path, out):
    import cv2
    import h5py
    import mediapipe as mp
    from mediapipe.tasks import python as mp_python
    from mediapipe.tasks.python import vision

    selection = json.loads(selection_path.read_bytes())
    if digest(annotations_path) != selection['annotation_sha256']:
        raise ValueError('Annotations differ from the pre-inference selection')
    if selection.get('task_scope') != 'general_3d_pose_benchmark_not_volleyball':
        raise ValueError('General dataset must not be labelled as a volleyball skill')
    if selection.get('uses_universal_skeleton') is not False:
        raise ValueError('Use original metric coordinates, not universal skeleton lengths')
    indices = selection['zero_based_frame_indices']
    if not indices or len(set(indices)) != len(indices):
        raise ValueError('Selection requires unique frame indices')
    out.mkdir(parents=True, exist_ok=False)
    predictions = out/'predictions'
    predictions.mkdir()
    rows, records, tiles = [], [], []
    options = vision.PoseLandmarkerOptions(base_options=mp_python.BaseOptions(model_asset_path=str(model_path)),
        running_mode=vision.RunningMode.IMAGE, num_poses=1,
        min_pose_detection_confidence=.65, min_pose_presence_confidence=.65,
        min_tracking_confidence=.65)
    with h5py.File(annotations_path) as reference, vision.PoseLandmarker.create_from_options(options) as detector:
        if reference['annot3'].shape[1:] != (1, 17, 3):
            raise ValueError('Expected original camera-coordinate 17-joint test annotations')
        for index in indices:
            if index < 0 or index >= len(reference['valid_frame']) or not reference['valid_frame'][index, 0]:
                raise ValueError('Selection contains an invalid official test frame')
            clip = f"{selection['sequence']}_{index+1:06d}"
            image_path = images/(clip+'.jpg')
            frame = cv2.imread(str(image_path))
            if frame is None:
                raise ValueError(f'Missing image: {image_path}')
            truth = hip_relative(reference['annot3'][index, 0], 11, 8)
            if not np.isfinite(truth).all():
                raise ValueError('Nonfinite reference joints')
            result = detector.detect(mp.Image(image_format=mp.ImageFormat.SRGB,
                                             data=cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)))
            world = np.full((33, 3), np.nan, dtype=np.float32)
            visibility = np.zeros(33, dtype=np.float32)
            image_points = np.full((33, 2), np.nan, dtype=np.float32)
            detected = bool(result.pose_world_landmarks and result.pose_landmarks)
            if detected:
                world = np.array([[p.x,p.y,p.z] for p in result.pose_world_landmarks[0]],dtype=np.float32)
                world = hip_relative(world, 23, 24).astype(np.float32)
                visibility = np.array([p.visibility for p in result.pose_landmarks[0]],dtype=np.float32)
                image_points = np.array([[p.x,p.y] for p in result.pose_landmarks[0]],dtype=np.float32)
            np.savez_compressed(predictions/(clip+'_landmarks.npz'), schema_version=2,
                world=world[None], visibility=visibility[None], image=image_points[None],
                source_frame_index=index, model_sha256=digest(model_path))
            for name, reference_index in REFERENCE_INDEX.items():
                rows.append((clip,'general',0,name,*truth[reference_index]))
            records.append(dict(clip=clip,source_frame_index=index,image_sha256=digest(image_path),detected=detected))
            if len(tiles)<6:
                overlay = frame.copy()
                height,width = frame.shape[:2]
                for name, reference_index in REFERENCE_INDEX.items():
                    x,y = reference['annot2'][index,0,reference_index]
                    cv2.circle(overlay,(round(x),round(y)),7,(0,255,0),2)
                    p=image_points[JOINT_INDEX[name]]
                    if np.isfinite(p).all():
                        cv2.circle(overlay,(round(float(p[0])*width),round(float(p[1])*height)),5,(255,80,0),-1)
                cv2.putText(overlay,clip+' green=reference blue=AI',(20,40),cv2.FONT_HERSHEY_SIMPLEX,1,(0,0,0),3)
                tiles.append(cv2.resize(overlay,(640,360)))
            print(f'{clip}: detected={detected}',flush=True)
    truth_path = out/'ground_truth_3d.csv'
    with truth_path.open('w',newline='',encoding='utf-8') as stream:
        writer=csv.writer(stream)
        writer.writerow(('clip','skill','frame','joint','x_mm','y_mm','z_mm'))
        writer.writerows(rows)
    annotations=read_annotations(truth_path)
    primary=evaluate(predictions,annotations,min_visibility=0.)
    filtered=evaluate(predictions,annotations,min_visibility=.5)
    report=dict(schema_version=1,benchmark_scope='general_pose_pilot_not_volleyball',
        dataset='MPI-INF-3DHP test',sequence=selection['sequence'],reference_field='annot3',
        reference_units='millimetres',prediction_units='metres converted to millimetres',
        coordinate_transform='camera axes; each skeleton translated to midpoint of left/right hips',
        rotation_alignment=False,scale_alignment=False,procrustes_alignment=False,
        joint_mapping=REFERENCE_INDEX,joint_count=12,model_sha256=digest(model_path),
        model_file=str(model_path),mediapipe_version=mp.__version__,running_mode='IMAGE',
        detection_confidence=.65,presence_confidence=.65,num_poses=1,
        selection_sha256=digest(selection_path),annotations_sha256=digest(annotations_path),
        ground_truth_csv_sha256=digest(truth_path),frames=records,
        pose_detection_coverage=sum(record['detected'] for record in records)/len(records),
        primary_all_finite_joints=primary,visibility_filtered_diagnostic=filtered,
        caveats=['Not the official universal-skeleton 17-joint MPI-INF-3DHP score',
            'Sparse still-image pilot, not Android LIVE_STREAM or temporal video validation',
            'MediaPipe landmark and motion-capture joint-centre definitions are approximate correspondences',
            'Pretrained MediaPipe overlap with benchmark subjects is unknown',
            'Missing detections are reported as coverage, not assigned zero error'],
        five_skill_volleyball_validated=False,manuscript_target_validated=False)
    (out/'report.json').write_text(json.dumps(report,indent=2,allow_nan=False))
    if len(tiles)==6:
        cv2.imwrite(str(out/'joint-correspondence-check.jpg'),np.concatenate(
            [np.concatenate(tiles[:3],axis=1),np.concatenate(tiles[3:],axis=1)],axis=0))
    print(json.dumps(dict(mpjpe_mm=primary['overall']['mpjpe_mm'],joint_coverage=primary['coverage'],
        detection_coverage=report['pose_detection_coverage'],volleyball_validated=False),indent=2))
    return report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--selection',type=Path,required=True)
    parser.add_argument('--annotations',type=Path,required=True)
    parser.add_argument('--images',type=Path,required=True)
    parser.add_argument('--model',type=Path,required=True)
    parser.add_argument('--out',type=Path,required=True)
    args=parser.parse_args()
    run(args.selection,args.annotations,args.images,args.model,args.out)


if __name__=='__main__':
    main()
