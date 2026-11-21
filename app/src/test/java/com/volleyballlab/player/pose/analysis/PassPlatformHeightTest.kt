package com.volleyballlab.player.pose.analysis

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
 * Platform height is the slope of the forearm platform at contact. Arms lifted
 * to about shoulder height are still a pass, and score low here, instead of
 * passing unnoticed as they did when only hands above the shoulders were caught.
 */
class PassPlatformHeightTest {

    private val analyzer = ReceiveAnalyzer()
    private val cue = FormCueCatalog.byId(CueId.PassPlatformHeight)!!

    @Test
    fun `a platform angled down like the reference figure is in range`() {
        val measured = analyzer.measure(cue, at(platform(belowHorizontal = 45f)))!!
        assertEquals(45f, measured, 0.5f)
        assertEquals(1f, cue.score(measured)!!, 0.001f)
    }

    @Test
    fun `a platform lifted near shoulder height scores low`() {
        val measured = analyzer.measure(cue, at(platform(belowHorizontal = 10f)))!!
        assertEquals(10f, measured, 0.5f)
        assertTrue(cue.score(measured)!! < 0.8f)
    }

    @Test
    fun `only arms the camera saw are read`() {
        val hidden = platform(belowHorizontal = 45f).let { frame ->
            frame.copy(image = frame.image.mapIndexed { i, p ->
                if (i in ARM_POINTS) p.copy(visibility = 0.1f, presence = 0.1f) else p
            })
        }
        assertNull(analyzer.measure(cue, at(hidden)))
    }

    private fun at(frame: PoseFrame) = RepWindow(0, listOf(frame), mapOf(RepPhase.Contact to frame), 0.5f)

    /** Front-on, both arms straight out toward the camera and [belowHorizontal] degrees down. */
    private fun platform(belowHorizontal: Float): PoseFrame {
        val base = TestPoses.wellFramedStanding(0L)
        val world = base.world.toMutableList()
        val rad = Math.toRadians(belowHorizontal.toDouble())
        val down = sin(rad).toFloat()
        val forward = cos(rad).toFloat()
        for ((shoulder, wrist, elbow, x) in listOf(
            Quad(BodyPoint.LEFT_SHOULDER, BodyPoint.LEFT_WRIST, BodyPoint.LEFT_ELBOW, -0.18f),
            Quad(BodyPoint.RIGHT_SHOULDER, BodyPoint.RIGHT_WRIST, BodyPoint.RIGHT_ELBOW, 0.18f),
        )) {
            world[shoulder.ordinal] = TestPoses.landmark(x = x, y = -0.45f)
            world[elbow.ordinal] = TestPoses.landmark(x = x, y = -0.45f + 0.28f * down, z = -0.28f * forward)
            world[wrist.ordinal] = TestPoses.landmark(x = x, y = -0.45f + 0.55f * down, z = -0.55f * forward)
        }
        return base.copy(world = world)
    }

    private data class Quad(val shoulder: BodyPoint, val wrist: BodyPoint, val elbow: BodyPoint, val x: Float)

    private companion object {
        val ARM_POINTS = listOf(
            BodyPoint.LEFT_SHOULDER, BodyPoint.RIGHT_SHOULDER, BodyPoint.LEFT_ELBOW,
            BodyPoint.RIGHT_ELBOW, BodyPoint.LEFT_WRIST, BodyPoint.RIGHT_WRIST,
        ).map { it.ordinal }
    }
}
