package com.volleyballlab.player.domain.model

import org.junit.Assert.*
import org.junit.Test

class FeedbackHistoryTest {
    private fun session(id: String, skill: Skill = Skill.Receiving, weak: CueId = CueId.PassPlatformStraight) = SessionResult(id, skill, "drill", 0, 1_000,
        (0 until 5).map { RepResult(it, 0, 500, 1_000, FormCueCatalog.poseCues(skill).map { cue -> CueScore(cue.id, 1f, if (cue.id == weak) .2f else .95f) }) })
    private fun sent(id: String, reviewed: Boolean) = SessionSubmission(id, "coach", "player", 1, if (reviewed) 2 else null)

    @Test fun countsWhatWasSentAnsweredAndIsWaiting() {
        val history = FeedbackHistory.of(listOf(sent("a", true), sent("b", false), sent("c", true)), emptyList())
        assertEquals(listOf(3, 2, 1), listOf(history.sent, history.reviewed, history.waiting))
        assertNull("no sessions on this phone, so nothing measured to summarise", history.workOn)
    }

    @Test fun aCheckIsOnlyCalledRepeatedWhenItLedInSeveralSentSessionsOfOneSkill() {
        val sessions = listOf(session("a"), session("b"), session("c", weak = CueId.PassKneeFlexion),
            session("never sent"), session("spike", Skill.Spiking, CueId.SpikeArmExtension))
        val submissions = listOf("a", "b", "c", "spike").map { sent(it, true) }
        assertEquals(RepeatedCue(Skill.Receiving, CueId.PassPlatformStraight, 2, 3), FeedbackHistory.of(submissions, sessions).workOn)
        assertNull(FeedbackHistory.of(submissions.take(1), sessions).workOn)
    }
}
