import unittest

from prepare_mpi3dhp_benchmark import select_frames


class BenchmarkSelectionTests(unittest.TestCase):
    def test_selection_is_uniform_only_over_official_valid_frames(self):
        self.assertEqual(select_frames([1,0,1,1,0,1,0,1],3),[0,3,7])
        self.assertEqual(select_frames([0,1,0],1),[1])

    def test_selection_cannot_repeat_frames_to_inflate_support(self):
        with self.assertRaisesRegex(ValueError,'count'):
            select_frames([1,0,1],3)

    def test_nonbinary_or_nonfinite_reference_flags_are_rejected(self):
        for flags in ([0,.5,1],[1,float('nan')]):
            with self.assertRaisesRegex(ValueError,'binary'):
                select_frames(flags,1)


if __name__=='__main__':
    unittest.main()
