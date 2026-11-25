package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.FormCue
import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.Rig
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is and is not a repetition, for each of the five skills.
 *
 * Every case is run through both counters: [LiveRepCounter], which drives the
 * badge and the spoken count during capture, and [RepSegmenter], which produces
 * the reps that are saved and scored. They must agree, or the athlete is told
 * one number on the camera screen and shown another on the results screen.
 */
class MovementVerdictTest {

    private data class Outcome(val live: Int, val saved: Int, val faults: List<MovementFault>)

    private fun run(skill: Skill, frames: List<PoseFrame>): Outcome {
        val analyzer = SkillAnalyzers.forSkill(skill)
        val counter = LiveRepCounter(analyzer)
        val faults = mutableListOf<MovementFault>()
        frames.forEach { frame ->
            counter.onFrame(frame)
            counter.lastRejection?.let(faults::add)
        }
        return Outcome(counter.count, RepSegmenter().segment(frames, analyzer).size, faults)
    }

    /** One second at [rest], then [count] repetitions of [move], each followed by a pause at [rest]. */
    private fun reps(
        rest: Rig,
        count: Int,
        vararg move: Pair<Long, Rig>,
        view: Facing = Facing.Forward,
        hidden: Set<BodyPoint> = emptySet(),
        jitter: Random? = null,
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
        return Rig.motion(0L, *keys.toTypedArray(), view = view, jitter = jitter, hidden = hidden)
    }

    // --- Poses -------------------------------------------------------------

    private val standing = Rig.Standing
    private val handsAtForehead = standing.arms(raise = 80f, elbow = 80f)
    private val armsOverhead = standing.arms(raise = 170f, elbow = 170f)
    private val blockReady = standing.arms(raise = 60f, elbow = 60f)
    private val platform = standing.arms(raise = 45f, elbow = 170f).copy(join = 1f, knee = 20f)

    // The guiding arm comes up as the hitting elbow is drawn back, then drops as the hand swings through.
    private val serve = arrayOf(
        400L to standing.copy(leftRaise = 115f, leftElbow = 165f, rightRaise = 100f, rightElbow = 70f),
        700L to standing.copy(leftRaise = 45f, rightRaise = 170f, rightElbow = 170f),
        1_100L to standing,
    )
    private val spike = arrayOf(
        300L to standing.copy(leftRaise = 40f, rightRaise = -20f, knee = 60f),
        600L to standing.copy(leftRaise = 115f, leftElbow = 165f, rightRaise = 100f, rightElbow = 70f, knee = 20f),
        850L to standing.copy(leftRaise = 45f, rightRaise = 170f, rightElbow = 170f),
        1_250L to standing,
    )
    private val set = arrayOf(400L to handsAtForehead, 700L to armsOverhead, 1_000L to handsAtForehead, 1_400L to standing)
    private val block = arrayOf(300L to blockReady.copy(knee = 40f), 650L to armsOverhead, 1_050L to blockReady)
    private val pass = arrayOf(500L to platform.copy(knee = 70f), 1_000L to platform)
    private val kneeBend = arrayOf(600L to standing.copy(knee = 70f), 1_200L to standing)

    // --- The reported defect -------------------------------------------------

    /**
     * Twelve seconds of an athlete standing still, with landmark noise of the
     * size a real tracker shows. No skill may find a repetition in it.
     */
    @Test
    fun `a motionless athlete earns no repetition in any skill`() {
        Skill.All.forEach { skill ->
            val still = Rig.motion(0L, 0L to standing, 12_000L to standing, jitter = Random(skill.ordinal + 7))
            val outcome = run(skill, still)
            assertEquals("$skill counted reps live while the athlete stood still", 0, outcome.live)
            assertEquals("$skill saved reps for an athlete who stood still", 0, outcome.saved)
            assertTrue("$skill coached a movement that never happened: ${outcome.faults}", outcome.faults.isEmpty())
        }
    }

    @Test
    fun `bending the knees up and down earns no repetition in any skill`() {
        Skill.All.forEach { skill ->
            val outcome = run(skill, reps(standing, 4, *kneeBend, jitter = Random(skill.ordinal + 31)))
            assertEquals("$skill counted knee bends live", 0, outcome.live)
            assertEquals("$skill saved knee bends as reps", 0, outcome.saved)
        }
    }

