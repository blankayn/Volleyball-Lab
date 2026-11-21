package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.pose.TestPoses
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The spike's elbow-height cue reads the cocked arm, side-on: a dropped elbow
 * happens before contact, and at contact a dropped-elbow swing still finishes
 * straight overhead.
 */
class SpikeElbowHeightTest {

    private val analyzer = SpikeAnalyzer()
    private val cue = FormCueCatalog.byId(CueId.SpikeElbowHeight)!!

    @Test
    fun `the test poses read as side-on`() {
        assertEquals(CameraAngle.Side, CameraOrientationDetector().detect(sidePose(0L, 20f)).angle)
    }

    @Test
    fun `a dropped elbow at the cocked arm reads below the shoulder and scores low`() {
        val window = swing(cockDegrees = -20f)
        val measured = analyzer.measure(cue, window)!!
        assertEquals(-20f, measured, 1f)
        assertTrue(cue.score(measured)!! < 0.8f)
        // Read before contact, where the arm is cocked, not at contact.
        assertTrue(analyzer.cueFrame(cue, window)!!.timestampMs < window.contactMs)
    }

    @Test
    fun `a high elbow at the cocked arm is in range`() {
        val measured = analyzer.measure(cue, swing(cockDegrees = 20f))!!
        assertEquals(20f, measured, 1f)
        assertEquals(1f, cue.score(measured)!!, 0.001f)
    }

    @Test
    fun `the arm-extension cue at contact cannot tell the two swings apart`() {
        val extension = FormCueCatalog.byId(CueId.SpikeArmExtension)!!
        val dropped = analyzer.measure(extension, swing(cockDegrees = -20f))!!
        val high = analyzer.measure(extension, swing(cockDegrees = 20f))!!
        assertEquals(high, dropped, 0.5f)
    }

    @Test
    fun `a swing not filmed side-on is not measured`() {
        val frames = TestPoses.wellFramedStanding(0L).let { listOf(it, it.copy(timestampMs = 33L), it.copy(timestampMs = 66L)) }
        val window = RepWindow(0, frames, mapOf(RepPhase.Contact to frames[1]), 0.5f)
        assertNull(analyzer.measure(cue, window))
    }

    /**
     * Cocked for a while with the upper arm [cockDegrees] above horizontal, then
     * the arm swings up to straight overhead at contact.
     */
    private fun swing(cockDegrees: Float): RepWindow {
        val angles = List(8) { cockDegrees } + listOf(50f, 70f, 85f) + List(4) { 90f }
        val frames = angles.mapIndexed { i, degrees -> sidePose(i * 33L, degrees) }
        val contact = frames.maxBy { AnalyzerSupport.wristRise(it, Side.Left)!! }
        return RepWindow(0, frames, mapOf(RepPhase.Preparation to frames.first(), RepPhase.Contact to contact,
            RepPhase.FollowThrough to frames.last()), Angles.hipCentreImage(frames.first()).y)
    }

    /**
     * Side-on, left side to the camera: the hitting (left) upper arm points back
     * [upperArm] degrees above horizontal, the forearm straight up; the right arm
     * hangs. World depth separates the shoulders and hips, the picture overlaps
     * them, as a side view does. Image points follow world points at one pixel scale.
     */
    private fun sidePose(timestampMs: Long, upperArm: Float): PoseFrame {
        val base = TestPoses.wellFramedStanding(timestampMs)
        val world = base.world.toMutableList()
        val image = base.image.toMutableList()
        val pixelsPerMetre = 740f
        fun place(point: BodyPoint, x: Float, y: Float, z: Float) {
            world[point.ordinal] = TestPoses.landmark(x = x, y = y, z = z)
            image[point.ordinal] = TestPoses.landmark(
                x = 0.5f + x * pixelsPerMetre / base.imageWidth,
                y = 0.55f + y * pixelsPerMetre / base.imageHeight,
            )
        }
        place(BodyPoint.LEFT_SHOULDER, 0f, -0.45f, -0.18f)
        place(BodyPoint.RIGHT_SHOULDER, 0.01f, -0.45f, 0.18f)
        place(BodyPoint.LEFT_HIP, 0f, 0f, -0.09f)
        place(BodyPoint.RIGHT_HIP, 0.01f, 0f, 0.09f)
        place(BodyPoint.LEFT_KNEE, 0f, 0.45f, -0.10f)
        place(BodyPoint.RIGHT_KNEE, 0.01f, 0.45f, 0.10f)
        place(BodyPoint.LEFT_ANKLE, 0f, 0.88f, -0.10f)
        place(BodyPoint.RIGHT_ANKLE, 0.01f, 0.88f, 0.10f)
        place(BodyPoint.NOSE, 0.10f, -0.62f, 0f)
        place(BodyPoint.RIGHT_ELBOW, 0.01f, -0.17f, 0.18f)
        place(BodyPoint.RIGHT_WRIST, 0.01f, 0.09f, 0.18f)
        val rad = Math.toRadians(upperArm.toDouble())
        val ex = -0.28f * cos(rad).toFloat()
        val ey = -0.45f - 0.28f * sin(rad).toFloat()
        place(BodyPoint.LEFT_ELBOW, ex, ey, -0.18f)
        place(BodyPoint.LEFT_WRIST, ex, ey - 0.26f, -0.18f)
        return base.copy(world = world, image = image)
    }
}
