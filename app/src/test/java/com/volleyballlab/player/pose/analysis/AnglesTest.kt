package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.pose.TestPoses
import com.volleyballlab.player.pose.model.BodyPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnglesTest {

    @Test
    fun `straight limb measures 180 degrees`() {
        // Shoulder above, elbow at the joint, wrist below: a fully extended arm.
        val frame = TestPoses.frame(
            world = mapOf(
                BodyPoint.LEFT_SHOULDER to TestPoses.landmark(y = -1f),
                BodyPoint.LEFT_ELBOW to TestPoses.landmark(y = 0f),
                BodyPoint.LEFT_WRIST to TestPoses.landmark(y = 1f),
            ),
        )

        val angle = Angles.at(
            frame,
            BodyPoint.LEFT_SHOULDER,
            BodyPoint.LEFT_ELBOW,
            BodyPoint.LEFT_WRIST,
        )

        assertEquals(180f, angle!!, TOLERANCE)
    }

    @Test
    fun `perpendicular segments measure 90 degrees`() {
        val frame = TestPoses.frame(
            world = mapOf(
                BodyPoint.LEFT_SHOULDER to TestPoses.landmark(x = 1f),
                BodyPoint.LEFT_ELBOW to TestPoses.landmark(),
                BodyPoint.LEFT_WRIST to TestPoses.landmark(y = 1f),
            ),
        )

        val angle = Angles.at(
            frame,
            BodyPoint.LEFT_SHOULDER,
            BodyPoint.LEFT_ELBOW,
            BodyPoint.LEFT_WRIST,
        )

        assertEquals(90f, angle!!, TOLERANCE)
    }

    /**
     * The contract that keeps a tracking dropout from being scored as bad form:
     * a degenerate triple must be unmeasurable, not zero.
     */
    @Test
    fun `coincident landmarks give null rather than zero`() {
        val frame = TestPoses.frame(
            world = mapOf(
                BodyPoint.LEFT_SHOULDER to TestPoses.landmark(),
                BodyPoint.LEFT_ELBOW to TestPoses.landmark(),
                BodyPoint.LEFT_WRIST to TestPoses.landmark(y = 1f),
            ),
        )

        assertNull(
            Angles.at(frame, BodyPoint.LEFT_SHOULDER, BodyPoint.LEFT_ELBOW, BodyPoint.LEFT_WRIST),
        )
    }

    @Test
    fun `upright trunk has zero forward lean`() {
        val frame = TestPoses.wellFramedStanding()
        assertEquals(0f, Angles.trunkForwardLean(frame)!!, TOLERANCE)
    }

    /**
     * The head has to travel with the torso.
     *
     * This fixture previously moved the shoulders 0.45 m toward the lens and left
     * the nose where it was, which put the nose 0.39 m *behind* the shoulder line
     * - a pose no body can hold, and one that reads as facing away. It passed only
     * because [Angles.trunkForwardLean] used to measure against camera z and never
     * asked which way the athlete faced. Now that it asks, the fixture has to be
     * physically coherent, so the nose keeps its offset from the shoulders.
     */
    @Test
    fun `leaning forward is positive forward lean`() {
        val base = TestPoses.wellFramedStanding()
        // Shoulders 0.45 m above the hips and 0.45 m nearer the camera: 45 degrees.
        // The nose stays 0.06 m in front of the shoulder line and 0.17 m above it,
        // exactly as it sits in the standing pose.
        val leaning = base.copy(
            world = base.world.toMutableList().also { points ->
                points[BodyPoint.LEFT_SHOULDER.ordinal] =
                    TestPoses.landmark(x = -0.18f, y = -0.45f, z = -0.45f)
                points[BodyPoint.RIGHT_SHOULDER.ordinal] =
                    TestPoses.landmark(x = 0.18f, y = -0.45f, z = -0.45f)
                points[BodyPoint.NOSE.ordinal] =
                    TestPoses.landmark(x = 0f, y = -0.62f, z = -0.51f)
            },
        )

        assertEquals(45f, Angles.trunkForwardLean(leaning)!!, TOLERANCE)
    }

    /**
     * The bug this fix exists for: the same lean, with the athlete turned around,
     * used to read -45 and score zero on a cue whose band is entirely positive.
     */
    @Test
    fun `leaning forward reads positive with the athlete facing away too`() {
        val base = TestPoses.wellFramedStanding()
        // Turned 180 degrees: shoulders swap sides, and both the torso and the
        // nose lean away from the lens - which is now the athlete's forward.
        val leaning = base.copy(
            world = base.world.toMutableList().also { points ->
                points[BodyPoint.LEFT_SHOULDER.ordinal] =
                    TestPoses.landmark(x = 0.18f, y = -0.45f, z = 0.45f)
                points[BodyPoint.RIGHT_SHOULDER.ordinal] =
                    TestPoses.landmark(x = -0.18f, y = -0.45f, z = 0.45f)
                points[BodyPoint.NOSE.ordinal] =
                    TestPoses.landmark(x = 0f, y = -0.62f, z = 0.51f)
            },
        )

        assertEquals(45f, Angles.trunkForwardLean(leaning)!!, TOLERANCE)
    }

    /** With no usable face, facing is unknowable and the lean must decline to answer. */
    @Test
    fun `forward lean is unmeasurable when the nose cannot resolve facing`() {
        val base = TestPoses.wellFramedStanding()
        val noFace = base.copy(
            world = base.world.toMutableList().also { points ->
                points[BodyPoint.NOSE.ordinal] = TestPoses.landmark(y = -0.62f)
            },
        )

        assertNull(Angles.trunkForwardLean(noFace))
    }

    /**
     * The calibration that makes jump height measurable at all.
     *
     * World space knows the torso is 0.45 m. Image space knows that same torso
     * spans 0.26 of frame height. Their ratio converts image displacement into
     * metres, which is the only way to get a real jump height out of a
     * hip-centred coordinate system.
     */
    @Test
    fun `vertical scale is derived from the athlete's own torso`() {
        val frame = TestPoses.wellFramedStanding()

        val scale = Angles.verticalScale(frame)

        assertNotNull(scale)
        // Torso: 0.45 m in world space, 0.26 in normalised image units.
        assertEquals(0.45f / 0.26f, scale!!, 0.05f)
    }

    @Test
    fun `hip rise converts image displacement into metres`() {
        val frame = TestPoses.wellFramedStanding()
        val scale = Angles.verticalScale(frame)!!
        val hipImageY = Angles.hipCentreImage(frame).y

        // Baseline a tenth of the frame below the current hips: the athlete has
        // risen by that much, converted through the calibrated scale.
        val rise = Angles.hipRiseMetres(frame, baselineImageY = hipImageY + 0.1f)

        assertEquals(0.1f * scale, rise!!, TOLERANCE)
    }

    @Test
    fun `hips at the baseline means no rise`() {
        val frame = TestPoses.wellFramedStanding()
        val hipImageY = Angles.hipCentreImage(frame).y

        assertEquals(0f, Angles.hipRiseMetres(frame, hipImageY)!!, TOLERANCE)
    }

    @Test
    fun `level shoulders have near-zero tilt`() {
        val frame = TestPoses.wellFramedStanding()
        assertTrue(Angles.shoulderTilt(frame)!! < 1f)
    }

    /**
     * The case the shared fixture cannot express, and the reason a real bug
     * survived every test in this file.
     *
     * World x is a camera axis, so the vector left-shoulder to right-shoulder
     * reverses when the athlete turns around. `wellFramedStanding` lays the
     * shoulders out rear-view, so it only ever exercised one of the two. Real
     * footage is 93% front-facing, where the unfolded measurement returned ~180
     * and failed the level-shoulders cue on essentially every rep.
     *
     * Built inline rather than by adding a facing parameter to the fixture:
     * `wellFramedStanding` is shared with the trunk-lean and facing-camera tests,
     * whose expectations would move with it.
     */
    @Test
    fun `level shoulders read zero from the front too`() {
        val frontOn = TestPoses.frame(
            world = mapOf(
                BodyPoint.LEFT_SHOULDER to TestPoses.landmark(x = 0.18f, y = -0.45f),
                BodyPoint.RIGHT_SHOULDER to TestPoses.landmark(x = -0.18f, y = -0.45f),
            ),
        )

        assertEquals(0f, Angles.shoulderTilt(frontOn)!!, TOLERANCE)
    }

    /** A real tilt measures the same from either side. Ten degrees is ten degrees. */
    @Test
    fun `tilt magnitude does not depend on which way the athlete faces`() {
        fun tilted(leftX: Float, rightX: Float) = TestPoses.frame(
            world = mapOf(
                BodyPoint.LEFT_SHOULDER to TestPoses.landmark(x = leftX, y = -0.45f),
                BodyPoint.RIGHT_SHOULDER to
                    TestPoses.landmark(x = rightX, y = -0.45f + 0.0635f),
            ),
        )

        val rearView = Angles.shoulderTilt(tilted(-0.18f, 0.18f))!!
        val frontView = Angles.shoulderTilt(tilted(0.18f, -0.18f))!!

        assertEquals(10f, rearView, 1f)
        assertEquals(rearView, frontView, TOLERANCE)
    }

    @Test
    fun `facing the camera squarely scores near one`() {
        // The standing pose has both shoulders at the same depth, so the shoulder
        // line is entirely width and no depth: fully front-on.
        assertEquals(1f, Angles.facingCamera(TestPoses.wellFramedStanding()), TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.5f
    }
}
