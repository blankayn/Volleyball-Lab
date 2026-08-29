package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.RepPhase
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
