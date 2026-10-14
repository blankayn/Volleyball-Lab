package com.volleyballlab.player.feature

import com.volleyballlab.player.domain.model.AthleteProfile
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.CueScore
import com.volleyballlab.player.domain.model.RepResult
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.model.TrainingTotals
import com.volleyballlab.player.feature.coach.CoachAdvice
import com.volleyballlab.player.feature.progress.ProgressSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for what the Progress, Coach and Profile tabs actually claim.
 *
 * These three screens are the only place in the app that makes statements about
 * the athlete's training as a whole rather than about one session, and a wrong
 * aggregate is uniquely hard to spot by eye: a mean that quietly includes
 * unscored sessions, or a "focus on this" built from two reps, looks exactly like
 * a correct one on screen. The derivations are pure functions precisely so they
 * can be pinned here.
 */
class TabDerivationsTest {

    // ── Progress ────────────────────────────────────────────────────────────

    @Test
    fun `no sessions produces the empty summary`() {
        val summary = ProgressSummary.from(emptyList())

        assertTrue(!ProgressSummary.Empty.loaded)
        assertTrue(summary.loaded)
        assertEquals(0, summary.sessionCount)
        assertNull(summary.meanScore)
        assertTrue(summary.trend.isEmpty())
    }

    @Test
    fun `totals count every session and every rep`() {
        val summary = ProgressSummary.from(
            listOf(
                session(startedAt = 1_000, reps = listOf(perfectRep(0), perfectRep(1))),
                session(startedAt = 2_000, reps = listOf(perfectRep(0))),
            )
        )

        assertEquals(2, summary.sessionCount)
        assertEquals(3, summary.repCount)
    }

    /**
     * The repository hands back newest-first. A chart drawn in that order runs
     * backwards through time while looking exactly like one that does not.
     */
    @Test
    fun `the trend runs oldest to newest`() {
        val summary = ProgressSummary.from(
            listOf(
                session(id = "newest", startedAt = 3_000, reps = listOf(perfectRep(0))),
                session(id = "oldest", startedAt = 1_000, reps = listOf(failedRep(0))),
            )
        )

        val (first, last) = summary.trend
        assertTrue("Expected the low score first, got ${summary.trend}", first < last)
    }

    @Test
    fun `the trend keeps only the most recent window`() {
        val many = (1..ProgressSummary.TREND_LENGTH + 5).map {
            session(id = "s$it", startedAt = it * 1_000L, reps = listOf(perfectRep(0)))
        }

        val summary = ProgressSummary.from(many)

        assertEquals(ProgressSummary.TREND_LENGTH, summary.trend.size)
    }

    @Test
    fun `skills are ordered by how much they have been trained`() {
        val summary = ProgressSummary.from(
            listOf(
                session(id = "a", skill = Skill.Serving, reps = listOf(perfectRep(0))),
                session(id = "b", skill = Skill.Spiking, reps = listOf(perfectRep(0))),
                session(id = "c", skill = Skill.Spiking, reps = listOf(perfectRep(0))),
            )
        )

        assertEquals(Skill.Spiking, summary.bySkill.first().skill)
        assertEquals(2, summary.bySkill.first().sessionCount)
    }

    /**
     * A session where nothing could be measured has a null score. Averaging it in
     * as a zero would let a bad camera angle drag down a real average, which is
     * the same mistake [RepResult.formScore] exists to avoid one level down.
     */
    @Test
    fun `unscored sessions are excluded from the average, not counted as zero`() {
        val summary = ProgressSummary.from(
            listOf(
                session(id = "scored", reps = listOf(perfectRep(0))),
                session(id = "unscored", reps = listOf(unmeasurableRep(0))),
            )
        )

        assertEquals(2, summary.sessionCount)
        assertEquals(100, summary.meanScore)
    }

    // ── Coach ───────────────────────────────────────────────────────────────

    @Test
    fun `empty coach history finishes loading before showing empty advice`() {
        assertTrue(!CoachAdvice.Empty.loaded)
        assertTrue(CoachAdvice.from(emptyList()).loaded)
    }

