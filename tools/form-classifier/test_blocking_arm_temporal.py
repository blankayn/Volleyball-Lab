import unittest
import numpy as np
from blocking_arm_temporal import window, sample_indices
import test_blocking_arm_dataset as legacy


class TemporalBlockingTests(unittest.TestCase):
    def setUp(self):
        base=legacy.BlockingArmDatasetTests(); base.setUp()
        self.image,self.world,self.visibility=base.image,base.world,base.visibility
        self.presence=np.ones((32,33))
        self.detected=np.ones(32,dtype=bool)
        self.times=np.arange(32,dtype=np.int64)*33

    def build(self):
        return window(self.image,self.world,self.visibility,self.presence,self.detected,self.times)

    def test_missing_detections_equal_absent_android_pose_events(self):
        self.detected[12:17]=False
        expected=self.build()
        keep=self.detected
        actual=window(*(value[keep] for value in (self.image,self.world,self.visibility,self.presence,self.detected,self.times)))
        np.testing.assert_allclose(expected[0],actual[0],equal_nan=True)
        self.assertTrue((expected[0][:,-1]==0).any())
        self.assertTrue(np.isnan(expected[0][expected[0][:,-1]==0,:-1]).all())

    def test_camera_timestamp_jitter_changes_sampling_by_time_not_event_index(self):
        times=np.array([0,33,66,99,132,165,198,231,264,297,330,363,396,429,462,495,
            528,561,594,627,660,693,726,759,792,825,858,891,924,957,990,1023])
        indices,_=sample_indices(times)
        np.testing.assert_array_equal(indices,np.arange(32))
        times[8:16]+=12
        jittered,_=sample_indices(times)
        self.assertEqual(len(jittered),32)
        # Removing a burst must not compress the complete remaining trajectory.
        keep=np.ones(32,dtype=bool); keep[8:13]=False
        indices,observed=sample_indices(times[keep])
        self.assertFalse(observed[10])
        self.assertEqual(times[keep][indices[-1]],1023)

    def test_presence_and_unconsumed_world_joints_have_consistent_scope(self):
        self.presence[:,15]=0
        self.assertEqual(self.build()[2],'right')
        self.world[:,27]=np.nan
        self.assertEqual(self.build()[2],'right')
        self.presence[:,16]=np.nan
        self.assertEqual(self.build()[1],0)

    def test_timestamp_resets_long_gaps_and_partial_windows_reject(self):
        for times in (self.times[:9],self.times//10,self.times[::-1],np.r_[self.times[:16],self.times[16:]+501]):
            with self.subTest(times=times),self.assertRaises(ValueError): sample_indices(times)

    def test_nearest_ties_choose_earlier_pose_without_float_rounding(self):
        times=np.r_[np.arange(11,dtype=np.int64)*60,930]
        indices,mask=sample_indices(times)
        self.assertEqual(indices[0],0)
        self.assertEqual(indices[-1],11)
        self.assertEqual(indices[1],0)  # target 30ms ties original 0ms and 60ms
        self.assertFalse(mask.all())  # the large final gap remains unobserved

if __name__=='__main__': unittest.main()
