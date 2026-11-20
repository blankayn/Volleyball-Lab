package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.pose.TestPoses
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame
import com.volleyballlab.player.pose.analysis.PersonalElbowBaseline.Check
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalElbowBaselineTest {
    // wellFramedStanding: shoulder line at y=0.24, hips at y=0.50, so one torso is 0.26.
    private fun elbowY(rise: Float) = 0.24f - rise * 0.26f

    /** Right hand swings overhead; the left hand stays low, so the hitting side is unambiguous. */
    private fun frame(t: Long, elbowRise: Float, elbowVisibility: Float = 1f): PoseFrame {
        val base = TestPoses.wellFramedStanding(t)
        val image = base.image.toMutableList()
        image[BodyPoint.RIGHT_WRIST.ordinal] = image[BodyPoint.RIGHT_WRIST.ordinal].copy(y = 0.05f)
        image[BodyPoint.RIGHT_ELBOW.ordinal] = image[BodyPoint.RIGHT_ELBOW.ordinal]
            .copy(y = elbowY(elbowRise), visibility = elbowVisibility, presence = elbowVisibility)
        val world = base.world.toMutableList()
        world[BodyPoint.RIGHT_ELBOW.ordinal] = world[BodyPoint.RIGHT_ELBOW.ordinal]
            .copy(visibility = elbowVisibility, presence = elbowVisibility)
        return base.copy(image = image, world = world)
    }

    /** A rep whose elbow climbs to [peak] at contact, then optionally goes higher afterwards. */
    private fun rep(peak: Float, afterContact: Float? = null, visibility: Float = 1f): RepWindow {
        val frames = listOf(frame(0, 0.1f, visibility), frame(100, peak * 0.6f, visibility), frame(200, peak, visibility)) +
            listOfNotNull(afterContact?.let { frame(300, it, visibility) })
        return RepWindow(0, frames, mapOf(RepPhase.Contact to frames[2]), 0.5f)
    }

    @Test fun elbowRiseIsTorsoLengthsAboveTheShoulderLine() {
        assertEquals(0.5f, PersonalElbowBaseline.elbowRise(frame(0, 0.5f), Side.Right)!!, 1e-4f)
        assertEquals(0.5f, PersonalElbowBaseline.peakElbowRise(rep(0.5f))!!, 1e-4f)
    }

    @Test fun onlyFramesUpToContactCount() {
        assertEquals(0.4f, PersonalElbowBaseline.peakElbowRise(rep(0.4f, afterContact = 0.9f))!!, 1e-4f)
    }

    @Test fun nothingIsJudgedUntilTwoRepsAreMeasured() {
        val baseline = PersonalElbowBaseline()
        assertTrue(baseline.observe(rep(0.6f)) is Check.WarmingUp)
        assertTrue(baseline.observe(rep(0.1f)) is Check.WarmingUp)
        assertTrue(baseline.observe(rep(0.1f)) is Check.Dropped)
    }

    @Test fun aRepNearTheBestIsHeldAndAClearlyLowerOneIsDropped() {
        val baseline = PersonalElbowBaseline()
        baseline.observe(rep(0.60f)); baseline.observe(rep(0.55f))
        assertTrue(baseline.observe(rep(0.40f)) is Check.Held)
        val dropped = baseline.observe(rep(0.30f))
        assertTrue(dropped is Check.Dropped)
        assertEquals(0.30f, (dropped as Check.Dropped).dropTorsoLengths, 1e-4f)
        assertTrue(dropped.announce)
    }

    @Test fun aRunOfLowRepsIsAnnouncedOncePerCooldown() {
        val baseline = PersonalElbowBaseline()
        baseline.observe(rep(0.6f)); baseline.observe(rep(0.6f))
        val announced = (1..5).map {
            (baseline.observe(rep(0.2f)) as Check.Dropped).announce.also { said -> if (said) baseline.announced() }
        }
        assertEquals(listOf(true, false, false, true, false), announced)
    }

    @Test fun aDropThatWasNotSaidCanBeSaidOnTheNextLowRep() {
        val baseline = PersonalElbowBaseline()
        baseline.observe(rep(0.6f)); baseline.observe(rep(0.6f))
        assertTrue((baseline.observe(rep(0.2f)) as Check.Dropped).announce) // another correction took the turn
        assertTrue((baseline.observe(rep(0.2f)) as Check.Dropped).announce)
    }

    @Test fun anUntrustedElbowIsUnmeasuredAndLeavesTheBaselineAlone() {
        val baseline = PersonalElbowBaseline()
        baseline.observe(rep(0.6f))
        assertEquals(Check.Unmeasured, baseline.observe(rep(0.9f, visibility = 0.2f)))
        assertNull(PersonalElbowBaseline.peakElbowRise(rep(0.9f, visibility = 0.2f)))
        assertTrue(baseline.observe(rep(0.2f)) is Check.WarmingUp)
        assertTrue(baseline.observe(rep(0.6f)) is Check.Held)
    }

    @Test fun aNewViewStartsANewBaseline() {
        val baseline = PersonalElbowBaseline()
        baseline.observe(rep(0.8f), view = "side"); baseline.observe(rep(0.8f), view = "side")
        assertTrue(baseline.observe(rep(0.2f), view = "side") is Check.Dropped)
        assertTrue(baseline.observe(rep(0.2f), view = "front") is Check.WarmingUp)
        assertFalse(baseline.observe(rep(0.2f), view = "front") is Check.Dropped)
    }
}
