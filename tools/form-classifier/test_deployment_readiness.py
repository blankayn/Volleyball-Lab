import copy
import json
import unittest
import tempfile
from pathlib import Path

from blocking_arm_temporal import NAMES, PROFILE, SCOPE, RECIPE
from deployment_readiness import audit, package_model, sha, POSE_MODEL_SHA256
from evaluation import metrics


class DeploymentReadinessTests(unittest.TestCase):
    def setUp(self):
        # Controlled fixtures verify gates; these are not real release evidence.
        self.blob = b'fixture-model'
        self.digest = sha(self.blob)
        policy = dict(policy='selective', temperature=1., accept_above=.8)
        self.metadata = dict(schema_version=2, timesteps=32, task_scope=SCOPE,
            feature_recipe=RECIPE, feature_quality_profile=PROFILE,
            classes=['fault', 'ok'], feature_names=list(NAMES), model_sha256=self.digest,
            normalisation=dict(mean=[0.] * len(NAMES), std=[1.] * len(NAMES)), disabled_features=[],
            decision_policy=policy, observed_arms_in_training=['left'])
        self.metadata['pose_configuration'] = dict(num_poses=1, tracker='android_primary_tracker_v1',
            model_sha256=POSE_MODEL_SHA256,
            min_detection_confidence=.65, min_presence_confidence=.65, min_tracking_confidence=.65, running_mode='VIDEO')
        measured = metrics([0, 1] * 40, [[.99, .01], [.01, .99]] * 40, ['fault', 'ok'], .8,
                           groups=[f'player-{i // 2}' for i in range(80)])
        self.report = dict(mode='locked', skills={'block': dict(status='locked_evaluation_recorded',
            test_isolated_from_selection=True, unseen_players_separated_by_registry=True, task_scope=SCOPE,
            data_fingerprint='fixture-data', published_policy=policy, test_metrics=measured,
            export=dict(model_sha256=self.digest, builtin_ops_only=True, raw_argmax_flips=0,
                        max_probability_difference=.001))})
        self.report_bytes = json.dumps(self.report).encode()
        self.review = dict(model_sha256=self.digest, report_sha256=sha(self.report_bytes),
            data_fingerprint='fixture-data', task_scope=SCOPE, expert_labels_reviewed=True,
            independent_player_test=True, test_exposure_reviewed=True,
            reviewers=[dict(id='fixture-reviewer', qualification='fixture qualification')], validated_arms=['left'])
        self.device = dict(model_sha256=self.digest, feature_recipe=RECIPE,
            physical_device=True, platform='android', device_model='fixture', os_version='fixture',
            includes_pose_estimation=True, includes_feature_extraction=True, includes_video_decode=True,
            inferences=100, successful_inferences=100, invalid_outputs=0, pipeline_p95_ms=50,
            max_probability_difference=.001)
        self.device.update(preprocessing_parity_verified=True,
            pose_model_sha256=POSE_MODEL_SHA256,
            latency_includes_empty_detections=True,
            preprocessing_parity_cases=['left_arm', 'right_arm', 'occlusion', 'missing_pose', 'dropped_frame'])

    def check(self, review=True, device=True):
        return audit(self.metadata, self.report, self.blob, self.report_bytes,
                     self.review if review else None, self.device if device else None)

    def test_complete_consistent_fixture_passes_gate_without_creating_evidence(self):
        self.assertTrue(self.check()['ready_for_packaging'])

    def test_native_probability_agreement_cannot_replace_missing_pose_sampling_parity(self):
        self.device['preprocessing_parity_cases'].remove('missing_pose')
        self.assertFalse(self.check()['ready_for_packaging'])

    def test_different_pose_extractor_cannot_use_otherwise_valid_weights(self):
        self.metadata['pose_configuration']['model_sha256']='e'*64
        self.assertFalse(self.check()['ready_for_packaging'])

    def test_deployment_flag_cannot_override_development_data(self):
        self.metadata['deployment_approved'] = True
        self.report['mode'] = 'development'
        self.assertFalse(self.check()['ready_for_packaging'])

    def test_exact_weights_and_report_bindings_are_required(self):
        self.assertFalse(audit(self.metadata, self.report, b'changed', self.report_bytes,
                               self.review, self.device)['ready_for_packaging'])
        self.review['report_sha256'] = 'another-report'
        self.assertFalse(self.check()['ready_for_packaging'])

    def test_missing_human_or_physical_device_evidence_blocks_release(self):
        self.assertFalse(self.check(review=False)['ready_for_packaging'])
        self.assertFalse(self.check(device=False)['ready_for_packaging'])
        self.device['physical_device'] = False
        self.assertFalse(self.check()['ready_for_packaging'])

    def test_failed_per_class_metrics_cannot_hide_behind_aggregate_accuracy(self):
        self.report['skills']['block']['test_metrics']['per_class']['ok']['recall'] = .2
        self.assertFalse(self.check()['ready_for_packaging'])

    def test_wrong_task_bad_mask_normalization_and_phone_failures_block(self):
        for field, value in [('task_scope', 'whole_block_execution'), ('feature_names', ['wrong'])]:
            old = copy.deepcopy(self.metadata[field])
            self.metadata[field] = value
            self.assertFalse(self.check()['ready_for_packaging'])
            self.metadata[field] = old
        self.metadata['normalisation']['mean'][-1] = .5
        self.assertFalse(self.check()['ready_for_packaging'])
        self.metadata['normalisation']['mean'][-1] = 0
        self.device['invalid_outputs'] = 1
        self.assertFalse(self.check()['ready_for_packaging'])

    def test_packaging_cannot_override_a_failed_audit(self):
        with tempfile.TemporaryDirectory() as temporary:
            out = Path(temporary) / 'package'
            self.report['mode'] = 'development'
            with self.assertRaisesRegex(ValueError, 'Cannot package'):
                package_model(out, self.metadata, self.report, self.blob, self.report_bytes,
                              json.dumps(self.review).encode(), json.dumps(self.device).encode())
            self.assertFalse(out.exists())

    def test_valid_fixture_package_is_immutable_and_matches_android_manifest_fields(self):
        with tempfile.TemporaryDirectory() as temporary:
            arguments = (Path(temporary), self.metadata, self.report, self.blob, self.report_bytes,
                         json.dumps(self.review).encode(), json.dumps(self.device).encode())
            target = package_model(*arguments)
            manifest = json.loads((target / 'blocking_arm_release.json').read_text())
            self.assertEqual(sha((target / 'blocking_arm_classifier.tflite').read_bytes()), manifest['model_sha256'])
            self.assertEqual(manifest['feature_names'], list(NAMES))
            self.assertEqual(manifest['validated_arms'], ['left'])
            with self.assertRaises(FileExistsError):
                package_model(*arguments)


if __name__ == '__main__':
    unittest.main()
