package com.volleyballlab.player.feature.coachmode

import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.domain.repository.*
import com.volleyballlab.player.drilltest.DrillTestAuth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class CoachFeedbackFlowTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private val sessions = object : SessionDirectory {
        override fun sessionsOf(uid: String) = flowOf(emptyList<SessionResult>())
    }

    @Test fun repeatedTapDuringDeliveryWritesOnlyOnce() = runTest(dispatcher) {
        val coaches = FakeCoaches()
        val gate = CompletableDeferred<Unit>()
        coaches.beforeSave = { gate.await() }
        val model = CoachReviewViewModel(coaches, DrillTestAuth("coach"), sessions)
        var sent = 0
        model.postNote("player", "session", "  Reach high.  ") { sent++ }
        model.postNote("player", "session", "Reach high.") { sent++ }
        assertTrue(model.noteDelivery.value.sending)
        runCurrent()
        assertEquals(1, coaches.attempts)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, sent)
        assertEquals("Reach high.", coaches.saved.single().text)
        assertFalse(model.noteDelivery.value.sending)
    }

    @Test fun failedDeliveryNeverClearsDraftThroughSuccessCallbackAndCanRetry() = runTest(dispatcher) {
        val coaches = FakeCoaches()
        coaches.fail = true
        val model = CoachReviewViewModel(coaches, DrillTestAuth("coach"), sessions)
        var sent = false
        model.postNote("player", "session", "Keep the trunk upright.") { sent = true }
        advanceUntilIdle()
        assertFalse(sent)
        assertTrue(model.noteDelivery.value.failed)
        assertFalse(model.noteDelivery.value.sending)
        coaches.fail = false
        model.postNote("player", "session", "Keep the trunk upright.") { sent = true }
        advanceUntilIdle()
        assertTrue(sent)
        assertFalse(model.noteDelivery.value.failed)
        assertEquals(1, coaches.saved.size)
    }

    @Test fun blankOrSignedOutFeedbackNeverWrites() = runTest(dispatcher) {
        val coaches = FakeCoaches()
        val auth = DrillTestAuth("coach")
        val model = CoachReviewViewModel(coaches, auth, sessions)
        model.postNote("player", "session", "   ") { fail("Blank feedback sent") }
        auth.switch(null)
        model.postNote("player", "session", "Reach high.") { fail("Signed-out feedback sent") }
        advanceUntilIdle()
        assertEquals(0, coaches.attempts)
        assertTrue(model.noteDelivery.value.failed)
    }

    private class FakeCoaches : CoachRepository {
        var fail = false
        var attempts = 0
        var beforeSave: suspend () -> Unit = {}
        val saved = mutableListOf<CoachNote>()
        override val coachProfile = flowOf(CoachProfile.Empty)
        override val roster = flowOf(emptyList<RosterMember>())
        override val assignments = flowOf(emptyList<CoachAssignment>())
        override val submissions = flowOf(emptyList<SessionSubmission>())
        override val notes = flowOf(emptyList<CoachNote>())
        override val assignmentsForMe = assignments
        override val mySubmissions = submissions
        override val notesForMe = notes
        override val myCoach = flowOf<CoachLink?>(null)
        override val coachDirectory = flowOf(emptyList<CoachPreview>())
        override suspend fun postNote(note: CoachNote) {
            attempts++
            beforeSave()
            if (fail) error("Offline")
            saved += note
        }
        override suspend fun saveCoachProfile(profile: CoachProfile) = Unit
        override suspend fun publishListing() = Unit
        override suspend fun approve(playerUid: String) = Unit
        override suspend fun decline(playerUid: String) = Unit
        override suspend fun removeFromRoster(playerUid: String) = Unit
        override suspend fun saveAssignment(assignment: CoachAssignment) = Unit
        override suspend fun deleteAssignment(id: String) = Unit
        override suspend fun requestJoin(coachUid: String) = JoinOutcome.Failure(JoinFailure.Unavailable)
        override suspend fun leaveCoach() = Unit
        override suspend fun submitSession(sessionId: String) = Unit
        override suspend fun submitAssignmentSession(assignmentId: String, session: SessionResult) = Unit
        override suspend fun updateAssignmentStatus(id: String, status: AssignmentStatus) = Unit
    }
}
