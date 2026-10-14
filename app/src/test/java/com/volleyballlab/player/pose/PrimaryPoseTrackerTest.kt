package com.volleyballlab.player.pose

import com.volleyballlab.player.pose.model.Landmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrimaryPoseTrackerTest {
    @Test fun confirmsPlayerBeforeDisplayingSkeleton() {
        confirm(PrimaryPoseTracker(), pose(0.5f))
    }

    @Test fun keepsPlayerWhenDetectorOrderChanges() {
        val tracker = PrimaryPoseTracker()
        confirm(tracker, pose(0.5f))
        val bystander = pose(0.85f)
        assertEquals(1, tracker.select(listOf(bystander, pose(0.52f))))
    }

    @Test fun doesNotSwapToBystanderWhenPlayerLeaves() {
        val tracker = PrimaryPoseTracker()
        confirm(tracker, pose(0.5f))
        assertNull(tracker.select(listOf(pose(0.85f))))
        assertEquals(0, tracker.select(listOf(pose(0.51f))))
    }

    @Test fun doesNotAdoptBystanderAfterPlayerHasBeenAbsent() {
        val tracker = PrimaryPoseTracker()
        confirm(tracker, pose(0.5f))
        repeat(60) { assertNull(tracker.select(listOf(pose(0.85f)), it * 100L)) }
        confirm(tracker, pose(0.51f), 6_100L)
    }

    @Test fun reconfirmsReturningPlayerAfterHalfSecondEvenWithSlowInference() {
        val tracker = PrimaryPoseTracker()
        confirm(tracker, pose(0.5f), 0L)
        assertNull(tracker.select(emptyList(), 200L))
        assertNull(tracker.select(listOf(pose(0.85f)), 800L))
        confirm(tracker, pose(0.51f), 900L)
    }

    @Test fun reconfirmsAfterSilentDetectorGap() {
        val tracker = PrimaryPoseTracker()
        confirm(tracker, pose(0.5f), 0L)
        confirm(tracker, pose(0.51f), 1_000L)
    }

    @Test fun explicitResetAllowsADifferentPlayer() {
        val tracker = PrimaryPoseTracker()
        confirm(tracker, pose(0.5f))
        tracker.reset()
        confirm(tracker, pose(0.85f))
    }

    @Test fun keepsPlayerWhenOneShoulderIsOccluded() {
        val tracker = PrimaryPoseTracker()
        confirm(tracker, pose(0.5f))
        val turnedPlayer = pose(0.52f).mapIndexed { index, landmark ->
            if (index == 11) landmark.copy(visibility = 0.1f) else landmark
        }
        assertEquals(1, tracker.select(listOf(pose(0.85f), turnedPlayer)))
    }

    @Test fun acceptsSideViewWithoutRequiringSeparatedLeftAndRightPoints() {
        val side = pose(0.5f).map { it.copy(x = 0.5f) }
        confirm(PrimaryPoseTracker(), side)
    }

    @Test fun rejectsHighVisibilityWhenBodyPresenceIsWeak() {
        val tracker = PrimaryPoseTracker()
        val objectProposal = pose(0.5f).map { it.copy(presence = 0.2f) }
        repeat(6) { assertNull(tracker.select(listOf(objectProposal))) }
    }

    @Test fun rejectsCollapsedObjectLikeJointCluster() {
        val tracker = PrimaryPoseTracker()
        val cluster = pose(0.5f).map { it.copy(x = 0.5f, y = 0.5f) }
        repeat(6) { assertNull(tracker.select(listOf(cluster))) }
    }

    @Test fun rejectsTorsoOnlyProposal() {
        val tracker = PrimaryPoseTracker()
        val torsoOnly = pose(0.5f).mapIndexed { index, point ->
            if (index in listOf(11, 12, 23, 24)) point else point.copy(presence = 0.1f)
        }
        repeat(6) { assertNull(tracker.select(listOf(torsoOnly))) }
    }

    @Test fun rejectsNonFiniteCoordinates() {
        val invalid = pose(0.5f).map { it.copy(y = Float.NaN) }
        assertNull(PrimaryPoseTracker().select(listOf(invalid)))
    }

    @Test fun rejectsSuddenlyDifferentBodyScaleAtSamePosition() {
        val tracker = PrimaryPoseTracker()
        confirm(tracker, pose(0.5f))
        val smallerPerson = TestPoses.scaledAboutCentre(TestPoses.wellFramedStanding(), 0.5f).image
        assertNull(tracker.select(listOf(smallerPerson)))
        assertEquals(0, tracker.select(listOf(pose(0.5f))))
    }

    @Test fun missingFrameBreaksInitialConfirmation() {
        val tracker = PrimaryPoseTracker()
        assertNull(tracker.select(listOf(pose(0.5f))))
        assertNull(tracker.select(emptyList()))
        confirm(tracker, pose(0.5f))
    }

    private fun confirm(tracker: PrimaryPoseTracker, player: List<Landmark>, time: Long? = null) {
        assertNull(tracker.select(listOf(player), time))
        assertNull(tracker.select(listOf(player), time?.plus(50L)))
        assertEquals(0, tracker.select(listOf(player), time?.plus(100L)))
    }

    private fun pose(x: Float): List<Landmark> =
        TestPoses.shiftedHorizontally(TestPoses.wellFramedStanding(), x - 0.5f).image
}