    /** A pass is segmented on the knees, so here the bend is seen and has to be explained. */
    @Test
    fun `a knee bend without a platform is explained rather than counted as a pass`() {
        val outcome = run(Skill.Receiving, reps(standing, 3, *kneeBend))
        assertEquals(0, outcome.live)
        assertEquals(listOf(MovementFault.JoinPlatform, MovementFault.JoinPlatform, MovementFault.JoinPlatform), outcome.faults)
    }

    /**
     * Why the verdict exists. The same knee bends, judged the old way - by the
     * shape of the curve alone - are counted every time, both on the knee signal
     * a pass uses and on the hip-height signal a spike used to use.
     */
    @Test
    fun `curve shape alone would have counted every knee bend`() {
        val bends = reps(standing, 4, *kneeBend)
        fun shapeOnly(signal: (PoseFrame) -> Float?) = object : SkillAnalyzer {
            override val skill = Skill.Receiving
            override fun segmentationSignal(frame: PoseFrame) = signal(frame)
            override fun measure(cue: FormCue, window: RepWindow): Float? = null
        }
        val kneeSignal = shapeOnly(ReceiveAnalyzer()::segmentationSignal)
        assertEquals(4, RepSegmenter().segment(bends, kneeSignal).size)
        val hipSignal = shapeOnly { -Angles.hipCentreImage(it).y }
        assertTrue(RepSegmenter().segment(bends, hipSignal).size >= 3)
    }

    // --- Correct movements still count ----------------------------------------

    @Test
    fun `a correct movement counts once per repetition in every skill`() {
        val movements = mapOf(
            Skill.Serving to reps(standing, 3, *serve, jitter = Random(1)),
            Skill.Spiking to reps(standing, 3, *spike, jitter = Random(2)),
            Skill.Setting to reps(standing, 3, *set, jitter = Random(3)),
            Skill.Blocking to reps(blockReady, 3, *block, jitter = Random(4)),
            Skill.Receiving to reps(platform, 3, *pass, jitter = Random(5)),
        )
        Skill.All.forEach { skill ->
            val outcome = run(skill, movements.getValue(skill))
            assertEquals("$skill live count", 3, outcome.live)
            assertEquals("$skill saved count", 3, outcome.saved)
            assertTrue("$skill rejected a correct movement: ${outcome.faults}", outcome.faults.isEmpty())
        }
    }

    @Test
    fun `a set that starts with the hands already raised still counts`() {
        val push = arrayOf(350L to armsOverhead, 700L to handsAtForehead)
        assertEquals(3, run(Skill.Setting, reps(handsAtForehead, 3, *push)).live)
    }

    @Test
    fun `a standing arm swing counts as a spike without a jump or a knee bend`() {
        assertEquals(3, run(Skill.Spiking, reps(standing, 3, *serve)).live)
    }

    // --- Wrong movements are named ---------------------------------------------

    @Test
    fun `a hand raised only to the face is not a serve or a spike`() {
        val toFace = arrayOf(400L to standing.copy(rightRaise = 60f, rightElbow = 50f), 800L to standing)
        listOf(Skill.Serving, Skill.Spiking).forEach { skill ->
            val outcome = run(skill, reps(standing, 2, *toFace))
            assertEquals(0, outcome.live)
            assertEquals(0, outcome.saved)
            assertEquals(listOf(MovementFault.ReachOverhead, MovementFault.ReachOverhead), outcome.faults)
        }
    }

    /** Hands on hips, a tug at a shirt: not an attempt, so nothing is said about it. */
    @Test
    fun `a hand that stays below the shoulders is passed over in silence`() {
        val toChest = arrayOf(400L to standing.copy(rightRaise = 20f, rightElbow = 80f), 800L to standing)
        listOf(Skill.Serving, Skill.Spiking).forEach { skill ->
            val outcome = run(skill, reps(standing, 3, *toChest))
            assertEquals(0, outcome.live)
            assertEquals(0, outcome.saved)
            assertTrue("$skill coached a movement that was not an attempt: ${outcome.faults}", outcome.faults.isEmpty())
        }
    }

