package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.FormCue
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.TestPoses
import com.volleyballlab.player.pose.model.PoseFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for live posture correction.
 *
 * The engine's contract is stability, not just measurement: a card that flips
 * cues every frame is unreadable from across a court, so bad cues must win
 * repeatedly before taking the card and good frames must persist before clearing
 * it. A fake analyzer drives those cases deterministically; one integration case
 * pins the real geometry (straight knees read as needing correction live, the
 * same way they score post-hoc).
 */
class LivePostureCoachTest {

    @Test
    fun `a bad cue takes the card after repeated wins, not on the first frame`() {
        // 165 vs target 110..150, tolerance 30: scores 0.5 - a mild miss, so the
        // severe fast-path does not apply and the six-frame gate is exercised.
        val coach = LivePostureCoach(
            skill = Skill.Receiving,
            analyzer = FakeAnalyzer(mapOf(CueId.PassKneeFlexion to 165f)),
        )
        var ui = coach.update(frameAt(0), RepPhase.Contact)
        assertNull(ui.displayedCue)

        repeat(5) { i -> ui = coach.update(frameAt((i + 1) * 33L), RepPhase.Contact) }
        // Six consecutive wins (first + five) reaches the gate.
        assertEquals(CueId.PassKneeFlexion, ui.displayedCue?.id)
        assertFalse(ui.isGood)
    }

    @Test
    fun `good frames clear the card only after a run`() {
        val analyzer = FakeAnalyzer(mapOf(CueId.PassKneeFlexion to 165f))
        val coach = LivePostureCoach(skill = Skill.Receiving, analyzer = analyzer)
        repeat(6) { i -> coach.update(frameAt(i * 33L), RepPhase.Contact) }
        val showing = coach.update(frameAt(6 * 33L), RepPhase.Contact)
        assertEquals(CueId.PassKneeFlexion, showing.displayedCue?.id)

        // Now fix the posture: every cue reads mid-target (good).
        analyzer.overrides = emptyMap()
        var ui = showing
        repeat(4) { i -> ui = coach.update(frameAt((7 + i) * 33L), RepPhase.Contact) }
        // Four good frames is not enough to clear (needs eight).
        assertEquals(CueId.PassKneeFlexion, ui.displayedCue?.id)
        repeat(6) { i -> ui = coach.update(frameAt((11 + i) * 33L), RepPhase.Contact) }
        assertNull(ui.displayedCue)
        assertTrue(ui.isGood)
    }

    @Test
    fun `alternating cues never reach the gate and the card stays down`() {
        val kneeBad = mapOf(CueId.PassKneeFlexion to 165f)
        val platformBad = mapOf(CueId.PassPlatformStraight to 150f)
        val coach = LivePostureCoach(
            skill = Skill.Receiving,
            analyzer = AlternatingAnalyzer(kneeBad, platformBad),
        )
        var ui = LivePostureUi.Idle
        repeat(12) { i -> ui = coach.update(frameAt(i * 33L), RepPhase.Contact) }
        // Both miss mildly (scores ~0.4-0.5, above the severe fast-path), but
        // neither wins six in a row, so nothing takes the card.
        assertNull(ui.displayedCue)
    }

    @Test
    fun `a severe miss shows immediately when nothing is up`() {
        val coach = LivePostureCoach(
            skill = Skill.Receiving,
            analyzer = FakeAnalyzer(mapOf(CueId.PassKneeFlexion to 180f)),
        )
        val ui = coach.update(frameAt(0), RepPhase.Contact)
        // 180 vs target 110..150 with tolerance 30 scores 0.0: severe fast-path.
        assertEquals(CueId.PassKneeFlexion, ui.displayedCue?.id)
    }

    @Test
    fun `jump height is excluded from live judging`() {
        val coach = LivePostureCoach(skill = Skill.Spiking)
        val statuses = coach.measureLive(frameAt(0), phase = RepPhase.Loading)
        assertTrue(statuses.none { it.cue.id == CueId.SpikeJumpHeight })
        assertTrue(statuses.isNotEmpty())
    }

