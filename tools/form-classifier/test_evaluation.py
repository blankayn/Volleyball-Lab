import unittest
import numpy as np
from evaluation import assert_disjoint, connected_groups, fit_calibration, grouped_folds, metrics, qualify_on_locked_test


class EvaluationTests(unittest.TestCase):
    def test_confusion_and_present_but_never_predicted_class(self):
        result = metrics([0, 0, 1, 1], [[.9, .1]] * 4, ["fault", "ok"])
        self.assertEqual(result["confusion_matrix"], [[2, 0], [2, 0]])
        self.assertEqual(result["per_class"]["ok"]["f1"], 0)
        self.assertIsNone(result["per_class"]["ok"]["precision"])
        self.assertAlmostEqual(result["macro_f1"], 1 / 3)

    def test_abstention_does_not_report_perfect_empty_accuracy(self):
        result = metrics([0, 1], [[.6, .4], [.4, .6]], ["fault", "ok"], .9)
        self.assertEqual(result["selective"]["coverage"], 0)
        self.assertIsNone(result["selective"]["accuracy"])

    def test_invalid_model_output_is_rejected(self):
        for p in ([[2, -1]], [[.2, .2]], [[float("nan"), .5]]):
            with self.assertRaises(ValueError):
                metrics([0], p, ["fault", "ok"])

    def test_uncalibrated_tiny_data_must_abstain(self):
        result = fit_calibration([0, 1], [[.99, .01], [.01, .99]], ["fault", "ok"])
        self.assertEqual(result["policy"], "abstain")
        self.assertIsNone(result["accept_above"])

    def test_players_link_multiple_videos_and_duplicate_files_transitively(self):
        rows = [dict(source_group="a", athlete_id="player1"),
                dict(source_group="b", athlete_id="player1", source_sha256="same"),
                dict(source_group="c", source_sha256="same"), dict(source_group="d")]
        groups = connected_groups(rows)
        self.assertEqual(len(set(groups[:3])), 1)
        self.assertNotEqual(groups[0], groups[3])
        with self.assertRaisesRegex(ValueError, "leakage"):
            assert_disjoint(groups, [0], [2])

    def test_unknown_players_are_not_all_treated_as_one_person(self):
        groups = connected_groups([dict(source_group="a", athlete_id="unknown"), dict(source_group="b", athlete_id="unknown")])
        self.assertEqual(len(set(groups)), 2)

    def test_class_deficient_training_fold_is_skipped(self):
        folds = list(grouped_folds([0, 0, 1], ["fault-video", "fault-video", "ok-video"], 2))
        self.assertTrue(all(f["reason"] for f in folds))

    def test_undefined_class_support_is_visible(self):
        result = metrics([0], [[.8, .2]], ["fault", "ok"])
        self.assertFalse(result["all_classes_observed"])
        self.assertIsNone(result["per_class"]["ok"]["recall"])

    def test_many_reps_from_one_player_cannot_calibrate_confidence(self):
        result = fit_calibration([0, 1] * 20, [[.99, .01], [.01, .99]] * 20,
                                 ["fault", "ok"], groups=["same-player"] * 40)
        self.assertEqual(result["policy"], "abstain")
        self.assertEqual(result["status"], "insufficient_calibration_groups")

    def test_good_calibration_cannot_override_failed_locked_test(self):
        test = metrics([0, 1] * 10, [[.1, .9], [.9, .1]] * 10, ["fault", "ok"], .8,
                       groups=[f"player-{i // 4}" for i in range(20)])
        policy = qualify_on_locked_test({"policy": "selective", "temperature": 1., "accept_above": .8}, test)
        self.assertEqual(policy["policy"], "abstain")
        self.assertFalse(policy["locked_test_passed"])

    def test_three_perfect_players_still_cannot_establish_reliable_acceptance(self):
        result = fit_calibration([0, 1] * 30, [[.99, .01], [.01, .99]] * 30,
                                 ["fault", "ok"], groups=[f"player-{i // 20}" for i in range(60)])
        self.assertEqual(result["policy"], "abstain")

    def test_sufficient_separate_calibration_groups_can_fit_a_selective_policy(self):
        result = fit_calibration([0, 1] * 20, [[.99, .01], [.01, .99]] * 20,
                                 ["fault", "ok"], groups=[f"player-{i // 2}" for i in range(40)])
        self.assertEqual(result["policy"], "selective")
        self.assertGreater(result["temperature"], 0)


if __name__ == "__main__":
    unittest.main()
