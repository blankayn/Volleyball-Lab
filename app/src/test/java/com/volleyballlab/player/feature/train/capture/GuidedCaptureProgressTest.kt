package com.volleyballlab.player.feature.train.capture

import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.pose.quality.PoseQuality
import org.junit.Assert.*
import org.junit.Test

class GuidedCaptureProgressTest {
    @Test fun `requested side view displays fresh counters during turn and countdown`() {
        val front = CaptureUiState.Recording(
            sessionId = "progress", elapsedMs = 30_000, liveRepCount = 5, quality = PoseQuality.NotYetKnown,
            stageMetrics = LiveCaptureMetrics(reps = 5, validReps = 3),
            totalMetrics = LiveCaptureMetrics(reps = 5, validReps = 3),
        )
        val options = GuidedCaptureOptions()
        assertEquals(CaptureProgressDisplay(CameraAngle.Front, front.stageMetrics), front.displayedProgress(options))
        val turn = front.copy(rotation = RotationUi(CameraAngle.Side, 1, message = "Turn sideways"))
        val expected = CaptureProgressDisplay(CameraAngle.Side, LiveCaptureMetrics())
        assertEquals(expected, turn.displayedProgress(options))
        assertEquals(expected, turn.copy(rotation = turn.rotation!!.copy(secondsLeft = 3)).displayedProgress(options))
        assertEquals(5, turn.totalMetrics.reps)
    }

    @Test fun `active side counters and free practice keep their own totals`() {
        val side = CaptureUiState.Recording(
            sessionId = "progress", elapsedMs = 40_000, liveRepCount = 7, quality = PoseQuality.NotYetKnown,
            angle = CameraAngle.Side, stageIndex = 1,
            stageMetrics = LiveCaptureMetrics(reps = 2, validReps = 1),
            totalMetrics = LiveCaptureMetrics(reps = 7, validReps = 4),
        )
        val options = GuidedCaptureOptions()
        assertEquals(CaptureProgressDisplay(CameraAngle.Side, side.stageMetrics), side.displayedProgress(options))
        assertEquals(side.totalMetrics, side.displayedProgress(options.copy(enabled = false)).metrics)
        val turning = side.copy(angle = CameraAngle.Front, stageIndex = 0, rotation = RotationUi(CameraAngle.Side, 1, message = "Turn side on"))
        assertEquals(CaptureProgressDisplay(CameraAngle.Side, LiveCaptureMetrics()), turning.displayedProgress(options))
    }

    @Test fun requiredStageStopTableAndLimit() {
        val options = GuidedCaptureOptions()
        // Both remaining views are required; nothing can be skipped.
        assertFalse(GuidedCaptureProgress.canSkip(0)); assertFalse(GuidedCaptureProgress.canSkip(1))
        assertTrue(GuidedCaptureProgress.isRequired(0)); assertTrue(GuidedCaptureProgress.isRequired(1))
        assertEquals(GuidedCaptureProgress.StopDecision.ContinueToSide(), GuidedCaptureProgress.stopDecision(0, 1, options))
        assertEquals(GuidedCaptureProgress.StopDecision.Blocked(2), GuidedCaptureProgress.stopDecision(1, 3, options))
        assertEquals(GuidedCaptureProgress.StopDecision.Finish, GuidedCaptureProgress.stopDecision(1, 5, options))
        assertEquals(GuidedCaptureProgress.StopDecision.Finish, GuidedCaptureProgress.stopDecision(2, 0, options))
        assertEquals(GuidedCaptureProgress.StopDecision.Finish, GuidedCaptureProgress.stopDecision(0, 0, options.copy(enabled = false)))
        // Two views of at most ten reps stay inside the five-minute floor.
        assertEquals(300_000L, GuidedCaptureProgress.recordingLimit(options.copy(repsPerAngle = 10)))
        assertEquals(90_000L, GuidedCaptureProgress.recordingLimit(options.copy(enabled = false)))
    }

    @Test fun `five and ten completed reps advance even when form needs correction`() {
        listOf(5, 10).forEach { target ->
            val options = GuidedCaptureOptions(repsPerAngle = target)
            var metrics = LiveCaptureMetrics()
            repeat(target - 1) { metrics = metrics.completed(30, true, false) }
            assertFalse(GuidedCaptureProgress.reachedTarget(options, metrics))
            metrics = metrics.completed(30, true, false)
            assertTrue(GuidedCaptureProgress.reachedTarget(options, metrics))
            assertEquals(0, metrics.validReps)
        }
    }

    @Test fun `uncertain reps never earn success and break streak`() {
        var metrics = LiveCaptureMetrics().completed(90, true, true).completed(95, true, true)
        metrics = metrics.completed(99, false, true)
        assertEquals(3, metrics.reps)
        assertEquals(2, metrics.validReps)
        assertEquals(0, metrics.streak)
        assertEquals(2, metrics.bestStreak)
        assertEquals(92, metrics.estimatedFormScore)
    }

    @Test fun `interruption retains completed reps but ends streak`() {
        val metrics = LiveCaptureMetrics().completed(90, true, true).interrupted()
        assertEquals(1, metrics.reps)
        assertEquals(1, metrics.validReps)
        assertEquals(0, metrics.streak)
    }

    @Test fun `ordinary sessions do not rotate and side ends the guided sequence`() {
        assertEquals(listOf(CameraAngle.Front, CameraAngle.Side), GuidedCaptureProgress.angles)
        assertFalse(GuidedCaptureProgress.reachedTarget(GuidedCaptureOptions(false, 5), LiveCaptureMetrics(reps = 30)))
        assertEquals(1, GuidedCaptureProgress.nextStage(0))
        assertNull(GuidedCaptureProgress.nextStage(1))
    }
}
