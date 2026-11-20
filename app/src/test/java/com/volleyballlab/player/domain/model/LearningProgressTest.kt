package com.volleyballlab.player.domain.model

import org.junit.Assert.*
import org.junit.Test

class LearningProgressTest {
    private fun session(time: Long, score: Float, count: Int = 3, angle: CameraAngle = CameraAngle.Side) =
        SessionResult("$time", Skill.Receiving, "receive", time, 1_000,
            (0 until count).map { RepResult(it, 0, 500, 1_000, listOf(CueScore(CueId.PassKneeFlexion, 160f, score))) },
            angleStages = listOf(SessionAngleStage(0, angle, 0, 1_000, count, 0, 0, null)))
    @Test fun usesMeasuredCountsAndRecurringFocus() {
        val progress = LearningProgress.from(listOf(session(2, .5f), session(1, 0f)))
        assertEquals(CueId.PassKneeFlexion, progress.focus)
        assertEquals(3, progress.change?.currentCount)
        assertEquals(0, progress.change?.currentInRange)
    }
    @Test fun noComparisonAcrossDifferentViewsOrInsufficientEvidence() {
        assertNull(LearningProgress.from(listOf(session(2, 1f), session(1, 0f, angle = CameraAngle.Front))).change)
        assertNull(LearningProgress.from(listOf(session(2, 1f, count = 2), session(1, 0f))).change)
        assertNull(LearningProgress.from(listOf(session(2, 1f).copy(angleStages = emptyList()), session(1, 0f))).change)
    }
}
