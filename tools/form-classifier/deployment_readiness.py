"""Audit an exact scoped model for app packaging; never approve a research fit.

Independent-review and physical-device records are external evidence, not records
this script fabricates. Passing schema checks does not prove their authenticity.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path

from blocking_arm_temporal import NAMES, PROFILE, SCOPE, RECIPE
from evaluation import qualify_on_locked_test

POSE_MODEL_SHA256 = hashlib.sha256((Path(__file__).resolve().parents[2] /
    'app/src/main/assets/pose_landmarker_full.task').read_bytes()).hexdigest()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def audit(metadata, report, model_bytes, report_bytes, review=None, device=None):
    errors = []

    def check(condition, reason):
        if not condition:
            errors.append(reason)

    digest = sha(model_bytes)
    try:
        check(json.loads(report_bytes) == report, 'Evaluation object differs from the report bytes reviewed')
    except (ValueError, UnicodeError):
        errors.append('Reviewed evaluation bytes are not valid JSON')
    check(metadata.get('model_sha256') == digest, 'Model file does not match metadata SHA-256')
    check(metadata.get('schema_version') == 2 and metadata.get('timesteps') == 32,
          'Unsupported model schema or temporal input length')
    check(metadata.get('task_scope') == SCOPE and metadata.get('feature_recipe') == RECIPE
          and metadata.get('feature_quality_profile') == PROFILE, 'Unsupported task or feature recipe')
    pose = metadata.get('pose_configuration', {})
    check(pose.get('model_sha256') == POSE_MODEL_SHA256, 'Training pose asset hash does not match the app')
    check(pose.get('num_poses') == 1 and pose.get('tracker') == 'android_primary_tracker_v1'
          and all(pose.get(key) == .65 for key in ('min_detection_confidence', 'min_presence_confidence', 'min_tracking_confidence')),
          'Training pose settings/tracker do not match the app')
    check(metadata.get('classes') == ['fault', 'ok'] and metadata.get('feature_names') == list(NAMES),
          'Class/feature order does not match the app contract')
    normalization = metadata.get('normalisation', {})
    mean, std = normalization.get('mean', []), normalization.get('std', [])
    check(len(mean) == len(NAMES) and all(finite(v) for v in mean)
          and len(std) == len(NAMES) and all(finite(v) and v > 0 for v in std), 'Invalid normalization')
    check(bool(mean) and bool(std) and mean[-1] == 0 and std[-1] == 1, 'Observation mask must stay binary')
    disabled = metadata.get('disabled_features', [])
    check(isinstance(disabled, list) and set(disabled).issubset(NAMES) and 'observed_frame' not in disabled,
          'Invalid disabled feature contract')
    result = report.get('skills', {}).get('block', {})
    check(report.get('mode') == 'locked' and result.get('status') == 'locked_evaluation_recorded',
          'A locked player-separated evaluation is required; development results cannot authorize deployment')
    check(result.get('test_isolated_from_selection') is True
          and result.get('unseen_players_separated_by_registry') is True, 'Player/test split evidence is missing')
    check(result.get('task_scope') == SCOPE, 'Evaluation targets a different task')
    check(result.get('export', {}).get('model_sha256') == digest, 'Evaluation is not bound to this exact model')
    policy = metadata.get('decision_policy', {})
    check(policy == result.get('published_policy'), 'Exported policy differs from evaluated policy')
    temperature, threshold = policy.get('temperature'), policy.get('accept_above')
    check(policy.get('policy') == 'selective' and finite(temperature) and temperature > 0
          and finite(threshold) and .5 <= threshold < 1, 'No calibrated selective acceptance policy')
    try:
        passed = qualify_on_locked_test(policy, result.get('test_metrics', {}))
        check(passed.get('locked_test_passed') is True and passed.get('policy') == 'selective',
              'Locked-test class support, risk or coverage gate did not pass')
    except (KeyError, TypeError, ValueError):
        errors.append('Locked-test metrics are missing or invalid')
    class_metrics = result.get('test_metrics', {}).get('per_class', {})
    for name in ('fault', 'ok'):
        row = class_metrics.get(name, {})
        check(finite(row.get('support')) and row['support'] >= 10
              and finite(row.get('precision')) and row['precision'] >= .8
              and finite(row.get('recall')) and row['recall'] >= .8,
              f'Locked-test {name} support/precision/recall is insufficient')
    export = result.get('export') or metadata.get('benchmark', {})
    check(export.get('builtin_ops_only') is True and export.get('raw_argmax_flips') == 0
          and finite(export.get('max_probability_difference')) and export['max_probability_difference'] <= .03,
          'Native export fidelity evidence is missing or failed')
    if not isinstance(review, dict):
        errors.append('Independent coach/player review record is missing')
    else:
        check(review.get('model_sha256') == digest and review.get('report_sha256') == sha(report_bytes)
              and review.get('data_fingerprint') == result.get('data_fingerprint') and review.get('task_scope') == SCOPE,
              'Independent review is not bound to this model, dataset and report')
        check(all(review.get(flag) is True for flag in ('expert_labels_reviewed', 'independent_player_test',
              'test_exposure_reviewed')), 'Independent expert labels/player test/exposure review is incomplete')
        reviewers = review.get('reviewers', [])
        check(isinstance(reviewers, list) and bool(reviewers)
              and all(isinstance(r, dict) and r.get('id') and r.get('qualification') for r in reviewers),
              'Named qualified review provenance is required')
        arms = review.get('validated_arms', [])
        check(isinstance(arms, list) and bool(arms) and set(arms).issubset({'left', 'right'})
              and set(arms).issubset(metadata.get('observed_arms_in_training', [])), 'Validated observed arms are missing')
    if not isinstance(device, dict):
        errors.append('Physical Android device benchmark/parity record is missing')
    else:
        check(device.get('model_sha256') == digest and device.get('feature_recipe') == RECIPE,
              'Device benchmark targets a different model or recipe')
        check(device.get('pose_model_sha256') == POSE_MODEL_SHA256, 'Device pose asset hash does not match the app')
        check(device.get('physical_device') is True and device.get('platform') == 'android'
              and bool(device.get('device_model')) and bool(device.get('os_version')), 'Physical Android device provenance is required')
        check(device.get('includes_pose_estimation') is True and device.get('includes_feature_extraction') is True
              and device.get('includes_video_decode') is True, 'Benchmark does not cover the complete phone pipeline')
        check(device.get('latency_includes_empty_detections') is True,
              'Phone latency must account for frames without accepted poses')
        cases = device.get('preprocessing_parity_cases', [])
        check(device.get('preprocessing_parity_verified') is True and isinstance(cases, list)
              and {'left_arm', 'right_arm', 'occlusion', 'missing_pose', 'dropped_frame'}.issubset(cases),
              'Preprocessing parity must cover both arms, occlusion, missing poses and dropped frames')
        count = device.get('inferences')
        check(isinstance(count, int) and not isinstance(count, bool) and count >= 100
              and device.get('successful_inferences') == count and device.get('invalid_outputs') == 0,
              'Device stability evidence is insufficient')
        check(finite(device.get('pipeline_p95_ms')) and 0 < device['pipeline_p95_ms'] <= 150,
              'Phone pipeline p95 exceeds the initial 150ms processing budget or was not measured')
        check(finite(device.get('max_probability_difference')) and 0 <= device['max_probability_difference'] <= .03,
              'Phone preprocessing/inference parity was not verified')
    return {'ready_for_packaging': not errors, 'task_scope': SCOPE, 'model_sha256': digest,
            'report_sha256': sha(report_bytes), 'blocking_reasons': errors,
            'scope_note': 'Visible-arm reach/hold only, not overall blocking technique',
            'evidence_note': 'Review/device assertions require authentic independent records; this tool cannot authenticate a reviewer'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model-dir', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--review', type=Path)
    parser.add_argument('--device', type=Path)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--package-out', type=Path, help='Create an immutable app asset bundle only after all checks pass')
    args = parser.parse_args()
    report_bytes = args.report.read_bytes()
    read = lambda p: json.loads(p.read_text(encoding='utf-8')) if p else None
    metadata = read(args.model_dir / 'form_classifier.json')
    model_bytes = (args.model_dir / 'form_classifier.tflite').read_bytes()
    review, device = read(args.review), read(args.device)
    result = audit(metadata, json.loads(report_bytes), model_bytes, report_bytes, review, device)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2, allow_nan=False), encoding='utf-8')
    print(json.dumps(result, indent=2))
    if args.package_out and result['ready_for_packaging']:
        package_model(args.package_out, metadata, json.loads(report_bytes), model_bytes, report_bytes,
                      args.review.read_bytes(), args.device.read_bytes())
    return 0 if result['ready_for_packaging'] else 2


def package_model(out, metadata, report, model_bytes, report_bytes, review_bytes, device_bytes):
    review, device = json.loads(review_bytes), json.loads(device_bytes)
    result = audit(metadata, report, model_bytes, report_bytes, review, device)
    if not result['ready_for_packaging']:
        raise ValueError('Cannot package: ' + '; '.join(result['blocking_reasons']))
    manifest = dict(schema_version=1, deployment_approved=True, task_scope=SCOPE,
        feature_recipe=metadata['feature_recipe'], feature_quality_profile=PROFILE,
        timesteps=32, classes=metadata['classes'], feature_names=list(NAMES),
        model_sha256=sha(model_bytes), normalisation=metadata['normalisation'],
        disabled_features=metadata['disabled_features'], validated_arms=review['validated_arms'],
        temperature=metadata['decision_policy']['temperature'], accept_above=metadata['decision_policy']['accept_above'],
        evaluation_report_sha256=sha(report_bytes), independent_review_sha256=sha(review_bytes),
        device_evidence_sha256=sha(device_bytes))
    manifest['pose_model_sha256'] = metadata['pose_configuration']['model_sha256']
    target = out / sha(model_bytes)
    target.mkdir(parents=True, exist_ok=False)
    (target / 'blocking_arm_classifier.tflite').write_bytes(model_bytes)
    (target / 'blocking_arm_release.json').write_text(json.dumps(manifest, indent=2, allow_nan=False), encoding='utf-8')
    (target / 'readiness.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    return target


if __name__ == '__main__':
    raise SystemExit(main())
