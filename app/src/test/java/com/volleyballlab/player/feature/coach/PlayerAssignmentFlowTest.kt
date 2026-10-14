package com.volleyballlab.player.feature.coach

import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerAssignmentFlowTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private val task = CoachAssignment("task", "coach", "player", "Setting practice", Skill.Setting,
        DrillLevel.Beginner, "Keep your hands high", "Perform the assigned repetitions", 1, 5, null,
        AssignmentStatus.Pending, 100, libraryDrillId = "selected-drill")
    private val result = SessionResult("result", Skill.Setting, "selected-drill", 200, 1_000,
        listOf(RepResult(0, 0, 500, 1_000, emptyList())), assignmentId = "task")

    @Test fun failedSendKeepsRecordingAndAllowsRetryWithoutDuplicateSubmission() = runTest(dispatcher) {
        val coaches = FakeCoaches(task)
        val sessions = FakeSessions(listOf(result))
        val model = PlayerAssignmentViewModel(task.id, coaches, FakeDrills(), sessions)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        advanceUntilIdle()
        coaches.failSend = true
        model.send(result)
        advanceUntilIdle()
        assertNull(model.sentSession.value)
        assertNotNull(model.error.value)
        assertEquals(result, model.state.value.sessions.single())
        assertEquals(AssignmentStatus.Pending, coaches.assignmentsForMe.value.single().status)
        coaches.failSend = false
        model.send(result)
        advanceUntilIdle()
        assertEquals(result.id, model.sentSession.value)
        assertNull(model.error.value)
        assertEquals(AssignmentStatus.Completed, coaches.assignmentsForMe.value.single().status)
        model.send(result)
        advanceUntilIdle()
        assertEquals(2, coaches.sendAttempts)
        assertEquals(1, coaches.mySubmissions.value.size)
    }

    @Test fun assignmentOnlyShowsItsOwnRecordingAndCoachFeedback() = runTest(dispatcher) {
        val coaches = FakeCoaches(task)
        coaches.notesForMe.value = listOf(
            CoachNote("own", "coach", "player", result.id, "Raise your hands", 300),
            CoachNote("other", "coach", "player", "different-result", "Other task", 300),
        )
        val sessions = FakeSessions(listOf(result, result.copy(id = "different-result", assignmentId = "other-task")))
        val model = PlayerAssignmentViewModel(task.id, coaches, FakeDrills(), sessions)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        advanceUntilIdle()
        assertEquals(listOf(result), model.state.value.sessions)
        assertEquals("Raise your hands", model.state.value.notes.single().text)
    }

    @Test fun selectedLibraryDrillStartsTheAssignedSkillAndDisconnectPreventsStartOrSend() = runTest(dispatcher) {
        val coaches = FakeCoaches(task)
        val model = PlayerAssignmentViewModel(task.id, coaches, FakeDrills(), FakeSessions(listOf(result)))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        advanceUntilIdle()
        var selected: String? = null
        model.start { skill, id -> assertEquals(task.skill, skill); selected = id }
        advanceUntilIdle()
        assertEquals("selected-drill", selected)
        assertEquals(AssignmentStatus.InProgress, coaches.assignmentsForMe.value.single().status)
        coaches.myCoach.value = null
        advanceUntilIdle()
        selected = null
        model.start { _, id -> selected = id }
        model.send(result)
        advanceUntilIdle()
        assertNull(selected)
        assertEquals(0, coaches.sendAttempts)
    }

    @Test fun wrongTaskAndEmptyRecordingsCannotBeSubmitted() = runTest(dispatcher) {
        val coaches = FakeCoaches(task)
        val model = PlayerAssignmentViewModel(task.id, coaches, FakeDrills(), FakeSessions(listOf(result)))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        advanceUntilIdle()
        model.send(result.copy(assignmentId = "other-task"))
        model.send(result.copy(reps = emptyList()))
        model.send(result.copy(skill = Skill.Serving))
        advanceUntilIdle()
        assertEquals(0, coaches.sendAttempts)
    }

    private class FakeDrills : DrillRepository {
        val chosen = Drill("selected-drill", Skill.Setting, "Setting", "", 5, 1, DrillLevel.Beginner)
        override suspend fun byId(id: String) = chosen.takeIf { it.id == id }
        override suspend fun drillsFor(skill: Skill) = listOf(chosen).filter { it.skill == skill }
    }

    private class FakeSessions(initial: List<SessionResult>) : SessionRepository {
        override val sessions = MutableStateFlow(initial)
        override suspend fun save(session: SessionResult) { sessions.value = sessions.value.filterNot { it.id == session.id } + session }
        override suspend fun byId(id: String) = sessions.value.firstOrNull { it.id == id }
        override suspend fun delete(id: String) { sessions.value = sessions.value.filterNot { it.id == id } }
    }

    private class FakeCoaches(task: CoachAssignment) : CoachRepository {
        var failSend = false
        var sendAttempts = 0
        override val assignmentsForMe = MutableStateFlow(listOf(task))
        override val myCoach = MutableStateFlow<CoachLink?>(CoachLink("coach", "Coach", "Team", MembershipStatus.Active, 100))
        override val mySubmissions = MutableStateFlow<List<SessionSubmission>>(emptyList())
        override val notesForMe = MutableStateFlow<List<CoachNote>>(emptyList())
        override val coachProfile = flowOf(CoachProfile.Empty)
        override val roster = flowOf(emptyList<RosterMember>())
        override val assignments = assignmentsForMe
        override val submissions = mySubmissions
        override val notes = notesForMe
        override val coachDirectory = flowOf(emptyList<CoachPreview>())
        override suspend fun saveCoachProfile(profile: CoachProfile) = Unit
        override suspend fun publishListing() = Unit
        override suspend fun approve(playerUid: String) = Unit
        override suspend fun decline(playerUid: String) = Unit
        override suspend fun removeFromRoster(playerUid: String) = Unit
        override suspend fun saveAssignment(assignment: CoachAssignment) = Unit
        override suspend fun deleteAssignment(id: String) = Unit
        override suspend fun postNote(note: CoachNote) = Unit
        override suspend fun requestJoin(coachUid: String) = JoinOutcome.Failure(JoinFailure.Unavailable)
        override suspend fun leaveCoach() = Unit
        override suspend fun submitSession(sessionId: String) = Unit
        override suspend fun updateAssignmentStatus(id: String, status: AssignmentStatus) {
            assignmentsForMe.value = assignmentsForMe.value.map { if (it.id == id) it.copy(status = status) else it }
        }
        override suspend fun submitAssignmentSession(assignmentId: String, session: SessionResult) {
            sendAttempts++
            if (failSend) error("Offline")
            mySubmissions.value += SessionSubmission(session.id, "coach", "player", 300)
            updateAssignmentStatus(assignmentId, AssignmentStatus.Completed)
        }
    }
}
