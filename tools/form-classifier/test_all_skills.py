"""Regression checks for data leakage and misleading form-model readiness."""
import csv
from pathlib import Path
import tempfile
import unittest

import numpy as np

from all_skills import assess, read_manifest


class CoverageTests(unittest.TestCase):
    def assess_labels(self, labels, groups):
        return assess(np.ones((len(labels), 32, 2)), np.array(labels), np.array(groups), ["a", "b"])

    def test_empty_skill_has_no_accuracy(self):
        status = self.assess_labels([], [])
        self.assertEqual(status["status"], "needs_data")
        self.assertIsNone(status["accuracy"])

    def test_repeated_segments_do_not_manufacture_sources(self):
        status = self.assess_labels(["ok", "fault"] * 100, ["same-session"] * 200)
        self.assertEqual(status["status"], "needs_data")

    def test_action_classes_are_rejected(self):
        status = self.assess_labels(["serve", "spike"] * 3, ["a", "a", "b", "b", "c", "c"])
        self.assertEqual(status["status"], "needs_data")

    def test_fault_from_single_source_cannot_support_every_fold(self):
        status = self.assess_labels(["ok", "ok", "fault"], ["a", "b", "c"])
        self.assertEqual(status["status"], "needs_data")
        self.assertTrue(any("fault needs" in reason for reason in status["reasons"]))

    def test_both_classes_across_sources_can_train_but_are_not_approved(self):
        status = self.assess_labels(["ok", "fault"] * 3, ["a", "a", "b", "b", "c", "c"])
        self.assertEqual(status["status"], "ready_for_development_training")
        self.assertFalse(status["deployment_approved"])
        self.assertIsNone(status["accuracy"])

    def test_all_missing_observations_block_training(self):
        values = np.full((6, 32, 2), np.nan)
        status = assess(values, np.array(["ok", "fault"] * 3),
                        np.array(["a", "a", "b", "b", "c", "c"]), ["a", "b"])
        self.assertEqual(status["status"], "needs_data")

    def test_unused_feature_can_be_disabled_by_trainer(self):
        values = np.ones((6, 32, 2))
        values[..., 1] = np.nan
        status = assess(values, np.array(["ok", "fault"] * 3),
                        np.array(["a", "a", "b", "b", "c", "c"]), ["a", "b"])
        self.assertEqual(status["status"], "ready_for_development_training")


class ManifestTests(unittest.TestCase):
    def write_manifest(self, root, rows):
        manifest = root / "clips.csv"
        with manifest.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=["video", "skill", "label", "source_group", "reviewer"])
            writer.writeheader()
            writer.writerows(rows)
        return manifest

    def test_relative_video_and_reviewer_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "serve.mp4").write_bytes(b"test-video")
            manifest = self.write_manifest(root, [dict(video="serve.mp4", skill="serve", label="fault",
                                                      source_group="athlete-a-session-1", reviewer="coach-a")])
            row = read_manifest(manifest)[0]
            self.assertEqual(row["video"], str((root / "serve.mp4").resolve()))
            self.assertEqual(row["reviewer"], "coach-a")

    def test_renamed_duplicate_video_cannot_claim_independence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rows = []
            for name in ("one", "two"):
                (root / f"{name}.mp4").write_bytes(b"identical-video")
                rows.append(dict(video=f"{name}.mp4", skill="serve", label="ok",
                                 source_group=name, reviewer="coach-a"))
            with self.assertRaisesRegex(ValueError, "duplicate video content"):
                read_manifest(self.write_manifest(root, rows))

    def test_unreviewed_label_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "clip.mp4").write_bytes(b"clip")
            manifest = self.write_manifest(root, [dict(video="clip.mp4", skill="set", label="ok",
                                                      source_group="session-a", reviewer="")])
            with self.assertRaisesRegex(ValueError, "every required field"):
                read_manifest(manifest)


if __name__ == "__main__":
    unittest.main()