    @Test
    fun `lost tracking clears the card without praising an empty frame`() {
        val coach = LivePostureCoach(
            skill = Skill.Receiving,
            analyzer = FakeAnalyzer(mapOf(CueId.PassKneeFlexion to 180f)),
        )
        repeat(7) { i -> coach.update(frameAt(i * 33L), RepPhase.Contact) }
        val showing = coach.update(frameAt(7 * 33L), RepPhase.Contact)
        assertEquals(CueId.PassKneeFlexion, showing.displayedCue?.id)

        val lost = TestPoses.withVisibility(TestPoses.wellFramedStanding(), 0.1f)
            .copy(timestampMs = 8 * 33L)
        val ui = coach.update(lost, RepPhase.Contact)
        assertTrue(ui.trackingLost)
        assertNull(ui.displayedCue)
        assertFalse(ui.isGood)
    }

    @Test
    fun `pose gap clears stale receiving feedback and restarts model warmup`() {
        val evaluator = ReceivingReadyPostureEvaluator(FaultProbabilityModel { 0.8f })
        val coach = LivePostureCoach(
            skill = Skill.Receiving,
            receivingEvaluator = evaluator,
        )
        var ui = LivePostureUi.Idle
        repeat(32) { index -> ui = coach.update(frameAt(index * 33L), RepPhase.Contact) }
        assertEquals(CueId.PassKneeFlexion, ui.displayedCue?.id)

        ui = coach.update(
            frameAt(31 * 33L + ReceivingReadyPostureEvaluator.MAX_CONTIGUOUS_FRAME_GAP_MS + 1L), RepPhase.Contact
        )

        assertNull(ui.displayedCue)
        assertTrue(ui.modelWarmingUp)
        assertFalse(ui.isGood)
    }

    @Test
    fun `straight knees read as needing correction on real geometry`() {
        // wellFramedStanding is near-straight-legged (~180), while the pass cue
        // wants 110..150. Live must agree with post-hoc: this is a correction.
        val coach = LivePostureCoach(skill = Skill.Receiving)
        var ui = LivePostureUi.Idle
        repeat(30) { i ->
            ui = coach.update(frameAt(i * 33L), RepPhase.Contact)
        }
        // Either knees take the card, or (if another cue scores worse on this
        // pose) something does: the point is live judging finds a correction
        // rather than calling a straight-legged stand good.
        assertTrue(ui.needsCorrection || ui.statuses.any { (it.score ?: 1f) < 0.8f })
    }

    // ---- Fakes -------------------------------------------------------------

    /**
     * Returns [overrides] for named cues and a mid-target good value for the
     * rest, so each test controls exactly which cue misses.
     */
    private class FakeAnalyzer(
        var overrides: Map<CueId, Float?>,
    ) : SkillAnalyzer {
        override val skill: Skill = Skill.Receiving
        override fun segmentationSignal(frame: PoseFrame): Float? = 0f
        override fun measure(cue: FormCue, window: RepWindow): Float? =
            overrides[cue.id] ?: midTarget(cue)

        private fun midTarget(cue: FormCue): Float =
            (cue.target.start + cue.target.endInclusive) / 2f
    }

    /** Alternates two miss-maps frame by frame, for the flicker case. */
    private class AlternatingAnalyzer(
        private val first: Map<CueId, Float?>,
        private val second: Map<CueId, Float?>,
    ) : SkillAnalyzer {
        override val skill: Skill = Skill.Receiving
        private var calls = 0
        override fun segmentationSignal(frame: PoseFrame): Float? = 0f
        override fun measure(cue: FormCue, window: RepWindow): Float? {
            val active = if (calls % 2 == 0) first else second
            // Count per frame, not per cue: four cues per frame would flip the
            // parity mid-frame. Bump only on the first cue of each frame.
            if (cue.id == FormCueCatalog.forSkill(skill).first().id) calls++
            return active[cue.id] ?: midTarget(cue)
        }

        private fun midTarget(cue: FormCue): Float =
            (cue.target.start + cue.target.endInclusive) / 2f
    }

    private fun frameAt(timestampMs: Long): PoseFrame =
        GuidedPoseFixtures.front(timestampMs)
}

