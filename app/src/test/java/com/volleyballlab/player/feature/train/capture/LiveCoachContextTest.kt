package com.volleyballlab.player.feature.train.capture

import com.volleyballlab.player.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class LiveCoachContextTest {
    private val link = CoachLink("coach", "Ana", "Spikers", MembershipStatus.Active, 0)
    private fun session(id: String, skill: Skill, at: Long) = SessionResult(id, skill, "drill", at, 1_000, emptyList())
    private fun note(id: String, session: String?, at: Long, coach: String = "coach") = CoachNote(id, coach, "player", session, "Text $id", at)
    private val sessions = listOf(session("spike", Skill.Spiking, 100), session("pass", Skill.Receiving, 200))

    @Test fun feedbackOnAnotherSkillNeverShowsDuringThisOne() {
        val notes = listOf(note("newest", "spike", 900))
        assertNull(LiveCoachContext.of(Skill.Receiving, link, emptyList(), notes, sessions).latestNote)
        assertFalse(LiveCoachContext.of(Skill.Receiving, link, emptyList(), notes, sessions).hasCoachContent)
        val spiking = LiveCoachContext.of(Skill.Spiking, link, emptyList(), notes, sessions)
        assertEquals("newest", spiking.latestNote?.id)
        assertEquals("labelled with the session it is about", 100L, spiking.noteSessionAtEpochMs)
        assertTrue(spiking.hasCoachContent)
    }

    @Test fun theNewestNoteOnTheSameSkillIsTheOneShown() {
        val notes = listOf(note("other", "spike", 900), note("old", "pass", 300), note("new", "pass", 500), note("general", null, 950),
            note("past coach", "pass", 990, coach = "someone"), note("unknown session", "gone", 995))
        assertEquals("new", LiveCoachContext.of(Skill.Receiving, link, emptyList(), notes, sessions).latestNote?.id)
    }

    @Test fun onlyADrillForThisSkillIsShown() {
        fun drill(id: String, skill: Skill, status: AssignmentStatus = AssignmentStatus.Pending) = CoachAssignment(id, "coach", "player", "Drill $id",
            skill, DrillLevel.Beginner, "", "", 3, 10, null, status, 1)
        val serving = drill("serve", Skill.Serving)
        assertNull(LiveCoachContext.of(Skill.Receiving, link, listOf(serving), emptyList(), sessions).assignment)
        assertNull(LiveCoachContext.of(Skill.Receiving, link, listOf(serving), emptyList(), sessions, assignmentId = "serve").assignment)
        val done = drill("done", Skill.Receiving, AssignmentStatus.Completed)
        val open = drill("open", Skill.Receiving)
        assertEquals("open", LiveCoachContext.of(Skill.Receiving, link, listOf(serving, done, open), emptyList(), sessions).assignment?.id)
        assertEquals("a drill opened from its own page", "done",
            LiveCoachContext.of(Skill.Receiving, link, listOf(done, open), emptyList(), sessions, assignmentId = "done").assignment?.id)
    }

    @Test fun aRequestThatIsNotAcceptedShowsNothing() {
        val pending = link.copy(status = MembershipStatus.Pending)
        assertFalse(LiveCoachContext.of(Skill.Spiking, pending, emptyList(), listOf(note("n", "spike", 1)), sessions).hasHumanCoach)
        assertFalse(LiveCoachContext.of(Skill.Spiking, null, emptyList(), emptyList(), sessions).hasHumanCoach)
    }

    @Test fun onlyUnreadFeedbackThatStillExistsIsCounted() {
        val notes = listOf(note("a", "spike", 1), note("b", "pass", 2), note("c", null, 3))
        val inbox = notes.map(NotificationEvents::feedback).map { if (it.id == "feedback:b") it.copy(isRead = true) else it } +
            NotificationEvents.feedback(note("deleted", "pass", 4)) +
            TrainingNotification("assignment:a", TrainingNotificationType.CoachAssignment, "t", "m", 5)
        assertEquals(setOf("a", "c"), NotificationEvents.unreadFeedback(inbox, notes))
        assertTrue(NotificationEvents.unreadFeedback(inbox.map { it.copy(isRead = true) }, notes).isEmpty())
        assertTrue(NotificationEvents.unreadFeedback(emptyList(), notes).isEmpty())
    }
}