    @Test
    fun `a cue measured too few times yields no advice at all`() {
        val tooFew = SessionResult.MIN_REPS_FOR_RECOMMENDATION - 1
        val advice = CoachAdvice.from(
            listOf(session(reps = (0 until tooFew).map { failedRep(it) }))
        )

        assertNull("Advice from $tooFew reps should be withheld", advice.focus)
    }

    @Test
    fun `the focus is the weakest cue across every session`() {
        val advice = CoachAdvice.from(listOf(session(reps = (0..4).map { mixedRep(it) })))

        assertEquals(CueId.SpikeTrunkDrive, advice.focus?.cue?.id)
        assertEquals(5, advice.focus?.measuredReps)
    }

    /**
     * "Working well" has to mean it. Naming the best of a bad set as a strength
     * is how a screen loses the athlete's trust in everything else on it.
     */
    @Test
    fun `no cue is called a strength unless it is genuinely good`() {
        val advice = CoachAdvice.from(listOf(session(reps = (0..4).map { mediocreRep(it) })))

        assertTrue("Expected a focus", advice.focus != null)
        assertNull("Nothing here is worth calling a strength", advice.strength)
    }

    @Test
    fun `unmeasurable cues are tracked so the camera angle can be blamed`() {
        val advice = CoachAdvice.from(listOf(session(reps = (0..4).map { unmeasurableRep(it) })))

        assertEquals(1f, advice.unmeasuredFraction, 0.001f)
        assertTrue(advice.cameraAngleIsHurting)
    }

    // ── Profile ─────────────────────────────────────────────────────────────

    @Test
    fun `training totals sum duration across sessions`() {
        val totals = TrainingTotals.from(
            listOf(
                session(id = "a", durationMs = 60_000, reps = listOf(perfectRep(0))),
                session(id = "b", durationMs = 30_000, reps = listOf(perfectRep(0))),
            )
        )

        assertEquals(90_000L, totals.totalMillis)
        assertEquals(100, totals.bestScore)
    }

    @Test
    fun `initials skip middle names`() {
        assertEquals("KM", AthleteProfile(name = "Kirt Deniel Magno").initials())
        assertEquals("K", AthleteProfile(name = "Kirt").initials())
        assertNull(AthleteProfile(name = "   ").initials())
    }

    // ── Builders ────────────────────────────────────────────────────────────

    private fun session(
        id: String = "session",
        skill: Skill = Skill.Spiking,
        startedAt: Long = 0L,
        durationMs: Long = 10_000L,
        reps: List<RepResult>,
    ) = SessionResult(
        id = id,
        skill = skill,
        drillId = "drill",
        startedAtEpochMs = startedAt,
        durationMs = durationMs,
        reps = reps,
    )

    private fun rep(index: Int, scores: Map<CueId, Float?>) = RepResult(
        index = index,
        startMs = 0L,
        contactMs = 0L,
        endMs = 0L,
        cueScores = scores.map { (cueId, score) ->
            CueScore(cueId = cueId, measured = score?.let { 0f }, score = score)
        },
    )

    private fun perfectRep(index: Int) = rep(index, SPIKE_CUES.associateWith { 1f })

    private fun failedRep(index: Int) = rep(index, SPIKE_CUES.associateWith { 0f })

    private fun mediocreRep(index: Int) = rep(index, SPIKE_CUES.associateWith { 0.5f })

    private fun unmeasurableRep(index: Int) = rep(index, SPIKE_CUES.associateWith { null })

    /** One clearly worst cue, so the focus assertion has an unambiguous answer. */
    private fun mixedRep(index: Int) = rep(
        index,
        mapOf(
            CueId.SpikeApproachLoad to 0.9f,
            CueId.SpikeArmExtension to 0.8f,
            CueId.SpikeJumpHeight to 0.7f,
            CueId.SpikeTrunkDrive to 0.2f,
        ),
    )

    private companion object {
        val SPIKE_CUES = listOf(
            CueId.SpikeApproachLoad,
            CueId.SpikeArmExtension,
            CueId.SpikeJumpHeight,
            CueId.SpikeTrunkDrive,
        )
    }
}
