from pathlib import Path
import tempfile
import unittest

import numpy as np

from blocking_arm_dataset import NAMES, SCOPE, build, choose_arm, frame_vector, reliable_frames, sequence
from temporal_models import augment_training


class BlockingArmDatasetTests(unittest.TestCase):
    def setUp(self):
        self.world = np.zeros((32, 33, 3))
        points = {0: (0, -.7, .1), 11: (-.2, -.5, 0), 12: (.2, -.5, 0),
                  13: (-.3, -.8, .1), 14: (.3, -.8, .1), 15: (-.3, -1, .15),
                  16: (.3, -1, .15), 23: (-.2, 0, 0), 24: (.2, 0, 0)}
        for index, point in points.items():
            self.world[:, index] = point
        self.image = np.full((32, 33, 2), .5)
        self.image[:, 15:17, 1] = .2
        self.visibility = np.ones((32, 33))
        self.valid = np.ones(32, dtype=bool)

    def test_legs_do_not_enter_the_arm_only_quality_contract(self):
        self.image[:, 25:29] = 4
        self.visibility[:, 25:29] = 0
        self.assertTrue(reliable_frames(self.image, self.world, self.visibility, self.valid).all())

    def test_hidden_wrist_is_not_interpolated_into_an_observation(self):
        self.visibility[12, 15] = 0
        X, quality = sequence(self.image, self.world, self.visibility, self.valid)
        self.assertTrue(np.isnan(X[12, :-1]).all())
        self.assertEqual(X[12, -1], 0)
        self.assertTrue(np.isfinite(X[11, :-1]).all())
        self.assertEqual(quality, 31 / 32)

    def test_unused_joint_values_do_not_change_arm_features(self):
        before = frame_vector(self.image[0], self.world[0])
        self.world[:, 25:29] = np.nan
        np.testing.assert_allclose(frame_vector(self.image[0], self.world[0]), before)
        self.assertTrue(np.isfinite(before).all())

    def test_hidden_opposite_arm_does_not_enter_visible_arm_features(self):
        before = frame_vector(self.image[0], self.world[0], "left")
        self.world[:, [14, 16]] = np.nan
        self.visibility[:, [14, 16]] = 0
        np.testing.assert_allclose(frame_vector(self.image[0], self.world[0], "left"), before)
        self.assertEqual(choose_arm(self.image, self.world, self.visibility, self.valid), "left")

    def test_arm_selection_is_fixed_for_the_cycle_not_switched_per_frame(self):
        self.visibility[:16, 15] = 0
        self.visibility[16:, 16] = 0
        arm = choose_arm(self.image, self.world, self.visibility, self.valid)
        _, quality = sequence(self.image, self.world, self.visibility, self.valid, arm)
        self.assertEqual(quality, .5)

    def test_matching_left_and_right_arm_geometry_uses_the_same_feature_convention(self):
        expected = frame_vector(self.image[0], self.world[0], "left")
        world, image = self.world[0].copy(), self.image[0].copy()
        for left, right in ((11, 12), (13, 14), (15, 16), (23, 24)):
            world[[left, right]] = world[[right, left]]
            image[[left, right]] = image[[right, left]]
        world[:, 0] *= -1
        image[:, 0] = 1 - image[:, 0]
        np.testing.assert_allclose(frame_vector(image, world, "right"), expected)

    def test_missing_frames_remain_explicit_in_nearest_time_warp(self):
        X = np.zeros((2, 32, len(NAMES)), dtype=np.float32)
        X[:, :, 0] = np.arange(32)
        X[:, :, -1] = np.arange(32) % 2
        augmented = augment_training(X, list(NAMES), 2, 7)
        self.assertEqual(set(np.unique(augmented[:, :, -1])), {0, 1})
        for row in augmented.reshape(-1, len(NAMES)):
            self.assertTrue(np.any(np.all(X[0] == row, axis=1)))

    def test_dataset_keeps_source_scope_and_observation_contract(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "clip_sequences.npz"
            values = dict(schema_version=2, sequences=np.zeros((1, 32, 24)),
                labels=np.array(["fault"]), skill=np.array(["block"]), segments=np.array([0]),
                too_short=np.array([False]), task_scope=np.array([SCOPE]), source_sha256=np.array(["a" * 64]))
            np.savez(source, **values)
            np.savez(root / "clip_landmarks.npz", image=self.image, world=self.world,
                     visibility=self.visibility, valid=self.valid, segment_of_frame=np.zeros(32))
            out = root / "out_sequences.npz"
            record = build(source, out)
            self.assertEqual(record["quality"], [1.])
            with np.load(out) as result:
                self.assertEqual(result["sequences"].shape, (1, 32, len(NAMES)))
                self.assertEqual(result["source_sha256"].tolist(), ["a" * 64])
                self.assertEqual(result["task_scope"].tolist(), [SCOPE])
            values["task_scope"] = np.array(["whole_block_execution"])
            np.savez(source, **values)
            with self.assertRaisesRegex(ValueError, "explicitly reviewed"):
                build(source, out)
            values.update(sequences=np.zeros((2, 32, 24)), labels=np.array(["fault", "fault"]),
                skill=np.repeat("block", 2), segments=np.array([0, 1]), too_short=np.array([False, False]),
                task_scope=np.repeat(SCOPE, 2), source_sha256=np.repeat("a" * 64, 2))
            np.savez(source, **values)
            with self.assertRaisesRegex(ValueError, "continuous shot"):
                build(source, out)


if __name__ == "__main__":
    unittest.main()
