package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.pose.TestPoses
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.Landmark
import com.volleyballlab.player.pose.model.PoseFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The set's elbow cues are read where they happen. The window's contact is the
 * peak of hand height, after the push, and its follow-through is the last frame,
 * with the hands coming down; neither is when the elbows are bent at the catch
 * or fullest at the finish.
 */
class SetElbowCuesTest {

    private val analyzer = SetAnalyzer()
    private val bend = FormCueCatalog.byId(CueId.SetElbowFlexion)!!
    private val finish = FormCueCatalog.byId(CueId.SetExtensionFollowThrough)!!

    /** Elbow angles over a set: catch at 100°, push to 175° as the hands peak, back down bent. */
    private val elbows = List(6) { 100f } + listOf(120f, 145f, 165f) + List(4) { 175f } + listOf(160f, 140f, 125f, 120f, 120f)

    private val frames = elbows.mapIndexed { i, angle -> setPose(i * 33L, angle) }

    /** Contact on the highest hands, follow-through on the last frame, as the segmenter places them. */
    private val window = RepWindow(
        index = 0,
        frames = frames,
        phaseFrames = mapOf(
            RepPhase.Preparation to frames.first(),
            RepPhase.Loading to frames.first(),
            RepPhase.Contact to frames.maxBy { AnalyzerSupport.meanWristRise(it)!! },
            RepPhase.FollowThrough to frames.last(),
        ),
        baselineHipImageY = Angles.hipCentreImage(frames.first()).y,
    )

    @Test
    fun `the poses read back the elbow angles they were built with`() {
        elbows.zip(frames).forEach { (angle, frame) ->
            assertEquals(angle, AnalyzerSupport.meanElbowAngle(frame)!!, 0.5f)
            assertTrue(AnalyzerSupport.meanWristRise(frame)!! >= 0.30f)
        }
        assertEquals(175f, AnalyzerSupport.meanElbowAngle(window.frameFor(RepPhase.Contact)!!)!!, 0.5f)
    }

    @Test
    fun `elbow bend is read at the catch, not where the hands peak`() {
        assertEquals(100f, analyzer.measure(bend, window)!!, 1f)
        val catch = analyzer.cueFrame(bend, window)
        assertNotNull(catch)
        assertTrue(catch!!.timestampMs < window.contactMs)
    }

    @Test
    fun `arm finish is read at the fullest extension, not the last frame`() {
        assertEquals(175f, analyzer.measure(finish, window)!!, 1f)
        val at = analyzer.cueFrame(finish, window)!!
        assertTrue(at.timestampMs < window.endMs)
        assertEquals(175f, AnalyzerSupport.meanElbowAngle(at)!!, 0.5f)
    }

    @Test
    fun `cues other than the elbow cues keep their phase frames`() {
        val hands = FormCueCatalog.byId(CueId.SetHandsAboveForehead)!!
        assertSame(window.frameFor(RepPhase.Contact), analyzer.cueFrame(hands, window))
    }

    @Test
    fun `a window without its contact falls back to the phase frame`() {
        val live = RepWindow(0, listOf(frames.last()), mapOf(RepPhase.FollowThrough to frames.last()), 0.5f)
        assertSame(frames.last(), analyzer.cueFrame(finish, live))
        assertEquals(120f, analyzer.measure(finish, live)!!, 1f)
    }

    /**
     * A front-on standing pose with both upper arms straight up and the forearms
     * folded in to [elbow] degrees, so the straighter the arms the higher the hands. Image points follow
     * the world points at one pixel scale, so the picture and the world agree.
     */
    private fun setPose(timestampMs: Long, elbow: Float): PoseFrame {
        val base = TestPoses.wellFramedStanding(timestampMs)
        val world = base.world.toMutableList()
        val image = base.image.toMutableList()
        val pixelsPerMetre = 740f
        fun place(point: BodyPoint, x: Float, y: Float) {
            world[point.ordinal] = TestPoses.landmark(x = x, y = y)
            image[point.ordinal] = TestPoses.landmark(
                x = 0.5f + x * pixelsPerMetre / base.imageWidth,
                y = 0.50f + y * pixelsPerMetre / base.imageHeight,
            )
        }
        for ((shoulder, elbowPoint, wrist, outward) in listOf(
            Arm(BodyPoint.LEFT_SHOULDER, BodyPoint.LEFT_ELBOW, BodyPoint.LEFT_WRIST, -1f),
            Arm(BodyPoint.RIGHT_SHOULDER, BodyPoint.RIGHT_ELBOW, BodyPoint.RIGHT_WRIST, 1f),
        )) {
            val sx = outward * 0.18f
            val sy = -0.45f
            val up = Math.toRadians(0.0)
            val ux = outward * sin(up).toFloat()
            val uy = -cos(up).toFloat()
            val ex = sx + 0.28f * ux
            val ey = sy + 0.28f * uy
            // Fold the forearm in toward the head by the bend: 180 - elbow degrees.
            val fold = -outward * Math.toRadians(180.0 - elbow)
            val fx = (ux * cos(fold) - uy * sin(fold)).toFloat()
            val fy = (ux * sin(fold) + uy * cos(fold)).toFloat()
            place(shoulder, sx, sy)
            place(elbowPoint, ex, ey)
            place(wrist, ex + 0.26f * fx, ey + 0.26f * fy)
        }
        // Shoulders and hips on the same pixel scale as the arms, so the torso ruler matches.
        place(BodyPoint.LEFT_HIP, -0.09f, 0f)
        place(BodyPoint.RIGHT_HIP, 0.09f, 0f)
        return base.copy(world = world, image = image)
    }

    private data class Arm(val shoulder: BodyPoint, val elbow: BodyPoint, val wrist: BodyPoint, val outward: Float)
}
