import copy
import unittest
import numpy as np
from blocking_arm_temporal import window, RECIPE
from verify_blocking_device import recompute
import test_blocking_arm_dataset as legacy


class DeviceVerificationTests(unittest.TestCase):
    def setUp(self):
        # Synthetic geometry verifies the verifier, not actual device evidence.
        base=legacy.BlockingArmDatasetTests(); base.setUp()
        keep=np.ones(32,dtype=bool); keep[12:17]=False
        times=np.arange(32)*33
        presence=np.ones((32,33))
        X,quality,arm,_=window(base.image,base.world,base.visibility,presence,keep,times)
        frames=[dict(timestamp_ms=int(times[i]),world=base.world[i].tolist(),
            image=np.c_[base.image[i],base.visibility[i],presence[i]].tolist()) for i in np.flatnonzero(keep)]
        self.record=dict(feature_recipe=RECIPE,replay_window=dict(frames=frames,arm=arm,quality=quality,
            raw_features=X.tolist(),normalized_input=np.nan_to_num(X).reshape(-1).tolist()))
        self.metadata=dict(feature_recipe=RECIPE,normalisation=dict(mean=[0.]*7,std=[1.]*7),disabled_features=[])

    def test_emitted_pose_replay_recomputes_without_compressing_missing_time(self):
        _,checks=recompute(self.record,self.metadata)
        self.assertLess(checks['max_feature_difference'],1e-4)
        self.assertLess(checks['max_normalized_input_difference'],1e-4)

    def test_phone_cannot_hide_unobserved_frames_by_replacing_them_with_zeros(self):
        corrupted=copy.deepcopy(self.record)
        for row in corrupted['replay_window']['raw_features']:
            if row[-1]==0:
                row[:-1]=[0.]*6
        with self.assertRaisesRegex(ValueError,'masks'):
            recompute(corrupted,self.metadata)

    def test_wrong_normalization_is_rejected_even_when_raw_features_match(self):
        self.record['replay_window']['normalized_input'][0]+=1.
        with self.assertRaisesRegex(ValueError,'normalization'):
            recompute(self.record,self.metadata)

    def test_old_recipe_and_wrong_arm_cannot_be_promoted(self):
        self.record['replay_window']['arm']='right'
        with self.assertRaisesRegex(ValueError,'arm'):
            recompute(self.record,self.metadata)
        self.record['feature_recipe']='blocking_arm_dataset_v1'
        with self.assertRaisesRegex(ValueError,'V2'):
            recompute(self.record,self.metadata)

if __name__=='__main__': unittest.main()
