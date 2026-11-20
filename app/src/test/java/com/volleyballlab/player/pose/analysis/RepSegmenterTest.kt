package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.domain.model.FormCue
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.SyntheticPoseDetector
import com.volleyballlab.player.pose.TestPoses
import com.volleyballlab.player.pose.model.PoseFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Segmentation and scoring, driven by [SyntheticPoseDetector].
 *
 * The synthetic detector runs a known number of movement cycles, which turns
 * "did it count the reps" into an actual assertion rather than someone watching a
 * badge on a phone and deciding it looked about right.
 */
class RepSegmenterTest {

    private val segmenter = RepSegmenter()

    @Test
    fun `a recording that starts at the peak rejects the falling tail and retains the next complete action`() {
        val signal = listOf(1f, .9f, .7f, .4f, .1f, 0f, 0f, .2f, .5f, .9f, 1f, .7f, .3f, .1f, 0f)
        val frames = signal.indices.map { TestPoses.wellFramedStanding(it * 100L) }
        val analyzer = object : SkillAnalyzer {
            override val skill = Skill.Blocking
            override fun segmentationSignal(frame: PoseFrame) = signal[(frame.timestampMs / 100L).toInt()]
            override fun measure(cue: FormCue, window: RepWindow): Float? = null
        }
        val reps = RepSegmenter(RepSegmenter.Config(smoothingWindow = 1)).segment(frames, analyzer)
        assertEquals(1, reps.size)
        assertTrue(reps.single().startMs > 0L)
        assertTrue(reps.single().contactMs > reps.single().startMs)
        assertTrue(reps.single().endMs > reps.single().contactMs)
    }

    /**
     * A block set as filmed on a phone (20 Nov groupmate recording): arms down while
     * setting up and while walking to the phone at the end, hands resting at the
     * chest between jumps. The arms-down seconds set the session's low percentile,
     * so the hands-at-chest rest never reaches the release line. Before the
     * rep-floor release this saved zero reps while the live counter counted them.
     */
    @Test
    fun `a set that rests above the session release line still saves every rep`() {
        val armsDown = -0.9f
        val rest = -0.3f
        val top = 0.85f
        fun ramp(from: Float, to: Float, frames: Int) = (1..frames).map { from + (to - from) * it / frames }
        val signal = buildList {
            repeat(45) { add(armsDown) }                       // 1.5 s setting up
            addAll(ramp(armsDown, rest, 10))
            repeat(6) {
                repeat(24) { add(rest) }                       // hands at the chest
                addAll(ramp(rest, top, 12)); repeat(6) { add(top) }
                addAll(ramp(top, rest, 15))
            }
            addAll(ramp(rest, armsDown, 10))
            repeat(60) { add(armsDown) }                       // 2 s walking to the phone
        }
        val frames = signal.indices.map { TestPoses.wellFramedStanding(it * 33L) }
        val analyzer = object : SkillAnalyzer {
            override val skill = Skill.Blocking
            override fun segmentationSignal(frame: PoseFrame) = signal[(frame.timestampMs / 33L).toInt()]
            override fun measure(cue: FormCue, window: RepWindow): Float? = null
        }

        val reps = segmenter.segment(frames, analyzer)

        assertEquals(6, reps.size)
        reps.forEach { rep ->
            assertEquals(top, signal[(rep.contactMs / 33L).toInt()], 0.05f)
            assertTrue(rep.endMs > rep.contactMs)
        }
        // No two reps share a frame.
        reps.zipWithNext().forEach { (a, b) -> assertTrue(a.endMs < b.startMs) }
    }

    private fun ramp(from: Float, to: Float, frames: Int) = (1..frames).map { from + (to - from) * it / frames }

    /** Segments [signal] at 30 fps with a test analyzer for [skill]. */
    private fun segmentSignal(signal: List<Float>, skill: Skill, raisedHands: Boolean): List<RepWindow> {
        val frames = signal.indices.map { TestPoses.wellFramedStanding(it * 33L) }
        val analyzer = object : SkillAnalyzer {
            override val skill = skill
            override val repeatsFromRaisedHands = raisedHands
            override fun segmentationSignal(frame: PoseFrame) = signal[(frame.timestampMs / 33L).toInt()]
            override fun measure(cue: FormCue, window: RepWindow): Float? = null
        }
        return segmenter.segment(frames, analyzer)
    }

