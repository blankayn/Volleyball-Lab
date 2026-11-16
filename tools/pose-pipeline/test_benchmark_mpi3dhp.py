import unittest
import numpy as np

from benchmark_mpi3dhp import REFERENCE_INDEX, hip_relative
from evaluate_mpjpe import JOINT_INDEX


class BenchmarkMappingTests(unittest.TestCase):
    def test_twelve_anatomical_joints_match_official_relevant_order(self):
        self.assertEqual(set(REFERENCE_INDEX),set(JOINT_INDEX))
        self.assertEqual(REFERENCE_INDEX['LEFT_WRIST'],7)
        self.assertEqual(REFERENCE_INDEX['RIGHT_WRIST'],4)
        self.assertEqual(REFERENCE_INDEX['LEFT_HIP'],11)
        self.assertEqual(REFERENCE_INDEX['RIGHT_HIP'],8)
        self.assertEqual(len(set(REFERENCE_INDEX.values())),12)

    def test_hip_translation_preserves_metric_depth_and_body_scale(self):
        points=np.zeros((17,3))
        points[11]=[-100,0,4000]
        points[8]=[100,0,4000]
        points[7]=[200,-500,4300]
        actual=hip_relative(points,11,8)
        np.testing.assert_allclose(actual[7],[200,-500,300])
        np.testing.assert_allclose(hip_relative(points+[50,100,200],11,8),actual)
        self.assertAlmostEqual(np.linalg.norm(actual[11]-actual[8]),200)

    def test_missing_hips_cannot_silently_define_a_root(self):
        points=np.zeros((17,3))
        points[11]=np.nan
        with self.assertRaisesRegex(ValueError,'hip'):
            hip_relative(points,11,8)


if __name__=='__main__':
    unittest.main()
