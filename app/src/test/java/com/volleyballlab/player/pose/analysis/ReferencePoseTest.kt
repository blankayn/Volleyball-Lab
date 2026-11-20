package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.Rig
import com.volleyballlab.player.pose.TestPoses
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.Landmark
import com.volleyballlab.player.pose.model.PoseFrame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reference figure is only worth drawing if standing in it is what the app
 * rewards. These tests turn each start and finish position back into a pose
 * frame and put it through the same analyzers, scorer and movement check an
 * athlete's own frames go through.
 */
class ReferencePoseTest {

    // --- The picture agrees with the scoring -----------------------------------

    @Test
    fun `the positions the figure shows score full marks on the cues read there`() {
        Skill.All.forEach { skill ->
            Side.entries.forEach { hitting ->
                val window = window(skill, hitting)
                val rep = SessionScorer().scoreRep(window, skill, angle = CameraAngle.Front)
                // The figure draws a start and a finish. Whatever is read after
                // the finish, on the way back down, it does not claim to show.
                FormCueCatalog.poseCues(skill).filter { it.phase != RepPhase.FollowThrough }.forEach { cue ->
                    val scored = rep.cueScores.first { it.cueId == cue.id }
                    assertNotNull("$skill ${cue.id} could not be measured on the figure ($hitting-handed)", scored.measured)
                    assertTrue("$skill ${cue.id} measured ${scored.measured} on the figure, outside ${cue.target}",
                        scored.measured!! in cue.target)
                    assertEquals("$skill ${cue.id}", 1f, scored.score!!, 0f)
                }
            }
        }
    }

    @Test
    fun `moving from the start position to the finish position is a counted rep`() {
        Skill.All.forEach { skill ->
            val frames = repetitions(skill, Side.Right, count = 3, view = Facing.Forward)
            val analyzer = SkillAnalyzers.forSkill(skill)
            val counter = LiveRepCounter(analyzer)
            val faults = mutableListOf<MovementFault>()
            frames.forEach { counter.onFrame(it); counter.lastRejection?.let(faults::add) }
            assertEquals("$skill live", 3, counter.count)
            assertEquals("$skill saved", 3, RepSegmenter().segment(frames, analyzer).size)
            assertTrue("$skill: the figure's own movement was refused: $faults", faults.isEmpty())
        }
    }

    @Test
    fun `the same movement counts from the side with the hitting arm nearest the camera`() {
        Skill.All.forEach { skill ->
            // Turned to their left, a right-hander's hitting arm is the near one.
            val frames = repetitions(skill, Side.Right, count = 3, view = Facing.Left)
            val counter = LiveRepCounter(SkillAnalyzers.forSkill(skill))
            frames.forEach { counter.onFrame(it) }
            assertEquals("$skill from the side", 3, counter.count)
        }
    }

    @Test
    fun `holding either position still is not a rep`() {
        Skill.All.forEach { skill ->
            ReferenceMoment.entries.forEach { moment ->
                val pose = ReferencePoses.pose(skill, moment)
                val frames = (0 until 240).map { frame(pose, timestampMs = it * 33L) }
                val counter = LiveRepCounter(SkillAnalyzers.forSkill(skill))
                frames.forEach { counter.onFrame(it) }
                assertEquals("$skill held at $moment", 0, counter.count)
            }
        }
    }

    @Test
    fun `angles come from the cue catalog so retuning a target moves the picture`() {
        fun mid(id: CueId) = FormCueCatalog.byId(id)!!.target.let { (it.start + it.endInclusive) / 2f }
        assertEquals(mid(CueId.PassKneeFlexion), ReferencePoses.pose(Skill.Receiving, ReferenceMoment.Finish).knee, 0f)
        assertEquals(mid(CueId.PassTrunkAngle), ReferencePoses.pose(Skill.Receiving, ReferenceMoment.Finish).trunkLean, 0f)
        assertEquals(mid(CueId.SetElbowFlexion), ReferencePoses.pose(Skill.Setting, ReferenceMoment.Finish).left.elbowAngle, 0.01f)
        assertEquals(mid(CueId.BlockHandSeparation), ReferencePoses.pose(Skill.Blocking, ReferenceMoment.Finish).handGap!!, 0f)
        val serve = ReferencePoses.pose(Skill.Serving, ReferenceMoment.Finish, hitting = Side.Left)
        assertEquals(mid(CueId.ServeContactArmExtension), serve.left.elbowAngle, 0.01f)
        assertEquals(mid(CueId.ServeReachHeight), serve.left.upperElevation, 0f)
        // The same pose mirrored for the other hand.
        assertEquals(serve.left, ReferencePoses.pose(Skill.Serving, ReferenceMoment.Finish, hitting = Side.Right).right)
    }