    /**
     * The ready position servers are taught: guiding arm held up in front, hitting
     * hand already beside the head. The swing from there is short, and the hand
     * the signal follows at rest is not the one that hits.
     */
    @Test
    fun `a serve from a cocked start with the guiding arm held up still counts`() {
        val cocked = standing.copy(leftRaise = 110f, leftElbow = 165f, rightRaise = 85f, rightElbow = 75f)
        val swing = arrayOf(
            350L to standing.copy(leftRaise = 40f, leftElbow = 150f, rightRaise = 165f, rightElbow = 168f),
            800L to cocked,
        )
        val outcome = run(Skill.Serving, reps(cocked, 3, *swing, jitter = Random(23)))
        assertEquals(3, outcome.live)
        assertEquals(3, outcome.saved)
        assertTrue(outcome.faults.isEmpty())
    }

    /**
     * From public coaching footage, at 15 frames a second: the toss hand is
     * held up at about face height for several frames, then the hitting hand
     * swings through overhead for a single frame. The smoothed contact frame
     * falls on the toss, which is the higher hand there but short of the
     * head. That refused real serves filmed from behind for "reach overhead".
     */
    @Test
    fun `a fast serve swing that is overhead for one frame after the toss still counts`() {
        val toss = standing.copy(leftRaise = 105f, leftElbow = 170f, rightRaise = 75f, rightElbow = 75f)
        val contact = standing.copy(leftRaise = 40f, leftElbow = 170f, rightRaise = 172f, rightElbow = 175f)
        val keys = mutableListOf(0L to standing, 1_000L to standing)
        var at = 1_000L
        repeat(3) {
            keys += (at + 300L) to toss
            keys += (at + 700L) to toss
            keys += (at + 767L) to contact
            keys += (at + 834L) to standing
            keys += (at + 1_800L) to standing
            at += 1_800L
        }
        val frames = Rig.motion(0L, *keys.toTypedArray(), jitter = Random(41), stepMs = 67L)
        val outcome = run(Skill.Serving, frames)
        assertEquals(3, outcome.live)
        assertEquals(3, outcome.saved)
        assertTrue(outcome.faults.isEmpty())
    }

    /**
     * A spike approach lifts both arms, and at the moment their average height
     * peaks it looks like a block. Public footage of hitters attacking a block
     * pad was counted as blocking 8 times in 19. On a spike the hitting hand
     * peaks well after the other arm has started down.
     */
    @Test
    fun `a spike arm swing is not a block`() {
        val bothUp = standing.arms(raise = 150f, elbow = 150f)
        val hit = standing.copy(leftRaise = 60f, leftElbow = 150f, rightRaise = 175f, rightElbow = 175f)
        val swing = arrayOf(350L to bothUp, 800L to hit, 1_200L to standing)
        val outcome = run(Skill.Blocking, reps(standing, 2, *swing, jitter = Random(43)))
        assertEquals(0, outcome.live)
        assertEquals(0, outcome.saved)
        assertTrue(outcome.faults.isNotEmpty())
        assertTrue(outcome.faults.all { it == MovementFault.UseBothHands })
    }

    @Test
    fun `one hand is not a set or a block`() {
        val oneHand = arrayOf(
            400L to standing.copy(rightRaise = 80f, rightElbow = 80f),
            700L to standing.copy(rightRaise = 170f, rightElbow = 170f),
            1_100L to standing,
        )
        listOf(Skill.Setting, Skill.Blocking).forEach { skill ->
            val outcome = run(skill, reps(standing, 2, *oneHand))
            assertEquals(0, outcome.live)
            assertEquals(listOf(MovementFault.UseBothHands, MovementFault.UseBothHands), outcome.faults)
        }
    }

    @Test
    fun `straight arms swung overhead are not a set`() {
        val swing = arrayOf(500L to armsOverhead, 1_000L to standing)
        val outcome = run(Skill.Setting, reps(standing, 2, *swing))
        assertEquals(0, outcome.live)
        assertEquals(listOf(MovementFault.BendElbowsFirst, MovementFault.BendElbowsFirst), outcome.faults)
    }