    @Test
    fun `pushes repeated from a raised ready position are each saved`() {
        // The hands go up once from hanging, then push from head height and come back to it.
        val armsDown = -0.9f
        val ready = 0.6f
        val top = 1.0f
        val signal = buildList {
            repeat(45) { add(armsDown) }
            addAll(ramp(armsDown, ready, 10))
            repeat(4) {
                repeat(30) { add(ready) }
                addAll(ramp(ready, top, 9)); repeat(6) { add(top) }
                addAll(ramp(top, ready, 12))
            }
            repeat(30) { add(ready) }
            addAll(ramp(ready, armsDown, 10))
            repeat(60) { add(armsDown) }
        }

        val reps = segmentSignal(signal, Skill.Blocking, raisedHands = true)

        assertEquals(4, reps.size)
        reps.forEach { assertEquals(top, signal[(it.contactMs / 33L).toInt()], 0.05f) }
        reps.zipWithNext().forEach { (a, b) -> assertTrue(a.endMs < b.startMs) }
    }

    @Test
    fun `a held reach whose signal sags and recovers stays one rep`() {
        // A side-on block held overhead: the wrist reading dips by a third of the
        // climb and comes back, more than a second apart, while the arms stay up.
        val armsDown = -0.9f
        val chest = -0.2f
        val top = 0.62f
        val sag = 0.30f
        val signal = buildList {
            repeat(45) { add(armsDown) }
            addAll(ramp(armsDown, chest, 10))
            repeat(4) {
                repeat(24) { add(chest) }
                addAll(ramp(chest, top, 12)); repeat(6) { add(top) }
                addAll(ramp(top, sag, 18)); addAll(ramp(sag, top, 18)); repeat(6) { add(top) }
                addAll(ramp(top, chest, 15))
            }
            addAll(ramp(chest, armsDown, 10))
            repeat(60) { add(armsDown) }
        }

        val reps = segmentSignal(signal, Skill.Blocking, raisedHands = true)

        assertEquals(4, reps.size)
    }

    @Test
    fun `a dip between peaks under a second apart does not split the rep`() {
        val armsDown = -0.9f
        val chest = -0.2f
        val top = 0.9f
        val dip = 0.15f
        val signal = buildList {
            repeat(45) { add(armsDown) }
            addAll(ramp(armsDown, chest, 10))
            repeat(4) {
                repeat(24) { add(chest) }
                addAll(ramp(chest, top, 10)); addAll(ramp(top, dip, 4)); addAll(ramp(dip, top, 4))
                addAll(ramp(top, chest, 12))
            }
            addAll(ramp(chest, armsDown, 10))
            repeat(60) { add(armsDown) }
        }

        val reps = segmentSignal(signal, Skill.Blocking, raisedHands = true)

        assertEquals(4, reps.size)
    }

    @Test
    fun `a one-armed skill keeps the toss and the hit as one rep`() {
        // The higher hand is the toss arm, then the hitting arm, with a dip between.
        val armsDown = -0.9f
        val signal = buildList {
            repeat(30) { add(armsDown) }
            repeat(3) {
                addAll(ramp(armsDown, 1.0f, 10)); repeat(6) { add(1.0f) }
                addAll(ramp(1.0f, -0.1f, 10)); repeat(12) { add(-0.1f) }
                addAll(ramp(-0.1f, 1.1f, 8)); repeat(4) { add(1.1f) }
                addAll(ramp(1.1f, armsDown, 12))
                repeat(30) { add(armsDown) }
            }
        }

        val reps = segmentSignal(signal, Skill.Serving, raisedHands = false)

        assertEquals(3, reps.size)
        reps.forEach { assertEquals(1.1f, signal[(it.contactMs / 33L).toInt()], 0.05f) }
        // The same curve from a two-handed skill is two pushes each time.
        assertEquals(6, segmentSignal(signal, Skill.Blocking, raisedHands = true).size)
    }

    @Test
    fun `tracking gaps do not stitch two partial actions into one repetition`() {
        val original = syntheticFrames(cycles = 4)
        val before = original.filter { it.timestampMs < 900 }
        val after = original.filter { it.timestampMs >= 1900 }
        val reps = segmenter.segment(before + after, SetAnalyzer())
        assertTrue(reps.none { it.startMs < 900 && it.endMs >= 1900 })
        assertEquals(reps.indices.toList(), reps.map { it.index })
    }