    // --- The figure stands where the athlete stands -----------------------------

    /** An athlete of typical build, in square pixels, so one metre is one length in both directions. */
    private fun athlete(view: Facing = Facing.Forward, jitter: kotlin.random.Random? = null, timestampMs: Long = 0L) =
        Rig.Standing.frame(timestampMs, view, jitter).copy(imageWidth = 1_000, imageHeight = 1_000)

    @Test
    fun `the figure is the athlete's own size and stands on their feet`() {
        val frame = athlete()
        val placement = ReferenceFigure.placement(frame)!!
        val body = BodyProportions.observed(frame)!!
        // The rig draws 0.38 of the frame per metre.
        assertEquals(380f, placement.pixelsPerMetre, 4f)
        assertEquals(0.45f, body.torso, 0.01f)
        assertEquals(0.28f, body.upperArm, 0.01f)

        val figure = ReferenceFigure.layout(ReferencePoses.pose(Skill.Blocking, ReferenceMoment.Start, body = body),
            ReferenceMoment.Start, body, placement, Facing.Forward, frame.imageWidth, frame.imageHeight)
        val floor = maxOf(frame.image(BodyPoint.LEFT_ANKLE).y, frame.image(BodyPoint.RIGHT_ANKLE).y)
        assertEquals(floor, figure.points.getValue(BodyPoint.LEFT_ANKLE).y, 0.005f)
        assertEquals(Angles.hipCentreImage(frame).x,
            (figure.points.getValue(BodyPoint.LEFT_HIP).x + figure.points.getValue(BodyPoint.RIGHT_HIP).x) / 2f, 0.005f)
        // Anatomical left is on the same side of the picture as the athlete's own.
        assertTrue(figure.points.getValue(BodyPoint.LEFT_SHOULDER).x > figure.points.getValue(BodyPoint.RIGHT_SHOULDER).x)
        assertTrue(frame.image(BodyPoint.LEFT_SHOULDER).x > frame.image(BodyPoint.RIGHT_SHOULDER).x)
        // Bent knees bring the hips down; the feet do not leave the floor.
        assertTrue(figure.points.getValue(BodyPoint.LEFT_HIP).y > frame.image(BodyPoint.LEFT_HIP).y)
    }

    @Test
    fun `turned side-on the figure points the way the athlete would be facing`() {
        val frame = athlete()
        val placement = ReferenceFigure.placement(frame)!!
        val body = BodyProportions.observed(frame)!!
        fun figure(facing: Facing) = ReferenceFigure.layout(ReferencePoses.pose(Skill.Receiving, ReferenceMoment.Finish, body = body),
            ReferenceMoment.Finish, body, placement, facing, frame.imageWidth, frame.imageHeight)
        fun ahead(figure: GuideFigure, point: BodyPoint) =
            figure.points.getValue(point).x - Angles.hipCentreImage(frame).x

        // The same directions the pose detector reports for a real athlete.
        val left = figure(Facing.Left)
        val right = figure(Facing.Right)
        val seenLeft = Rig.Standing.frame(0L, Facing.Left)
        assertTrue(seenLeft.image(BodyPoint.NOSE).x > Angles.hipCentreImage(seenLeft).x)
        listOf(BodyPoint.NOSE, BodyPoint.LEFT_WRIST, BodyPoint.RIGHT_WRIST, BodyPoint.LEFT_FOOT_INDEX).forEach { point ->
            assertTrue("$point should lead when facing left", ahead(left, point) > 0.01f)
            assertTrue("$point should lead the other way when facing right", ahead(right, point) < -0.01f)
        }
        // The feet are what stays put. A bent-knee stance sits the hips back behind them.
        listOf(left, right).forEach { figure ->
            val feet = (figure.points.getValue(BodyPoint.LEFT_ANKLE).x + figure.points.getValue(BodyPoint.RIGHT_ANKLE).x) / 2f
            assertEquals((frame.image(BodyPoint.LEFT_ANKLE).x + frame.image(BodyPoint.RIGHT_ANKLE).x) / 2f, feet, 0.002f)
        }
        assertTrue(ahead(left, BodyPoint.LEFT_HIP) < -0.005f)
        assertTrue(ahead(right, BodyPoint.LEFT_HIP) > 0.005f)
        // Forward and away put the platform on the midline and swap the sides.
        val forward = figure(Facing.Forward)
        val away = figure(Facing.Away)
        assertEquals(0f, ahead(forward, BodyPoint.NOSE), 0.001f)
        assertTrue(forward.points.getValue(BodyPoint.LEFT_SHOULDER).x > forward.points.getValue(BodyPoint.RIGHT_SHOULDER).x)
        assertTrue(away.points.getValue(BodyPoint.LEFT_SHOULDER).x < away.points.getValue(BodyPoint.RIGHT_SHOULDER).x)
    }

