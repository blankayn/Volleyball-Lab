package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.DrillFocus
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.Rig
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Things athletes do in front of the camera that are not the skill, seen from the
 * front and from both sides, judged exactly as a live session judges them: the
 * live counter and the saved session with [RepJudge]'s decision. None may add a rep.
 */
class UnrelatedMovementTest {

    private data class Outcome(val live: Int, val saved: Int, val refusals: List<MovementVerdict.NotCounted>)

    private fun run(skill: Skill, frames: List<PoseFrame>, drillId: String? = null, angle: CameraAngle? = null): Outcome {
        val analyzer = SkillAnalyzers.forSkill(skill)
        val judge = RepJudge(skill, drillId)
        val counter = LiveRepCounter(analyzer) { judge.judge(it, angle) }
        val refusals = mutableListOf<MovementVerdict.NotCounted>()
        frames.forEach { frame ->
            val counted = counter.onFrame(frame)
            counter.lastRefusal?.let(refusals::add)
            // One frame is never both added and refused.
            assertFalse("$skill added and refused the same movement", counted && counter.lastRefusal != null)
        }
        return Outcome(counter.count, RepSegmenter().segment(frames, analyzer) { judge.judge(it, angle) }.size, refusals)
    }

    private fun reps(
        rest: Rig, count: Int, vararg move: Pair<Long, Rig>,
        view: Facing = Facing.Forward, hidden: Set<BodyPoint> = emptySet(), seed: Int = 1,
    ): List<PoseFrame> {
        val keys = mutableListOf(0L to rest, 1_000L to rest)
        var at = 1_000L
        repeat(count) {
            move.forEach { (offset, pose) -> keys += (at + offset) to pose }
            at += move.last().first
            keys += (at + 400L) to rest
            keys += (at + 900L) to rest
            at += 900L
        }
        return Rig.motion(0L, *keys.toTypedArray(), view = view, jitter = Random(seed), hidden = hidden)
    }

    private val standing = Rig.Standing

    /** Name, resting pose, the movement, and the skills it is a real repetition of. */
    private val movements = listOf(
        Movement("bending the knees", standing, arrayOf(600L to standing.copy(knee = 80f), 1_200L to standing)),
        Movement("bending the knees with the hands on the knees", standing,
            arrayOf(600L to standing.arms(25f, 170f).copy(knee = 80f), 1_200L to standing)),
        Movement("bending the knees with one arm out", standing,
            arrayOf(600L to standing.copy(leftRaise = 45f, leftElbow = 170f, knee = 70f), 1_200L to standing)),
        Movement("bending the knees with the hands on the hips", standing,
            arrayOf(600L to standing.arms(10f, 100f).copy(knee = 70f), 1_200L to standing)),
        Movement("raising one hand", standing, arrayOf(600L to standing.copy(rightRaise = 172f, rightElbow = 172f), 1_200L to standing)),
        Movement("waving one hand", standing, arrayOf(400L to standing.copy(rightRaise = 110f, rightElbow = 90f),
            700L to standing.copy(rightRaise = 150f, rightElbow = 140f), 1_000L to standing.copy(rightRaise = 110f, rightElbow = 90f), 1_400L to standing)),
        Movement("scratching the head", standing, arrayOf(500L to standing.copy(rightRaise = 95f, rightElbow = 35f), 1_000L to standing)),
        Movement("stretching both arms overhead", standing, arrayOf(700L to standing.arms(172f, 172f), 1_500L to standing)),
        Movement("shading the eyes with both hands", standing, arrayOf(500L to standing.arms(80f, 80f), 1_000L to standing.arms(80f, 80f), 1_500L to standing)),
        Movement("clapping at the chest", standing, arrayOf(300L to standing.arms(40f, 70f), 600L to standing.arms(40f, 70f).copy(join = 1f), 900L to standing)),
        Movement("swinging the arms while walking", standing,
            arrayOf(300L to standing.copy(leftRaise = 30f, rightRaise = -25f), 600L to standing.copy(leftRaise = -25f, rightRaise = 30f), 900L to standing)),
    )

    private class Movement(val name: String, val rest: Rig, val move: Array<Pair<Long, Rig>>)

    private val farArm = setOf(BodyPoint.RIGHT_ELBOW, BodyPoint.RIGHT_WRIST)