    @Test
    fun `complete cycles on either side of a gap retain their own repetitions`() {
        val original = syntheticFrames(cycles = 4)
        val separated = original.map { frame ->
            if (frame.timestampMs >= 4800) frame.copy(timestampMs = frame.timestampMs + 2000) else frame
        }
        val reps = segmenter.segment(separated, SetAnalyzer())
        assertEquals(4, reps.size)
        assertTrue(reps.none { it.startMs < 4800 && it.endMs >= 6800 })
    }

    /** Four full cycles sampled at roughly 30 fps. */
    private fun syntheticFrames(
        cycles: Int = 4,
        cyclePeriodMs: Long = 2_400L,
        frameIntervalMs: Long = 33L,
    ): List<PoseFrame> {
        val detector = SyntheticPoseDetector(cyclePeriodMs = cyclePeriodMs)
        val totalMs = cycles * cyclePeriodMs
        return generateSequence(0L) { it + frameIntervalMs }
            .takeWhile { it < totalMs }
            .map { detector.frameAt(it) }
            .toList()
    }

    @Test
    fun `counts one repetition per movement cycle`() {
        val frames = syntheticFrames(cycles = 4)

        val reps = segmenter.segment(frames, SetAnalyzer())

        assertEquals(4, reps.size)
    }

    @Test
    fun `rep count scales with the number of cycles`() {
        val reps = segmenter.segment(syntheticFrames(cycles = 6), SetAnalyzer())

        assertEquals(6, reps.size)
    }

    @Test
    fun `each rep has all four phases in chronological order`() {
        val reps = segmenter.segment(syntheticFrames(), SetAnalyzer())

        reps.forEach { rep ->
            RepPhase.entries.forEach { phase ->
                assertNotNull("Rep ${rep.index} is missing $phase", rep.frameFor(phase))
            }
            val preparation = rep.frameFor(RepPhase.Preparation)!!.timestampMs
            val contact = rep.frameFor(RepPhase.Contact)!!.timestampMs
            val followThrough = rep.frameFor(RepPhase.FollowThrough)!!.timestampMs

            assertTrue("Contact precedes preparation", contact >= preparation)
            assertTrue("Follow-through precedes contact", followThrough >= contact)
        }
    }

    @Test
    fun `loading happens before contact`() {
        val reps = segmenter.segment(syntheticFrames(), SetAnalyzer())

        reps.forEach { rep ->
            val loading = rep.frameFor(RepPhase.Loading)!!.timestampMs
            val contact = rep.frameFor(RepPhase.Contact)!!.timestampMs
            assertTrue("Loading must precede contact", loading <= contact)
        }
    }

    /**
     * The degenerate case the [RepSegmenter.Config.minSignalRange] guard exists
     * for: an athlete who sets up and never moves. Thresholds derived from a flat
     * signal would sit inside the noise floor and manufacture reps out of nothing.
     */
    @Test
    fun `a motionless athlete produces no repetitions`() {
        val frames = (0 until 200).map { TestPoses.wellFramedStanding(it * 33L) }

        val reps = segmenter.segment(frames, SetAnalyzer())

        assertEquals(0, reps.size)
    }

    @Test
    fun `too few frames to judge produces no repetitions`() {
        val reps = segmenter.segment(syntheticFrames().take(4), SetAnalyzer())

        assertEquals(0, reps.size)
    }

    @Test
    fun `scoring a synthetic session produces a score for every rep`() {
        val frames = syntheticFrames(cycles = 4)

        val session = SessionScorer(segmenter).score(
            sessionId = "test-session",
            skill = Skill.Setting,
            drillId = "set-wall-reps",
            startedAtEpochMs = 0L,
            frames = frames,
        )

        assertEquals(4, session.repCount)
        assertNotNull("Session should have an overall score", session.formScore())
        session.reps.forEach { rep ->
            assertNotNull(
                "Rep ${rep.index} produced no measurable cue",
                rep.formScore(Skill.Setting),
            )
        }
    }

    @Test
    fun `an empty capture scores as a session with no reps`() {
        val session = SessionScorer(segmenter).score(
            sessionId = "empty",
            skill = Skill.Setting,
            drillId = "set-wall-reps",
            startedAtEpochMs = 0L,
            frames = emptyList(),
        )

        // An empty session is a real outcome, not an error. It must still be a
        // valid SessionResult so the results screen can explain what happened.
        assertEquals(0, session.repCount)
        assertEquals(null, session.formScore())
        assertEquals(0L, session.durationMs)
    }

    @Test
    fun `every analyzer is registered under the skill it declares`() {
        Skill.All.forEach { skill ->
            assertEquals(skill, SkillAnalyzers.forSkill(skill).skill)
        }
    }
}
