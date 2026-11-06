import unittest

from export_mads_benchmark import summary


class AppBenchmarkExportTests(unittest.TestCase):
    def evidence(self):
        report = dict(dataset='MADS', sequence='Sports_Volleyball', camera=1,
            running_mode='VIDEO', five_skill_volleyball_validated=False,
            rotation_fitted=False, scale_fitted=False, model_sha256='a'*64,
            valid_reference_frames=10, selection_sha256='b'*64,
            primary_all_finite_joints=dict(five_skill_target_met=None,
                overall=dict(joints=120, mpjpe_mm=172.716), target_mm=50,
                overall_target_met=False, coverage=1.0))
        verification = dict(matches_saved_report=True, joint_pairs=120,
            valid_reference_frames=10, mpjpe_mm=172.716028,
            report_sha256='c'*64)
        return report, verification

    def test_failed_target_stays_failed_and_is_not_a_session_measurement(self):
        report, verified = self.evidence()
        result = summary(report,verified,'2026-11-05')
        self.assertFalse(result['target_met'])
        self.assertFalse(result['five_skill_validated'])
        self.assertEqual(result['scope'],'dataset_benchmark_not_session_measurement')
        self.assertEqual(result['mpjpe_mm'],172.716)

    def test_mismatched_reference_coverage_cannot_be_published(self):
        report, verified = self.evidence()
        verified['joint_pairs'] = 12
        with self.assertRaisesRegex(ValueError, 'match'):
            summary(report,verified,'2026-11-05')

    def test_fitted_or_five_skill_result_cannot_be_mislabelled_as_this_benchmark(self):
        for change in [dict(rotation_fitted=True),dict(five_skill_volleyball_validated=True)]:
            report, verified = self.evidence()
            report.update(change)
            with self.assertRaisesRegex(ValueError, 'Expected'):
                summary(report,verified,'2026-11-05')


if __name__ == '__main__':
    unittest.main()