    @Test
    fun `the figure is not drawn when the athlete cannot be placed`() {
        val frame = athlete()
        fun without(vararg points: BodyPoint) = frame.copy(
            image = frame.image.mapIndexed { index, point ->
                if (points.any { it.ordinal == index }) point.copy(visibility = 0.1f, presence = 0.1f) else point
            },
        )
        assertNull("No feet, no floor to stand it on",
            ReferenceFigure.placement(without(BodyPoint.LEFT_ANKLE, BodyPoint.RIGHT_ANKLE)))
        assertNull(ReferenceFigure.placement(without(BodyPoint.LEFT_SHOULDER)))
        assertNull(BodyProportions.observed(without(BodyPoint.RIGHT_HIP)))
        // One foot is enough, as in a side view with the far leg hidden.
        assertNotNull(ReferenceFigure.placement(without(BodyPoint.RIGHT_KNEE, BodyPoint.RIGHT_ANKLE)))
    }

    @Test
    fun `a hidden or mistracked limb takes a believable length instead of a wild one`() {
        val frame = athlete()
        val hiddenArm = frame.copy(image = frame.image.mapIndexed { index, point ->
            if (index == BodyPoint.LEFT_WRIST.ordinal || index == BodyPoint.RIGHT_WRIST.ordinal) point.copy(visibility = 0.1f) else point
        })
        val typicalForearm = BodyProportions.Typical.forearm * 0.45f / BodyProportions.Typical.torso
        assertEquals(typicalForearm, BodyProportions.observed(hiddenArm)!!.forearm, 0.001f)

        val stretched = frame.copy(world = frame.world.mapIndexed { index, point ->
            if (index == BodyPoint.LEFT_WRIST.ordinal || index == BodyPoint.RIGHT_WRIST.ordinal) point.copy(y = point.y + 1f) else point
        })
        assertTrue(BodyProportions.observed(stretched)!!.forearm <= typicalForearm * 1.36f)
    }

    @Test
    fun `the tracker steadies the figure against tracker wobble and forgets after a gap`() {
        val noise = kotlin.random.Random(11)
        val tracker = ReferenceFigureTracker()
        val raw = mutableListOf<Float>()
        val steady = mutableListOf<Float>()
        (0 until 60).forEach { index ->
            val frame = athlete(jitter = noise, timestampMs = index * 33L)
            raw += ReferenceFigure.placement(frame)!!.pixelsPerMetre
            steady += tracker.update(frame)!!.second.pixelsPerMetre
        }
        fun spread(values: List<Float>) = values.drop(10).let { it.max() - it.min() }
        assertTrue("steadied ${spread(steady)} should be well under raw ${spread(raw)}", spread(steady) < spread(raw) * 0.6f)

        // A long gap means a different stance; the old average must not drag the new one.
        val moved = Rig.Standing.frame(10_000L).let { frame ->
            frame.copy(imageWidth = 1_000, imageHeight = 1_000, image = frame.image.map { it.copy(x = it.x + 0.2f) })
        }
        assertEquals(ReferenceFigure.placement(moved)!!.centreX, tracker.update(moved)!!.second.centreX, 0.001f)
    }

    // --- Turning a reference pose back into a pose frame -------------------------

    private fun window(skill: Skill, hitting: Side): RepWindow {
        val start = ReferencePoses.pose(skill, ReferenceMoment.Start, hitting)
        val finish = ReferencePoses.pose(skill, ReferenceMoment.Finish, hitting)
        val frames = listOf(frame(start, 0L), frame(finish, 400L), frame(start, 800L))
        return RepWindow(0, frames, mapOf(
            RepPhase.Preparation to frames[0], RepPhase.Loading to frames[0],
            RepPhase.Contact to frames[1], RepPhase.FollowThrough to frames[2],
        ), Angles.hipCentreImage(frames[0]).y)
    }

