"""Check millimetre conversion, visibility filtering, and 3D input validation."""
import csv
import tempfile
import unittest
from pathlib import Path

import numpy as np

from evaluate_mpjpe import evaluate, read_annotations


class MpjpeTest(unittest.TestCase):
    def test_metric_coverage_and_skill_breakdown(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            world = np.zeros((2, 33, 3), dtype=float)
            visibility = np.ones((2, 33), dtype=float)
            world[0, 11, 0] = 0.03
            world[0, 12, 1] = 0.04
            world[1, 11, 2] = 0.05
            visibility[1, 11] = 0.1
            np.savez(root / "clip_landmarks.npz", schema_version=2,
                     world=world, visibility=visibility)
            annotations = root / "truth.csv"
            with annotations.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.writer(handle)
                writer.writerow(("clip", "skill", "frame", "joint", "x_mm", "y_mm", "z_mm"))
                writer.writerows((
                    ("clip", "serve", 0, "LEFT_SHOULDER", 0, 0, 0),
                    ("clip", "serve", 0, "RIGHT_SHOULDER", 0, 0, 0),
                    ("clip", "block", 1, "LEFT_SHOULDER", 0, 0, 0),
                ))
            report = evaluate(root, read_annotations(annotations))
            self.assertEqual(35.0, report["overall"]["mpjpe_mm"])
            self.assertEqual(0.6667, report["coverage"])
            self.assertEqual(35.0, report["by_skill"]["serve"]["mpjpe_mm"])
            self.assertIsNone(report["five_skill_target_met"])
            self.assertIn("block", report["skills_without_valid_annotations"])
            self.assertEqual(1, report["skipped"]["low_visibility_or_missing_pose"])

    def test_rejects_2d_annotations(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "truth.csv"
            path.write_text("clip,skill,frame,joint,x_px,y_px\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "calibrated 3D"):
                read_annotations(path)


if __name__ == "__main__":
    unittest.main()
