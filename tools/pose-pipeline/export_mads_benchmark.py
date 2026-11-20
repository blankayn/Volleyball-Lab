"""Export verified, non-personal MADS benchmark metadata for the Android app."""
import argparse
from datetime import date
import json
from pathlib import Path

from benchmark_mpi3dhp import digest
from verify_mads_benchmark import check


def summary(report, verification, evaluated_on):
    date.fromisoformat(evaluated_on)
    primary = report['primary_all_finite_joints']
    if (report['dataset'] != 'MADS' or report['sequence'] != 'Sports_Volleyball' or
            report['camera'] != 1 or report['running_mode'] != 'VIDEO' or
            not verification['matches_saved_report'] or
            primary['five_skill_target_met'] is not None or
            report['five_skill_volleyball_validated'] is not False or
            report['rotation_fitted'] or report['scale_fitted']):
        raise ValueError('Expected the verified, unaligned MADS camera-1 video benchmark')
    if (verification['joint_pairs'] != primary['overall']['joints'] or
            verification['valid_reference_frames'] != report['valid_reference_frames'] or
            round(verification['mpjpe_mm'],3) != primary['overall']['mpjpe_mm']):
        raise ValueError('Verification does not match the reported measurement')
    return dict(schema_version=1, dataset='MADS', metric='3D_MPJPE',
        scope='dataset_benchmark_not_session_measurement', evaluated_on=evaluated_on,
        model_sha256=report['model_sha256'], running_mode='VIDEO',
        mpjpe_mm=primary['overall']['mpjpe_mm'], target_mm=primary['target_mm'],
        target_met=primary['overall_target_met'],
        valid_reference_frames=report['valid_reference_frames'], joint_pairs=verification['joint_pairs'],
        joint_coverage=primary['coverage'], joints_per_frame=12,
        reference='independent_motion_capture', alignment='hip_midpoint_translation_only',
        camera_count=1, athlete_count=1, five_skill_validated=False,
        source_url='https://visal.cs.cityu.edu.hk/research/mads/',
        report_sha256=verification['report_sha256'], selection_sha256=report['selection_sha256'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, required=True)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--evaluated-on', required=True, help='ISO local evaluation date')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    verified = check(args.data,args.run)
    report = json.loads((args.run/'report.json').read_text())
    if digest(args.run/'report.json') != verified['report_sha256']:
        raise ValueError('Report changed after verification')
    result = summary(report,verified,args.evaluated_on)
    args.out.parent.mkdir(parents=True,exist_ok=True)
    args.out.write_text(json.dumps(result,indent=2,allow_nan=False)+'\n')
    print(f'Exported verified {result["mpjpe_mm"]} mm benchmark to {args.out}')
