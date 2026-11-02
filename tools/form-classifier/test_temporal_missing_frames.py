import importlib.util
import unittest

import numpy as np

from temporal_models import build_model


@unittest.skipUnless(importlib.util.find_spec("tensorflow"), "TensorFlow is needed for the graph contract test")
class TemporalMissingFrameTests(unittest.TestCase):
    def test_gap_does_not_create_fake_motion_from_imputed_pose_values(self):
        from tensorflow import keras
        names = ["measurement", "observed_frame"]
        model = build_model("tcn", 32, 2, names)
        padded = next(layer for layer in model.layers if isinstance(layer, keras.layers.ZeroPadding1D))
        differences = keras.Model(model.input, padded.output)
        X = np.ones((1, 32, 2), dtype=np.float32)
        X[0, :, 0] = np.arange(32)
        X[0, 10] = 0  # unobserved pose, imputed to zero by normalization
        delta = differences(X, training=False).numpy()
        np.testing.assert_array_equal(delta[0, [0, 10, 11]], 0)
        self.assertEqual(delta[0, 9, 0], 1)
        self.assertEqual(delta[0, 12, 0], 1)


if __name__ == "__main__":
    unittest.main()
