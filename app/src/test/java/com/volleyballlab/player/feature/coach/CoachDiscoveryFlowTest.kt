package com.volleyballlab.player.feature.coach

import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.domain.repository.*
import com.volleyballlab.player.drilltest.DrillTestPlans
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoachDiscoveryFlowTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun proActivationUnlocksDiscoveryAndFreeCannotSendRequests() = runTest(dispatcher) {
        val coaches = FakeCoaches()
        val plans = DrillTestPlans()
        val model = CoachLinkViewModel(coaches, plans, sessions)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        advanceUntilIdle()
        model.sendRequest("coach")
        advanceUntilIdle()
        assertEquals(0, coaches.attempts)
        assertNotNull(model.error.value)
        plans.plan.value = UserPlan.Pro
        advanceUntilIdle()
        assertTrue(model.state.value.plan.isPro)
        model.sendRequest("coach")
        advanceUntilIdle()
        assertEquals("coach", model.requestedCoach.value)
        assertNull(model.error.value)
    }

    @Test fun repeatedRequestTapWritesOnceAndSignalsSuccessAfterConfirmation() = runTest(dispatcher) {
        val coaches = FakeCoaches()
        val gate = CompletableDeferred<Unit>()
        coaches.beforeRequest = { gate.await() }
        val model = CoachLinkViewModel(coaches, DrillTestPlans(UserPlan.Pro), sessions)
        model.sendRequest("coach")
        model.sendRequest("coach")
        runCurrent()
        assertEquals(1, coaches.attempts)
        assertTrue(model.requesting.value)
        assertNull(model.requestedCoach.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("coach", model.requestedCoach.value)
        assertFalse(model.requesting.value)
    }

    @Test fun failedRequestStaysOnProfileAndCanRetry() = runTest(dispatcher) {
        val coaches = FakeCoaches()
        coaches.fail = true
        val model = CoachLinkViewModel(coaches, DrillTestPlans(UserPlan.Pro), sessions)
        model.sendRequest("coach")
        advanceUntilIdle()
        assertNull(model.requestedCoach.value)
        assertNotNull(model.error.value)
        assertFalse(model.requesting.value)
        coaches.fail = false
        model.sendRequest("coach")
        advanceUntilIdle()
        assertEquals("coach", model.requestedCoach.value)
        assertNull(model.error.value)
    }

    @Test fun failedSessionSharingKeepsAnActionableErrorAndCanRetry() = runTest(dispatcher) {
        val coaches = FakeCoaches()
        coaches.failSubmission = true
        val model = CoachLinkViewModel(coaches, DrillTestPlans(UserPlan.Pro), sessions)
        model.submit("session")
        advanceUntilIdle()
        assertNotNull(model.submitError.value)
        assertFalse(model.sending.value)
        coaches.failSubmission = false
        model.submit("session")
        advanceUntilIdle()
        assertNull(model.submitError.value)
        assertEquals(2, coaches.submissionAttempts)
    }

    @Test fun leavingFailureDoesNotCrashOrHideTheRetry() = runTest(dispatcher) {
        val coaches = FakeCoaches()
        coaches.failLeaving = true
        val model = CoachLinkViewModel(coaches, DrillTestPlans(UserPlan.Pro), sessions)
        model.leave()
        advanceUntilIdle()
        assertNotNull(model.leaveError.value)
        assertFalse(model.leaving.value)
        coaches.failLeaving = false
        model.leave()
        advanceUntilIdle()
        assertNull(model.leaveError.value)
    }

    private val sessions = object : SessionRepository {
        override val sessions = flowOf(emptyList<SessionResult>())
        override suspend fun save(session: SessionResult) = Unit
        override suspend fun byId(id: String): SessionResult? = null
        override suspend fun delete(id: String) = Unit
        override suspend fun deleteVideo(id: String) = Unit
    }

    private class FakeCoaches : CoachRepository {
        var attempts = 0
        var fail = false
        var failSubmission = false
        var failLeaving = false
        var submissionAttempts = 0
        var beforeRequest: suspend () -> Unit = {}
        override val coachProfile = flowOf(CoachProfile.Empty)
        override val roster = flowOf(emptyList<RosterMember>())
        override val assignments = flowOf(emptyList<CoachAssignment>())
        override val submissions = flowOf(emptyList<SessionSubmission>())
        override val notes = flowOf(emptyList<CoachNote>())
        override val assignmentsForMe = assignments
        override val mySubmissions = submissions
        override val notesForMe = notes
        override val myCoach = flowOf<CoachLink?>(null)
        override val coachDirectory = flowOf(listOf(CoachPreview("coach", CoachProfile(name = "Coach Rivera"), 2)))
        override suspend fun requestJoin(coachUid: String): JoinOutcome {
            attempts++
            beforeRequest()
            if (fail) error("Offline")
            return JoinOutcome.Success(CoachLink(coachUid, "Coach Rivera", "Team", MembershipStatus.Pending, 1L))
        }
        override suspend fun saveCoachProfile(profile: CoachProfile) = Unit
        override suspend fun publishListing() = Unit
        override suspend fun approve(playerUid: String) = Unit
        override suspend fun decline(playerUid: String) = Unit
        override suspend fun removeFromRoster(playerUid: String) = Unit
        override suspend fun saveAssignment(assignment: CoachAssignment) = Unit
        override suspend fun deleteAssignment(id: String) = Unit
        override suspend fun postNote(note: CoachNote) = Unit
        override suspend fun leaveCoach() { if (failLeaving) error("Offline") }
        override suspend fun submitSession(sessionId: String) { submissionAttempts++; if (failSubmission) error("Offline") }
        override suspend fun submitAssignmentSession(assignmentId: String, session: SessionResult) = Unit
        override suspend fun updateAssignmentStatus(id: String, status: AssignmentStatus) = Unit
    }
}
