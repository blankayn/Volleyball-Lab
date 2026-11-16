"""Recompute a MADS result directly from original MATLAB data and raw predictions.

Does not read the generated reference CSV or reuse the producer's coordinate
transform or joint mapping. This verifies numerical transcription, not action
labels, reference quality, model quality or unseen-player generalization.
"""
import argparse
import json
from pathlib import Path

import cv2
import numpy as np
from scipy.io import loadmat

from benchmark_mpi3dhp import digest
from prepare_mads_benchmark import MANIFEST, verify


def check(data, run):
    manifest = json.loads(MANIFEST.read_text())
    for entry in manifest['members']:
        verify((data/entry['file']).read_bytes(), entry)
    report = json.loads((run/'report.json').read_text())
    selection_path = run/'selection-before-inference.json'
    selection = json.loads(selection_path.read_text())
    if digest(selection_path) != report['selection_sha256']:
        raise ValueError('Pre-inference selection changed')
    for key, filename in [('video_sha256','Sports_Volleyball_C1.avi'),
                          ('annotations_sha256','Sports_Volleyball_GT.mat'),
                          ('calibration_sha256','Calib_Cam1.mat')]:
        if digest(data/filename) != selection[key]:
            raise ValueError('Run does not match the original input files')
    if selection['model_sha256'] != report['model_sha256']:
        raise ValueError('Model identity mismatch')
    truth = loadmat(data/'Sports_Volleyball_GT.mat')['GTpose2'][0]
    calibration = loadmat(data/'Calib_Cam1.mat')
    rotation = cv2.Rodrigues(calibration['om_ext'].reshape(3))[0]
    with np.load(run/'predictions/Sports_Volleyball_C1_landmarks.npz', allow_pickle=False) as saved:
        world, visibility = saved['world'].astype(float), saved['visibility']
        if str(saved['model_sha256']) != report['model_sha256']:
            raise ValueError('Predictions do not match the reported model')
    reference_indices = [10,14,11,15,12,16,2,6,3,7,4,8]
    prediction_indices = [11,12,13,14,15,16,23,24,25,26,27,28]
    valid = [i for i, pose in enumerate(truth) if np.isfinite(pose).all()]
    if valid != selection['zero_based_frame_indices']:
        raise ValueError('Selection does not include every finite reference frame')
    errors, filtered, deltas = [], [], []
    for index in valid:
        # Column-vector Rodrigues transform independently expressed here.
        camera = (rotation @ truth[index].T).T + calibration['T_ext'].reshape(3)
        relative = camera - (camera[2]+camera[6])/2
        for a, b in zip(reference_indices, prediction_indices):
            prediction = world[index,b]*1000
            if not np.isfinite(prediction).all() or not np.isfinite(visibility[index,b]):
                continue
            delta = prediction-relative[a]
            error = float(np.sqrt(np.dot(delta,delta)))
            errors.append(error)
            deltas.append(delta)
            if visibility[index,b] >= .5:
                filtered.append(error)
    for values, key in [(errors,'primary_all_finite_joints'),(filtered,'visibility_filtered_diagnostic')]:
        expected = report[key]['overall']
        if not values or len(values) != expected['joints'] or round(float(np.mean(values)),3) != expected['mpjpe_mm']:
            raise ValueError('Direct reference recomputation disagrees with saved report')
    result = dict(independent_raw_mat_recomputation=True, valid_reference_frames=len(valid),
        joint_pairs=len(errors), mpjpe_mm=float(np.mean(errors)),
        visibility_diagnostic_mm=float(np.mean(filtered)),
        axis_mae_mm=np.abs(deltas).mean(axis=0).tolist(),
        axis_rmse_mm=np.sqrt(np.square(deltas).mean(axis=0)).tolist(),
        report_sha256=digest(run/'report.json'), selection_sha256=digest(selection_path),
        matches_saved_report=True)
    (run/'verification.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, required=True)
    parser.add_argument('--run', type=Path, required=True)
    args = parser.parse_args()
    check(args.data, args.run)