    @Test
    fun `hands that stop below the face are not a set`() {
        val toChest = arrayOf(400L to standing.arms(raise = 30f, elbow = 50f), 800L to standing)
        val outcome = run(Skill.Setting, reps(standing, 2, *toChest))
        assertEquals(0, outcome.live)
        assertEquals(listOf(MovementFault.HandsAboveForehead, MovementFault.HandsAboveForehead), outcome.faults)
    }

    @Test
    fun `hands that stop at the forehead are not a block`() {
        val toForehead = arrayOf(400L to handsAtForehead, 800L to standing)
        val outcome = run(Skill.Blocking, reps(standing, 2, *toForehead))
        assertEquals(0, outcome.live)
        assertEquals(listOf(MovementFault.ReachBothHandsHigh, MovementFault.ReachBothHandsHigh), outcome.faults)
    }

    @Test
    fun `a platform lifted overhead is not a pass`() {
        val overhead = armsOverhead.copy(join = 1f, knee = 20f)
        val outcome = run(Skill.Receiving, reps(overhead, 2, 500L to overhead.copy(knee = 70f), 1_000L to overhead))
        assertEquals(0, outcome.live)
        assertEquals(listOf(MovementFault.PlatformTooHigh, MovementFault.PlatformTooHigh), outcome.faults)
    }

    // --- Only what the camera saw is judged ------------------------------------

    @Test
    fun `a side view with the far arm hidden still counts the visible movement`() {
        val farArm = setOf(BodyPoint.RIGHT_ELBOW, BodyPoint.RIGHT_WRIST)
        assertEquals(3, run(Skill.Setting, reps(standing, 3, *set, view = Facing.Right, hidden = farArm)).live)
        assertEquals(3, run(Skill.Blocking, reps(blockReady, 3, *block, view = Facing.Right, hidden = farArm)).live)
        assertEquals(3, run(Skill.Receiving, reps(platform, 3, *pass, view = Facing.Right, hidden = farArm)).live)
    }

    @Test
    fun `a side view still refuses a knee bend with the visible arm hanging`() {
        val farArm = setOf(BodyPoint.RIGHT_ELBOW, BodyPoint.RIGHT_WRIST)
        val outcome = run(Skill.Receiving, reps(standing, 2, *kneeBend, view = Facing.Right, hidden = farArm))
        assertEquals(0, outcome.live)
        assertEquals(listOf(MovementFault.JoinPlatform, MovementFault.JoinPlatform), outcome.faults)
    }

    /** Live sessions are front and side only, and a platform the camera cannot see is not counted. */
    @Test
    fun `a pass seen from behind does not count`() {
        val wrists = setOf(BodyPoint.LEFT_WRIST, BodyPoint.RIGHT_WRIST)
        val outcome = run(Skill.Receiving, reps(platform, 3, *pass, view = Facing.Away, hidden = wrists))
        assertEquals(0, outcome.live)
        assertEquals(0, outcome.saved)
        assertTrue(outcome.faults.all { it == MovementFault.ArmsNotVisible })
        assertEquals(0, run(Skill.Receiving, reps(standing, 2, *kneeBend, view = Facing.Away)).live)
    }

    @Test
    fun `a hitting arm that leaves the picture is reported, not counted or blamed`() {
        val arm = setOf(BodyPoint.RIGHT_WRIST)
        val outcome = run(Skill.Serving, reps(standing, 2, *serve, hidden = arm))
        assertEquals(0, outcome.live)
        assertTrue(outcome.faults.isNotEmpty() && outcome.faults.all { it == MovementFault.ArmsNotVisible })
    }

    // --- The knee-load cue is read where the knees were loaded -------------------

    @Test
    fun `loading is the deepest knee bend, not the lowest the hands got`() {
        val frames = reps(standing, 1, *spike)
        val rep = RepSegmenter().segment(frames, SpikeAnalyzer()).single()
        val loading = rep.frameFor(RepPhase.Loading)!!
        val deepest = frames.minBy { AnalyzerSupport.meanKneeAngle(it) ?: 180f }
        assertEquals(AnalyzerSupport.meanKneeAngle(deepest)!!, AnalyzerSupport.meanKneeAngle(loading)!!, 6f)
        assertTrue(loading.timestampMs <= rep.contactMs)
    }
}