    @Test
    fun `no unrelated movement adds a rep in any skill facing the camera`() {
        for (movement in movements) for (skill in Skill.All) {
            val outcome = run(skill, reps(movement.rest, 3, *movement.move, seed = skill.ordinal + 5))
            assertEquals("${movement.name} added live $skill reps from the front", 0, outcome.live)
            assertEquals("${movement.name} saved $skill reps from the front", 0, outcome.saved)
        }
    }

    /** With every joint seen, as when the far arm comes into view past the body. */
    @Test
    fun `no unrelated movement adds a rep in any skill seen from the side`() {
        for (movement in movements) for (skill in Skill.All) for (view in listOf(Facing.Left, Facing.Right)) {
            val outcome = run(skill, reps(movement.rest, 3, *movement.move, view = view, seed = skill.ordinal + 11))
            assertEquals("${movement.name} added live $skill reps facing $view", 0, outcome.live)
            assertEquals("${movement.name} saved $skill reps facing $view", 0, outcome.saved)
        }
    }

    /** The other skills' real movements are unrelated movements too. */
    @Test
    fun `one skill's movement is not another's`() {
        val swing = arrayOf(400L to standing.copy(leftRaise = 115f, leftElbow = 165f, rightRaise = 100f, rightElbow = 70f),
            700L to standing.copy(leftRaise = 45f, rightRaise = 170f, rightElbow = 170f), 1_100L to standing)
        val set = arrayOf(400L to standing.arms(80f, 80f), 700L to standing.arms(170f, 170f), 1_000L to standing.arms(80f, 80f), 1_400L to standing)
        val platform = standing.arms(45f, 172f).copy(join = 1f, knee = 20f)
        val pass = arrayOf(500L to platform.copy(knee = 70f), 1_000L to platform)
        listOf(Skill.Receiving, Skill.Setting, Skill.Blocking).forEach {
            assertEquals("a swing counted as $it", 0, run(it, reps(standing, 3, *swing)).live)
        }
        listOf(Skill.Serving, Skill.Spiking, Skill.Receiving).forEach {
            assertEquals("a set counted as $it", 0, run(it, reps(standing, 3, *set)).live)
        }
        listOf(Skill.Serving, Skill.Spiking, Skill.Setting, Skill.Blocking).forEach {
            assertEquals("a pass counted as $it", 0, run(it, reps(platform, 3, *pass)).live)
        }
    }

    // --- Receiving needs both arms -------------------------------------------------

    private val platform = standing.arms(45f, 172f).copy(join = 1f, knee = 20f)

    @Test
    fun `a joined straight platform counts from the front and from the side`() {
        assertEquals(3, run(Skill.Receiving, reps(platform, 3, 500L to platform.copy(knee = 60f), 1_000L to platform)).live)
        assertEquals(3, run(Skill.Receiving, reps(platform, 3, 500L to platform.copy(knee = 60f), 1_000L to platform,
            view = Facing.Right, hidden = farArm)).live)
    }

    @Test
    fun `a pass with only the left or only the right arm is not counted`() {
        val left = standing.copy(leftRaise = 45f, leftElbow = 172f, knee = 20f)
        val right = standing.copy(rightRaise = 45f, rightElbow = 172f, knee = 20f)
        listOf(left, right).forEach { oneArm ->
            listOf(Facing.Forward, Facing.Left, Facing.Right).forEach { view ->
                val outcome = run(Skill.Receiving, reps(oneArm, 3, 500L to oneArm.copy(knee = 60f), 1_000L to oneArm, view = view))
                assertEquals("one arm counted facing $view", 0, outcome.live)
                assertEquals("one arm saved facing $view", 0, outcome.saved)
                assertTrue("one arm was not explained facing $view", outcome.refusals.isNotEmpty())
            }
        }
    }

    @Test
    fun `a pass facing the camera with one arm out of sight is not counted`() {
        val outcome = run(Skill.Receiving, reps(platform, 3, 500L to platform.copy(knee = 60f), 1_000L to platform, hidden = farArm))
        assertEquals(0, outcome.live)
        assertTrue(outcome.refusals.all { it.fault == MovementFault.ArmsNotVisible })
    }

