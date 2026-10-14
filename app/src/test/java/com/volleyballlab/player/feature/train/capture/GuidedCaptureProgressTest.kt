package com.volleyballlab.player.feature.train.capture

import com.volleyballlab.player.domain.model.CameraAngle
import org.junit.Assert.*
import org.junit.Test

class GuidedCaptureProgressTest {
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

    @Test fun `ordinary sessions do not rotate and rear ends the guided sequence`() {
        assertEquals(listOf(CameraAngle.Front, CameraAngle.Side, CameraAngle.Rear), GuidedCaptureProgress.angles)
        assertFalse(GuidedCaptureProgress.reachedTarget(GuidedCaptureOptions(false, 5), LiveCaptureMetrics(reps = 30)))
        assertEquals(1, GuidedCaptureProgress.nextStage(0))
        assertNull(GuidedCaptureProgress.nextStage(2))
    }
}
