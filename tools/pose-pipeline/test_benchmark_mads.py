import csv
from pathlib import Path
import tempfile
import unittest

import numpy as np

from benchmark_mads import REFERENCE_INDEX, camera_coordinates
from benchmark_mpi3dhp import hip_relative
from evaluate_mpjpe import JOINT_INDEX, evaluate, read_annotations


class MadsProtocolTests(unittest.TestCase):
    def test_supplied_extrinsics_preserve_scale_and_rotate_depth(self):
        points = np.zeros((19, 3))
        points[2] = [-100, 0, 0]
        points[6] = [100, 0, 0]
        points[12] = [0, 400, 200]
        camera = camera_coordinates(points, [np.pi/2, 0, 0], [100, 200, 3000])
        relative = hip_relative(camera, 2, 6)
        np.testing.assert_allclose(relative[12], [0, -200, 400], atol=1e-9)
        self.assertAlmostEqual(np.linalg.norm(relative[2]-relative[6]), 200)

    def test_invalid_reference_frame_is_rejected(self):
        points = np.zeros((19, 3))
        points[18] = np.nan
        with self.assertRaisesRegex(ValueError, 'finite'):
            camera_coordinates(points, [0,0,0], [0,0,0])

    def test_unspecified_volleyball_cannot_satisfy_five_skill_target(self):
        self.assertEqual(set(REFERENCE_INDEX), set(JOINT_INDEX))
        self.assertEqual(REFERENCE_INDEX['LEFT_HIP'], 2)
        self.assertEqual(REFERENCE_INDEX['RIGHT_WRIST'], 16)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            truth = root/'truth.csv'
            with truth.open('w', newline='') as stream:
                writer = csv.writer(stream)
                writer.writerow(('clip','skill','frame','joint','x_mm','y_mm','z_mm'))
                writer.writerow(('demo','volleyball_unspecified',0,'LEFT_WRIST',0,0,100))
            world = np.zeros((1,33,3))
            world[0,15,2] = .1
            np.savez(root/'demo_landmarks.npz', schema_version=2,
                     world=world, visibility=np.ones((1,33)))
            report = evaluate(root, read_annotations(truth))
            self.assertAlmostEqual(report['overall']['mpjpe_mm'], 0)
            self.assertIsNone(report['five_skill_target_met'])
            self.assertEqual(len(report['skills_without_valid_annotations']), 5)
            self.assertIn('volleyball_unspecified', report['by_skill'])


if __name__ == '__main__':
    unittest.main()
