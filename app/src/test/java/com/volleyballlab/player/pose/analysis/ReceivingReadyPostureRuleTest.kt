package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.pose.TestPoses
import com.volleyballlab.player.pose.model.BodyPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceivingReadyPostureRuleTest {

    @Test
    fun `straight knees trigger the knee correction`() {
        assertEquals(
            ReceivingReadyPostureRule.Issue.Knees,
            ReceivingReadyPostureRule.issue(kneeDegrees = 160f, trunkForwardDegrees = 30f),
        )
        val score = ReceivingReadyPostureRule.scoreOverride(
                CueId.PassKneeFlexion,
                kneeDegrees = 160f,
                trunkForwardDegrees = 30f,
            )
        assertEquals(0f, score ?: -1f, 0f)
    }

    @Test
    fun `moderately straight knees only fail when trunk is upright`() {
        assertEquals(
            ReceivingReadyPostureRule.Issue.Trunk,
            ReceivingReadyPostureRule.issue(kneeDegrees = 150f, trunkForwardDegrees = 5f),
        )
        assertNull(
            ReceivingReadyPostureRule.issue(kneeDegrees = 150f, trunkForwardDegrees = 20f),
        )
    }

    @Test
    fun `missing ankle makes receiving posture unjudgeable`() {
        val complete = TestPoses.wellFramedStanding()
        assertTrue(ReceivingReadyPostureRule.hasReliableFullLegs(complete))

        val image = complete.image.toMutableList()
        image[BodyPoint.LEFT_ANKLE.ordinal] =
            image[BodyPoint.LEFT_ANKLE.ordinal].copy(visibility = 0.39f)
        assertFalse(ReceivingReadyPostureRule.hasReliableFullLegs(complete.copy(image = image)))
    }
}
