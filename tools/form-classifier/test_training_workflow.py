"""Check split isolation at the orchestration boundary without fitting fake data."""
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import numpy as np

from coach_dataset import Dataset
from evaluation import connected_groups
import train_coach_system as runner


class TrainingWorkflowTests(unittest.TestCase):
    def test_locked_selection_never_trains_on_tune_calibration_or_test(self):
        rows, labels = [], []
        for role, groups in (("train", 2), ("tune", 2), ("calibration", 20), ("test", 20)):
            for group in range(groups):
                for label in (0, 1):
                    rows.append(dict(sample_id=str(len(rows)), partition=role,
                        task_scope="whole_skill_form",
                        source_group=f"{role}-{group}", athlete_id=f"{role}-{group}",
                        label_verified="true", actor_verified="true", complete_rep="true",
                        athlete_verified="true"))
                    labels.append(label)
        X = np.zeros((len(rows), 32, 1), dtype=np.float32)
        X[:, 0, 0] = np.arange(len(rows))
        data = Dataset(X, np.array(labels), ["identifier"], rows, connected_groups(rows), 0)
        calls = []

        def fake_predict(fitted, samples):
            indices = samples[:, 0, 0].astype(int)
            roles = {rows[i]["partition"] for i in indices}
            self.assertEqual(len(roles), 1)
            role = next(iter(roles))
            calls.append(role)
            probabilities = np.array([[.99, .01] if labels[i] == 0 else [.01, .99] for i in indices])
            # A bad locked test must disable an otherwise supported policy.
            return probabilities[:, ::-1] if role == "test" else probabilities

        with patch.object(runner, "fit", return_value=object()) as fit, \
             patch.object(runner, "predict", side_effect=fake_predict), \
             patch.object(runner, "export", return_value={}) as export:
            result = runner.locked(data, [("linear", .001), ("tcn", .001)],
                SimpleNamespace(epochs=1, augment_copies=0, quality_field="feature_usable_frac"), Path("unused"))
        self.assertEqual(calls, ["tune", "tune", "calibration", "test"])
        self.assertEqual(len(fit.call_args_list), 3)
        expected_train = [i for i, row in enumerate(rows) if row["partition"] == "train"]
        for call in fit.call_args_list:
            self.assertEqual(call.args[1].tolist(), expected_train)
        self.assertEqual(result["calibration"]["policy"], "selective")
        self.assertEqual(result["published_policy"]["policy"], "abstain")
        self.assertFalse(result["deployment_approved"])
        self.assertEqual(export.call_args.args[4]["policy"], "abstain")


if __name__ == "__main__":
    unittest.main()
