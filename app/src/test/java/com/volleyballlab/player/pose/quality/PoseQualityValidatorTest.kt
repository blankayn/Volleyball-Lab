package com.volleyballlab.player.pose.quality

import com.volleyballlab.player.pose.TestPoses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseQualityValidatorTest {

    private val validator = PoseQualityValidator()

    @Test
    fun `a well framed athlete is accepted`() {
        val quality = validator.validate(TestPoses.wellFramedStanding())

        assertTrue("Unexpected issues: ${quality.issues}", quality.isAcceptable)
    }

    @Test
    fun `no frame at all reports that nobody is detected`() {
        val quality = validator.validate(null)

        assertEquals(PoseQualityIssue.NoPersonDetected, quality.primaryIssue)
    }

    @Test
    fun `an athlete too far from the camera is asked to move closer`() {
        // Half size about the frame centre: roughly 30% coverage, under the 45% floor.
        val small = TestPoses.scaledAboutCentre(TestPoses.wellFramedStanding(), 0.5f)

        val quality = validator.validate(small)

        assertEquals(PoseQualityIssue.TooFar, quality.primaryIssue)
    }

    @Test
    fun `an athlete filling the frame is flagged as too close`() {
        // The standing pose spans 56% of frame height, so it needs a factor above
        // 1.70 to break the 95% ceiling.
        val large = TestPoses.scaledAboutCentre(TestPoses.wellFramedStanding(), 1.75f)

        val quality = validator.validate(large)

        // TooClose and PartialBody genuinely co-occur here, because an athlete
        // large enough to exceed the coverage ceiling has already pushed their
        // extremities past the frame edge. Both are true and both have the same
        // fix, so the assertion is on membership rather than on which one wins.
        assertTrue(quality.issues.contains(PoseQualityIssue.TooClose))
        assertFalse(quality.isAcceptable)
    }

    @Test
    fun `an athlete off to one side is asked to centre themselves`() {
        val shifted = TestPoses.shiftedHorizontally(TestPoses.wellFramedStanding(), 0.3f)

        val quality = validator.validate(shifted)

        assertTrue(quality.issues.contains(PoseQualityIssue.OffCentre))
    }

    @Test
    fun `very low visibility reads as nobody in frame`() {
        val faint = TestPoses.withVisibility(TestPoses.wellFramedStanding(), 0.2f)

        val quality = validator.validate(faint)

        assertEquals(PoseQualityIssue.NoPersonDetected, quality.primaryIssue)
    }

    @Test
    fun `weak but present tracking reports low confidence`() {
        // Above the 0.5 per-landmark floor and the 0.3 presence floor, but under
        // the 0.6 mean the gate requires.
        val weak = TestPoses.withVisibility(TestPoses.wellFramedStanding(), 0.55f)

        val quality = validator.validate(weak)

        assertTrue(quality.issues.contains(PoseQualityIssue.LowConfidence))
    }

    @Test
    fun `a motionless athlete is never reported as unstable`() {
        val frame = TestPoses.wellFramedStanding()

        // More frames than the stability window, all identical.
        repeat(30) { validator.validate(frame) }
        val quality = validator.validate(frame)

        assertFalse(quality.issues.contains(PoseQualityIssue.Unstable))
    }

    @Test
    fun `a drifting scene is reported as unstable`() {
        val base = TestPoses.wellFramedStanding()

        // Well beyond the 0.012 spread threshold, alternating either side so the
        // centroid's spread about its mean is large.
        repeat(30) { index ->
            val offset = if (index % 2 == 0) 0.05f else -0.05f
            validator.validate(TestPoses.shiftedHorizontally(base, offset))
        }
        val quality = validator.validate(TestPoses.shiftedHorizontally(base, 0.05f))

        assertTrue(quality.issues.contains(PoseQualityIssue.Unstable))
    }

    @Test
    fun `stability history is cleared when the athlete leaves frame`() {
        val base = TestPoses.wellFramedStanding()
        repeat(30) { index ->
            val offset = if (index % 2 == 0) 0.05f else -0.05f
            validator.validate(TestPoses.shiftedHorizontally(base, offset))
        }

        // A dropped frame resets history, so the shake does not haunt the next
        // attempt once the athlete steps back in and stands still.
        validator.validate(null)
        repeat(30) { validator.validate(base) }

        assertTrue(validator.validate(base).isAcceptable)
    }

    @Test
    fun `the highest priority issue is the one surfaced`() {
        val tiny = TestPoses.scaledAboutCentre(TestPoses.wellFramedStanding(), 0.3f)

        val quality = validator.validate(tiny)

        // TooFar (priority 3) outranks OffCentre and LowConfidence, and the body
        // stays inside the frame at this scale so PartialBody does not apply.
        assertEquals(PoseQualityIssue.TooFar, quality.primaryIssue)
    }
}
