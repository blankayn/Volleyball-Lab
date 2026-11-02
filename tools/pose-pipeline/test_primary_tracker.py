from pathlib import Path
import re
from types import SimpleNamespace
import unittest

from extract import MIN_DETECTION_CONFIDENCE, MIN_PRESENCE_CONFIDENCE, MIN_TRACKING_CONFIDENCE, NUM_POSES, PrimaryPoseTracker


def person(offset=0.):
    points = [SimpleNamespace(x=.5 + offset, y=.5, z=0., visibility=1., presence=1.) for _ in range(33)]
    for index, (x, y) in {11:(.45,.35),12:(.55,.35),13:(.4,.48),14:(.6,.48),
        15:(.35,.60),16:(.65,.60),23:(.45,.65),24:(.55,.65),25:(.45,.78),
        26:(.55,.78),27:(.45,.95),28:(.55,.95)}.items():
        points[index].x=x+offset; points[index].y=y
    return points


class PrimaryTrackerTests(unittest.TestCase):
    def test_pose_defaults_match_actual_android_source(self):
        source=(Path(__file__).resolve().parents[2]/'app/src/main/java/com/volleyballlab/player/pose/MediaPipePoseDetector.kt').read_text(encoding='utf-8')
        confidence=float(re.search(r'MIN_PERSON_CONFIDENCE\s*=\s*([\d.]+)f',source).group(1))
        count=int(re.search(r'setNumPoses\((\d+)\)',source).group(1))
        self.assertEqual(NUM_POSES,count)
        self.assertTrue(all(value==confidence for value in (MIN_DETECTION_CONFIDENCE,MIN_PRESENCE_CONFIDENCE,MIN_TRACKING_CONFIDENCE)))

    def test_acquisition_needs_three_consistent_frames(self):
        tracker=PrimaryPoseTracker()
        self.assertIsNone(tracker.select([person()],0))
        self.assertIsNone(tracker.select([person()],33))
        self.assertEqual(tracker.select([person()],66),0)

    def test_missing_player_never_unlocks_to_a_distant_bystander(self):
        tracker=PrimaryPoseTracker()
        for timestamp in (0,33,66): tracker.select([person()],timestamp)
        for i in range(20): self.assertIsNone(tracker.select([],100+i*33))
        for i in range(20): self.assertIsNone(tracker.select([person(.3)],1000+i*33))

    def test_long_gap_requires_reconfirmation(self):
        tracker=PrimaryPoseTracker()
        for timestamp in (0,33,66): tracker.select([person()],timestamp)
        self.assertIsNone(tracker.select([person()],1000))
        self.assertIsNone(tracker.select([person()],1033))
        self.assertEqual(tracker.select([person()],1066),0)

    def test_one_hidden_shoulder_still_locates_a_body_but_face_only_does_not(self):
        tracker=PrimaryPoseTracker(); pose=person(); pose[11].visibility=0
        for timestamp in (0,33): self.assertIsNone(tracker.select([pose],timestamp))
        self.assertEqual(tracker.select([pose],66),0)
        face=person()
        for index in tracker.BODY: face[index].visibility=0
        self.assertIsNone(PrimaryPoseTracker().select([face],0))


if __name__=='__main__': unittest.main()