    private fun repetitions(skill: Skill, hitting: Side, count: Int, view: Facing): List<PoseFrame> {
        val start = ReferencePoses.pose(skill, ReferenceMoment.Start, hitting)
        val finish = ReferencePoses.pose(skill, ReferenceMoment.Finish, hitting)
        val keys = mutableListOf(0L to start, 1_000L to start)
        var at = 1_000L
        repeat(count) {
            keys += (at + 500L) to finish
            keys += (at + 650L) to finish
            keys += (at + 1_150L) to start
            keys += (at + 1_900L) to start
            at += 1_900L
        }
        val frames = mutableListOf<PoseFrame>()
        var time = 0L
        while (time <= keys.last().first) {
            val next = keys.indexOfFirst { it.first >= time }.coerceAtLeast(1)
            val (fromMs, from) = keys[next - 1]
            val (toMs, to) = keys[next]
            val linear = if (toMs == fromMs) 1f else (time - fromMs).toFloat() / (toMs - fromMs)
            val eased = 0.5f - 0.5f * cos(PI.toFloat() * linear.coerceIn(0f, 1f))
            frames += frame(blend(from, to, eased), time, view)
            time += 33L
        }
        return frames
    }

    private fun blend(from: ReferencePose, to: ReferencePose, t: Float): ReferencePose {
        fun mix(a: Float, b: Float) = a + (b - a) * t
        fun arm(a: ArmPose, b: ArmPose) =
            ArmPose(mix(a.upperElevation, b.upperElevation), mix(a.foreElevation, b.foreElevation), mix(a.azimuth, b.azimuth))
        val gap = when {
            from.handGap != null && to.handGap != null -> mix(from.handGap, to.handGap)
            else -> if (t < 0.5f) from.handGap else to.handGap
        }
        return ReferencePose(arm(from.left, to.left), arm(from.right, to.right), mix(from.knee, to.knee),
            mix(from.trunkLean, to.trunkLean), gap)
    }

    /**
     * Renders [pose] as the pose detector would report an athlete standing in
     * it: world landmarks in metres about the hips, image landmarks from a
     * straight-on camera with square pixels.
     */
    private fun frame(
        pose: ReferencePose,
        timestampMs: Long = 0L,
        view: Facing = Facing.Forward,
        body: BodyProportions = BodyProportions.Typical,
    ): PoseFrame {
        val joints = ReferenceFigure.joints(pose, body).toMutableMap()
        val nose = joints.getValue(BodyPoint.NOSE)
        joints[BodyPoint.LEFT_EYE] = Vec3(0.03f, nose.y - 0.03f, nose.z - 0.01f)
        joints[BodyPoint.RIGHT_EYE] = Vec3(-0.03f, nose.y - 0.03f, nose.z - 0.01f)
        val floor = maxOf(joints.getValue(BodyPoint.LEFT_ANKLE).y, joints.getValue(BodyPoint.RIGHT_ANKLE).y)
        val world = MutableList(PoseFrame.EXPECTED_LANDMARK_COUNT) { TestPoses.landmark() }
        val image = MutableList(PoseFrame.EXPECTED_LANDMARK_COUNT) { TestPoses.landmark(x = 0.5f, y = 0.5f) }
        val face = setOf(BodyPoint.NOSE, BodyPoint.LEFT_EYE, BodyPoint.RIGHT_EYE)
        joints.forEach { (point, at) ->
            val (x, z) = when (view) {
                Facing.Forward -> at.x to -at.z
                Facing.Right -> -at.z to -at.x
                Facing.Left -> at.z to at.x
                Facing.Away -> -at.x to at.z
            }
            val seen = if (view == Facing.Away && point in face) 0.1f else 1f
            world[point.ordinal] = Landmark(x, at.y, z, seen, seen)
            image[point.ordinal] = Landmark(0.5f + x * SCALE, FLOOR_Y + (at.y - floor) * SCALE, z, seen, seen)
        }
        check(BodyPoint.TorsoAndLimbs.all { abs(image[it.ordinal].x - 0.5f) < 0.47f && image[it.ordinal].y in 0.03f..0.97f }) {
            "The test camera cropped the figure"
        }
        return PoseFrame(timestampMs, image, world, imageWidth = 1_000, imageHeight = 1_000, mirrored = false)
    }

    private companion object {
        const val SCALE = 0.36f
        const val FLOOR_Y = 0.92f
    }
}
