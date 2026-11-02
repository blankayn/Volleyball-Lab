import unittest
from index_sportskills import shot_lead


class SportSkillsIndexTests(unittest.TestCase):
    def shot(self, **changes):
        return dict(shot_start_sec=1., shot_end_sec=4., parsed_result=["incorrect"],
                    overlapping_actionable_rows=[{"text": "On the block, keep your hands up."}], **changes)

    def test_negative_is_review_lead_not_training_label(self):
        row = shot_lead("abcdefghijk", self.shot())
        self.assertEqual(row["suggested_skills"], ["block"])
        self.assertEqual(row["provisional_source_label"], "incorrect")
        self.assertTrue(row["review_required"])
        self.assertFalse(row["coach_verified"])
        self.assertNotIn("label", row)

    def test_conflicting_judgments_are_never_majority_voted(self):
        shot = self.shot()
        shot["parsed_result"] = ["correct", "incorrect", "correct"]
        self.assertEqual(shot_lead("abcdefghijk", shot)["provisional_source_label"], "mixed_or_unknown")

    def test_negative_nonfinite_and_boolean_times_are_rejected(self):
        for start, end in ((3, 1), (-1, 2), (float("nan"), 4), (0, float("inf")), (False, 4)):
            shot = self.shot()
            shot.update(shot_start_sec=start, shot_end_sec=end)
            self.assertIsNone(shot_lead("abcdefghijk", shot))

    def test_long_windows_require_trimming(self):
        shot = self.shot()
        shot["shot_end_sec"] = 100
        self.assertTrue(shot_lead("abcdefghijk", shot)["requires_single_rep_trim"])

    def test_source_id_cannot_be_a_filesystem_path(self):
        with self.assertRaises(ValueError):
            shot_lead("../outside", self.shot())


if __name__ == "__main__":
    unittest.main()
