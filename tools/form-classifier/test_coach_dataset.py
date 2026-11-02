import csv
from pathlib import Path
import tempfile
import unittest
import numpy as np
from coach_dataset import Dataset, load_skill, locked_partitions, read_registry
from evaluation import connected_groups
from temporal_models import augment_training
from prepare_online_clips import attach_source_hash


class DatasetTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.X = np.random.default_rng(7).normal(size=(4, 32, 2))
        self.write_bundle()

    def tearDown(self):
        self.temp.cleanup()

    def write_bundle(self, **changes):
        values = dict(schema_version=2, sequences=self.X, labels=np.array(["ok", "fault", "ok", "fault"]),
                      skill=np.repeat("block", 4), segments=np.arange(4), feature_names=["knee_mean", "hitting_side"],
                      source_group=np.array(["a", "a", "b", "b"]), too_short=np.array([False, False, True, False]),
                      feature_usable_frac=np.array([.9, .8, .9, .4]))
        values.update(changes)
        np.savez(self.root / "clip_sequences.npz", **values)

    def test_only_quality_passing_long_samples_are_admitted(self):
        data = load_skill(self.root, "block", {}, "feature_usable_frac", .5)
        self.assertEqual(data.X.shape, (2, 32, 2))
        self.assertEqual(data.y.tolist(), [1, 0])
        self.assertEqual(data.excluded, 2)
        self.assertEqual(data.rows[0]["sample_id"], "clip:0")

    def test_pose_configuration_is_canonical_and_mixed_detectors_are_rejected(self):
        self.write_bundle(pose_configuration=np.array('{"num_poses": 1, "confidence": 0.65}'))
        data = load_skill(self.root, "block", {}, "feature_usable_frac", .5)
        self.assertEqual(data.rows[0]["pose_configuration"], '{"confidence":0.65,"num_poses":1}')
        with np.load(self.root / "clip_sequences.npz") as original:
            values = {key: original[key] for key in original.files}
        values["pose_configuration"] = np.array('{"num_poses":3}')
        np.savez(self.root / "other_sequences.npz", **values)
        with self.assertRaisesRegex(ValueError, "Mixed pose configurations"):
            load_skill(self.root, "block", {}, "feature_usable_frac", .5)

    def test_registry_cannot_rewrite_original_source_or_label(self):
        for change in (dict(source_group="new"), dict(label="fault")):
            with self.subTest(change=change), self.assertRaises(ValueError):
                load_skill(self.root, "block", {"clip:0": change}, "feature_usable_frac", .5)

    def test_original_source_hash_links_renamed_and_different_sequences(self):
        self.write_bundle(source_group=np.array(["renamed-a", "renamed-b", "other", "other"]))
        attach_source_hash(self.root / "clip_sequences.npz", "a" * 64)
        data = load_skill(self.root, "block", {}, "feature_usable_frac", .5)
        self.assertEqual(len(set(data.groups)), 1)
        self.assertEqual(data.rows[0]["source_sha256"], "a" * 64)
        with self.assertRaisesRegex(ValueError, "source hash"):
            load_skill(self.root, "block", {"clip:0": {"source_sha256": "b" * 64}}, "feature_usable_frac", .5)

    def test_invalid_source_hash_and_shape_are_rejected(self):
        for value in (np.array(["not-a-hash"] * 4), np.array(["a" * 64] * 3)):
            self.write_bundle(source_sha256=value)
            with self.assertRaises(ValueError):
                load_skill(self.root, "block", {}, "feature_usable_frac", .5)

    def test_narrow_task_scope_survives_extraction_and_cannot_be_relabelled(self):
        attach_source_hash(self.root / "clip_sequences.npz", "a" * 64, "block_to_attack_retreat")
        data = load_skill(self.root, "block", {}, "feature_usable_frac", .5)
        self.assertEqual({row["task_scope"] for row in data.rows}, {"block_to_attack_retreat"})
        with self.assertRaisesRegex(ValueError, "task scope"):
            load_skill(self.root, "block", {"clip:0": {"task_scope": "whole_skill_form"}}, "feature_usable_frac", .5)

    def test_narrow_task_and_whole_skill_examples_cannot_be_mixed(self):
        self.write_bundle(task_scope=np.array(["block_to_attack_retreat", "whole_skill_form", "whole_skill_form", "whole_skill_form"]))
        with self.assertRaisesRegex(ValueError, "Mixed task scopes"):
            load_skill(self.root, "block", {}, "feature_usable_frac", .5)

    def test_old_and_timestamp_feature_recipes_cannot_share_one_training_run(self):
        self.write_bundle(feature_recipe=np.array('blocking_arm_dataset_v1'))
        with np.load(self.root/'clip_sequences.npz') as original:
            values={key:original[key] for key in original.files}
        values['feature_recipe']=np.array('blocking_arm_time_v2')
        np.savez(self.root/'other_sequences.npz',**values)
        with self.assertRaisesRegex(ValueError,'Mixed feature recipes'):
            load_skill(self.root,'block',{},'feature_usable_frac',.5)

    def test_invalid_observation_masks_cannot_create_measured_frames(self):
        for mask in (0., .5, float("nan")):
            X = self.X.copy()
            X[:, :, 1] = 1
            X[0, 4, 1] = mask
            self.write_bundle(sequences=X, feature_names=["measurement", "observed_frame"])
            with self.assertRaisesRegex(ValueError, "observation mask"):
                load_skill(self.root, "block", {}, "feature_usable_frac", .5)

    def test_recorded_observed_arm_cannot_be_rewritten(self):
        self.write_bundle(observed_arm=np.repeat("left", 4))
        with self.assertRaisesRegex(ValueError, "observed arm"):
            load_skill(self.root, "block", {"clip:0": {"observed_arm": "right"}}, "feature_usable_frac", .5)

    def test_locked_evaluation_rejects_undefined_label_scope(self):
        roles = ["train", "train", "tune", "tune"]
        rows = [dict(source_group=str(i), athlete_id=str(i), partition=role,
                     label_verified="true", actor_verified="true", complete_rep="true", athlete_verified="true")
                for i, role in enumerate(roles)]
        data = Dataset(self.X, np.array([0, 1, 0, 1]), ["a", "b"], rows, connected_groups(rows), 0)
        with self.assertRaisesRegex(ValueError, "task scope"):
            locked_partitions(data)

    def test_unknown_players_cannot_be_promoted_to_locked_test(self):
        data = load_skill(self.root, "block", {}, "feature_usable_frac", .5)
        with self.assertRaises(ValueError):
            locked_partitions(data)

    def test_action_labels_are_not_form_ground_truth(self):
        self.write_bundle(labels=np.array(["block", "spike", "ok", "fault"]))
        with self.assertRaisesRegex(ValueError, "Action|action"):
            load_skill(self.root, "block", {}, "feature_usable_frac", .5)

    def test_duplicate_registry_ids_fail(self):
        path = self.root / "registry.csv"
        with path.open("w", newline="") as handle:
            writer = csv.writer(handle)
            writer.writerows([["sample_id"], ["same"], ["same"]])
        with self.assertRaises(ValueError):
            read_registry(path)

    def test_training_augmentation_does_not_mutate_or_blend_side_identity(self):
        X = np.zeros((2, 32, 3), dtype=np.float32)
        X[:, :, 0] = np.arange(32) % 2
        X[:, :, 1] = np.arange(32)
        before = X.copy()
        result = augment_training(X, ["hitting_side", "knee_mean", "disabled"], 2, 7)
        np.testing.assert_array_equal(X, before)
        self.assertEqual(result.shape, (6, 32, 3))
        self.assertEqual(set(np.unique(result[:, :, 0])), {0, 1})
        self.assertTrue((result[:, :, 2] == 0).all())
        np.testing.assert_array_equal(result[:2], before)

    def test_review_flags_cannot_override_a_player_leaking_into_test(self):
        rows = [dict(source_group=f"video-{i}", athlete_id="same-player", partition=role,
                     label_verified="true", actor_verified="true", complete_rep="true", athlete_verified="true")
                for i, role in enumerate(("train", "train", "test", "test"))]
        data = Dataset(self.X, np.array([0, 1, 0, 1]), ["a", "b"], rows, connected_groups(rows), 0)
        with self.assertRaisesRegex(ValueError, "leakage"):
            locked_partitions(data)


if __name__ == "__main__":
    unittest.main()