    @Test
    fun `hands apart, bent elbows and a platform at the shoulders are not counted`() {
        val apart = standing.arms(45f, 172f).copy(knee = 20f)
        val bent = standing.arms(45f, 95f).copy(join = 1f, knee = 20f)
        val high = standing.arms(88f, 172f).copy(join = 1f, knee = 20f)
        listOf(apart, bent, high).forEach { wrong ->
            val outcome = run(Skill.Receiving, reps(wrong, 3, 500L to wrong.copy(knee = 60f), 1_000L to wrong))
            assertEquals(0, outcome.live)
            assertEquals(0, outcome.saved)
            assertTrue(outcome.refusals.isNotEmpty())
        }
    }

    // --- One decision ------------------------------------------------------------------

    /** The same movement cannot be refused by the voice and added by the counter, or the other way round. */
    @Test
    fun `a refused movement names a fix and a counted one carries no refusal`() {
        val high = standing.arms(88f, 172f).copy(join = 1f, knee = 20f)
        val refused = run(Skill.Receiving, reps(high, 2, 500L to high.copy(knee = 60f), 1_000L to high)).refusals
        assertTrue(refused.isNotEmpty())
        refused.forEach { refusal ->
            val cue = CoachPhrasebook.notCountedCue(refusal)
            assertTrue(cue.full.startsWith(CoachPhrasebook.NOT_COUNTED_PREFIX))
            assertTrue(cue.short.startsWith(CoachPhrasebook.NOT_COUNTED_PREFIX))
            assertTrue(cue.full.length > CoachPhrasebook.NOT_COUNTED_PREFIX.length + 5)
        }
        assertTrue(run(Skill.Receiving, reps(platform, 3, 500L to platform.copy(knee = 60f), 1_000L to platform)).refusals.isEmpty())
    }

    @Test
    fun `every refusal has words`() {
        MovementFault.entries.forEach { fault ->
            assertTrue(CoachPhrasebook.notCounted(fault).length > CoachPhrasebook.NOT_COUNTED_PREFIX.length + 5)
            assertTrue(CoachPhrasebook.notCountedShort(fault).length > CoachPhrasebook.NOT_COUNTED_PREFIX.length + 5)
        }
    }

    // --- The basic spike has no jump --------------------------------------------------

    private val standingSwing = arrayOf(
        400L to standing.copy(leftRaise = 115f, leftElbow = 165f, rightRaise = 100f, rightElbow = 70f),
        700L to standing.copy(leftRaise = 45f, rightRaise = 170f, rightElbow = 170f), 1_100L to standing,
    )

    @Test
    fun `a standing swing counts in the basic spike and is not asked for a knee load`() {
        val outcome = run(Skill.Spiking, reps(standing, 3, *standingSwing), "spike-standing-swing", CameraAngle.Front)
        assertEquals(3, outcome.live)
        assertEquals(3, outcome.saved)
        assertTrue(outcome.refusals.isEmpty())
        assertTrue(CueId.SpikeApproachLoad in DrillFocus.excluded("spike-standing-swing"))
        val scored = SessionScorer().score("s", Skill.Spiking, "spike-standing-swing", 0, reps(standing, 3, *standingSwing), angle = CameraAngle.Front)
        assertEquals(3, scored.reps.size)
        scored.reps.forEach { rep -> assertNull(rep.cueScores.first { it.cueId == CueId.SpikeApproachLoad }.score) }
    }

    @Test
    fun `the jumping spike asks for the knee load the basic one does not`() {
        val drill = "spike-three-step"
        assertTrue(DrillFocus.excluded(drill).isEmpty())
        val still = run(Skill.Spiking, reps(standing, 3, *standingSwing), drill, CameraAngle.Front)
        assertEquals(0, still.live)
        assertTrue(still.refusals.all { it.cue == CueId.SpikeApproachLoad })
        val loaded = arrayOf(
            350L to standing.copy(leftRaise = 40f, rightRaise = -20f, knee = 80f),
            650L to standing.copy(leftRaise = 115f, leftElbow = 165f, rightRaise = 100f, rightElbow = 70f, knee = 20f),
            900L to standing.copy(leftRaise = 45f, rightRaise = 170f, rightElbow = 170f), 1_300L to standing,
        )
        assertEquals(3, run(Skill.Spiking, reps(standing, 3, *loaded), drill, CameraAngle.Front).live)
    }
}
