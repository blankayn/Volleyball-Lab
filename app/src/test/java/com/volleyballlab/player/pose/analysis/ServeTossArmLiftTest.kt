package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.pose.TestPoses
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The guiding-arm lift is read where that arm is highest before contact. The
 * preparation frame opens the window, before the arm has moved.
 */
class ServeTossArmLiftTest {

    private val analyzer = ServeAnalyzer()
    private val cue = FormCueCatalog.byId(CueId.ServeTossArmLift)!!

    /** Right (guiding) arm raised from the vertical by these degrees, frame by frame; left arm hits. */
    private val raises = listOf(10f, 10f, 40f, 80f, 120f, 125f, 120f, 60f, 30f)
    private val frames = raises.mapIndexed { i, degrees -> pose(i * 33L, degrees) }
    private val window = RepWindow(
        0, frames,
        mapOf(RepPhase.Preparation to frames.first(), RepPhase.Contact to frames.last(), RepPhase.FollowThrough to frames.last()),
        0.5f,
    )

    @Test
    fun `the lift is read at the guiding arm's highest, not the preparation frame`() {
        val highest = frames[raises.indexOf(125f)]
        assertSame(highest, analyzer.cueFrame(cue, window))
        val measured = analyzer.measure(cue, window)!!
        assertEquals(AnalyzerSupport.shoulderAngle(highest, Side.Right)!!, measured, 0.01f)
        assertTrue(measured > AnalyzerSupport.shoulderAngle(frames.first(), Side.Right)!! + 90f)
        assertEquals(1f, cue.score(measured)!!, 0.001f)
    }

    @Test
    fun `a window without contact falls back to the preparation frame`() {
        val live = RepWindow(0, frames.take(1), mapOf(RepPhase.Preparation to frames.first()), 0.5f)
        assertSame(frames.first(), analyzer.cueFrame(cue, live))
    }

    /** The left wrist is the higher one, so the left arm hits and the right guides. */
    private fun pose(timestampMs: Long, raise: Float): PoseFrame {
        val base = TestPoses.wellFramedStanding(timestampMs)
        val world = base.world.toMutableList()
        val image = base.image.toMutableList()
        val rad = Math.toRadians(raise.toDouble())
        val dx = sin(rad).toFloat()
        val dy = cos(rad).toFloat()
        world[BodyPoint.RIGHT_ELBOW.ordinal] = TestPoses.landmark(x = 0.18f + 0.28f * dx, y = -0.45f + 0.28f * dy)
        world[BodyPoint.RIGHT_WRIST.ordinal] = TestPoses.landmark(x = 0.18f + 0.54f * dx, y = -0.45f + 0.54f * dy)
        image[BodyPoint.LEFT_WRIST.ordinal] = TestPoses.landmark(x = 0.41f, y = 0.05f)
        return base.copy(world = world, image = image)
    }
}
