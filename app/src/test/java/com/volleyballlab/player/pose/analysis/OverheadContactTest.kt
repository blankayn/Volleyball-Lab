package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.pose.TestPoses
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contact for a serve or spike is the hit: a straight arm at its highest. After
 * the hit the body can turn and lift the other hand nearly as high, bent; that
 * moment is not contact even when it is a few pixels higher.
 */
class OverheadContactTest {

    private val serve = ServeAnalyzer()

    @Test
    fun `a bent arm rising higher after the hit does not become contact`() {
        // Left arm straight up at frame 3; right arm, bent, slightly higher at frame 6.
        val frames = List(9) { i ->
            when (i) {
                in 2..4 -> pose(i, left = Arm.Straight, leftY = if (i == 3) 0.10f else 0.14f, right = Arm.Down, rightY = 0.45f)
                in 5..7 -> pose(i, left = Arm.Down, leftY = 0.45f, right = Arm.Bent, rightY = if (i == 6) 0.09f else 0.13f)
                else -> pose(i, left = Arm.Down, leftY = 0.45f, right = Arm.Down, rightY = 0.45f)
            }
        }
        assertEquals(6, frames.indices.maxBy { AnalyzerSupport.higherWristRise(frames[it])!! })
        assertEquals(3, serve.contactIndex(frames, signalPeak = 6))
    }

    @Test
    fun `of two straight arms the later one is the hit, not the toss`() {
        // Guiding arm straight up for the toss at frame 2, hitting arm straight up at frame 6.
        val frames = List(9) { i ->
            when (i) {
                in 1..3 -> pose(i, left = Arm.Down, leftY = 0.45f, right = Arm.Straight, rightY = if (i == 2) 0.09f else 0.13f)
                in 5..7 -> pose(i, left = Arm.Straight, leftY = if (i == 6) 0.10f else 0.14f, right = Arm.Down, rightY = 0.45f)
                else -> pose(i, left = Arm.Down, leftY = 0.45f, right = Arm.Down, rightY = 0.45f)
            }
        }
        assertEquals(6, serve.contactIndex(frames, signalPeak = 2))
    }

    @Test
    fun `other skills keep the signal peak`() {
        val frames = List(5) { pose(it, Arm.Down, 0.45f, Arm.Down, 0.45f) }
        assertEquals(2, SetAnalyzer().contactIndex(frames, signalPeak = 2))
    }

    private enum class Arm { Straight, Bent, Down }

    /** Image wrist heights set the hand heights; world joints set each elbow's angle. */
    private fun pose(i: Int, left: Arm, leftY: Float, right: Arm, rightY: Float): PoseFrame {
        val base = TestPoses.wellFramedStanding(i * 33L)
        val world = base.world.toMutableList()
        val image = base.image.toMutableList()
        fun arm(elbow: BodyPoint, wrist: BodyPoint, x: Float, shape: Arm) {
            val out = if (x < 0) -1f else 1f
            when (shape) {
                Arm.Straight -> { world[elbow.ordinal] = TestPoses.landmark(x = x, y = -0.73f); world[wrist.ordinal] = TestPoses.landmark(x = x, y = -1.0f) }
                Arm.Bent -> { world[elbow.ordinal] = TestPoses.landmark(x = x, y = -0.73f); world[wrist.ordinal] = TestPoses.landmark(x = x + out * 0.12f, y = -0.80f) }
                Arm.Down -> Unit
            }
        }
        arm(BodyPoint.LEFT_ELBOW, BodyPoint.LEFT_WRIST, -0.18f, left)
        arm(BodyPoint.RIGHT_ELBOW, BodyPoint.RIGHT_WRIST, 0.18f, right)
        image[BodyPoint.LEFT_WRIST.ordinal] = TestPoses.landmark(x = 0.41f, y = leftY)
        image[BodyPoint.RIGHT_WRIST.ordinal] = TestPoses.landmark(x = 0.59f, y = rightY)
        return base.copy(world = world, image = image)
    }
}
