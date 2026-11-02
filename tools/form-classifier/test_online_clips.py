from pathlib import Path
import tempfile
import unittest

from prepare_online_clips import validate


class SelectionValidationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "abcdefghijk.mp4").write_bytes(b"fixture")
        self.row = dict(id="abcdefghijk", skill="block", label="fault", start=1.0, end=4.0,
                        evidence="Creator identifies the demonstrated technique error")

    def tearDown(self):
        self.temp.cleanup()

    def test_valid_provisional_selection(self):
        validate([self.row], self.root)

    def test_conflicting_or_duplicate_windows_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "overlap"):
            validate([self.row, {**self.row, "label": "ok", "start": 3}], self.root)

    def test_path_cannot_escape_source_directory(self):
        with self.assertRaisesRegex(ValueError, "identifier"):
            validate([{**self.row, "id": "../outside"}], self.root)

    def test_unbounded_or_nonfinite_timestamps_are_rejected(self):
        for changes in ({"end": float("nan")}, {"start": -1}, {"end": 25}, {"start": "1.0"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate([{**self.row, **changes}], self.root)

    def test_action_label_cannot_be_used_as_form_label(self):
        with self.assertRaises(ValueError):
            validate([{**self.row, "label": "block"}], self.root)

    def test_missing_evidence_is_rejected(self):
        with self.assertRaises(ValueError):
            validate([{**self.row, "evidence": ""}], self.root)

    def test_task_scope_must_be_a_valid_identifier(self):
        validate([{**self.row, "task_scope": "block_to_attack_retreat"}], self.root)
        for scope in ("whole skill?!", "", 4):
            with self.assertRaises(ValueError):
                validate([{**self.row, "task_scope": scope}], self.root)

    def test_crop_cannot_extend_beyond_frame_or_have_zero_size(self):
        for crop in ([0.8, 0, 0.5, 1], [0, 0, 0, 1], [0, 0, float("nan"), 1]):
            with self.subTest(crop=crop), self.assertRaises(ValueError):
                validate([{**self.row, "crop": crop}], self.root)

    def test_athlete_crop_preserves_source_identity(self):
        validate([{**self.row, "crop": [0.2, 0.1, 0.5, 0.8]}], self.root)


if __name__ == "__main__":
    unittest.main()
