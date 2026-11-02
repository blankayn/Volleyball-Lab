import unittest
from pathlib import Path
import tempfile
import numpy as np
from feature_quality import annotate, reliable_frames, REQUIRED


class FeatureCoverageTests(unittest.TestCase):
    def setUp(self):
        self.image = np.full((2, 33, 2), 0.5)
        self.world = np.ones((2, 33, 3))
        self.visibility = np.ones((2, 33))
        self.valid = np.ones(2, dtype=bool)

    def result(self):
        return reliable_frames(self.image, self.world, self.visibility, self.valid).tolist()

    def test_unused_ear_does_not_reject_observed_body_features(self):
        self.visibility[:, 7] = 0
        self.assertEqual(self.result(), [True, True])

    def test_every_feature_landmark_is_required(self):
        for point in REQUIRED:
            with self.subTest(point=point):
                self.visibility[:] = 1
                self.visibility[0, point] = 0.49
                self.assertEqual(self.result(), [False, True])

    def test_inferred_offscreen_ankle_cannot_pass_high_visibility(self):
        self.image[0, 27, 1] = 1.01
        self.assertEqual(self.result(), [False, True])

    def test_invalid_skeleton_and_nonfinite_geometry_fail(self):
        self.valid[0] = False
        self.world[1, 11, 0] = np.nan
        self.assertEqual(self.result(), [False, False])

    def test_misaligned_landmark_arrays_are_rejected(self):
        with self.assertRaises(ValueError):
            reliable_frames(self.image[:1], self.world, self.visibility, self.valid)

    def test_annotation_preserves_original_quality_and_source_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sequence = root / "clip_sequences.npz"
            np.savez(sequence, segments=np.array([4, 7]), sequences=np.ones((2, 32, 2)),
                     usable_frac=np.array([0.1, 0.2]), source_group=np.array(["original", "original"]))
            image = np.full((4, 33, 2), 0.5)
            visibility = np.ones((4, 33))
            visibility[:, 7] = 0
            visibility[2, 27] = 0
            np.savez(root / "clip_landmarks.npz", image=image, world=np.ones((4, 33, 3)),
                     visibility=visibility, valid=np.ones(4, bool), segment_of_frame=np.array([4, 4, 7, 7]))
            output = root / "annotated.npz"
            annotate(sequence, output)
            with np.load(output) as result:
                np.testing.assert_array_equal(result["usable_frac"], [0.1, 0.2])
                np.testing.assert_array_equal(result["feature_usable_frac"], [1, 0.5])
                self.assertEqual(result["source_group"].tolist(), ["original", "original"])
            with np.load(sequence) as original:
                self.assertNotIn("feature_usable_frac", original.files)


if __name__ == "__main__":
    unittest.main()
