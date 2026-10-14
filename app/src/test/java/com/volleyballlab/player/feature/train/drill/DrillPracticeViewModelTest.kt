package com.volleyballlab.player.feature.train.drill

import androidx.lifecycle.ViewModelStore
import com.volleyballlab.player.data.drill.*
import com.volleyballlab.player.data.skill.StaticDrillRepository
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.drilltest.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DrillPracticeViewModelTest {
    private val auth = DrillTestAuth()
    private val plans = DrillTestPlans()
    private val storage = DrillTestStore()
    private val repository = DataStoreDrillPracticeRepository(storage, auth)
    private val access = DrillAccessController(auth, plans, StaticDrillRepository())
    private val models = ViewModelStore()
    private var now = 0L
    private var index = 0
    private fun model(sets: Int = 3, drillId: String = "set-wall-reps", resume: String? = null): DrillPracticeViewModel =
        DrillPracticeViewModel(drillId, sets, 10, 30, resume, access, repository, auth, { now }).also { models.put("model-${index++}", it) }
    @After fun cleanUp() { models.clear(); Dispatchers.resetMain() }
    private fun workoutTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally { models.clear(); runCurrent() }
    }

    @Test fun confirmingPausesTimeAndRecordsActualRepsExactlyOnce() = workoutTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = model()
        runCurrent(); vm.startSet(); runCurrent()
        now = 2000; advanceTimeBy(250); runCurrent()
        vm.requestRepConfirmation(); runCurrent()
        assertTrue(vm.state.value.confirmingReps)
        now = 12_000; advanceTimeBy(250); runCurrent()
        assertEquals(2000L, vm.state.value.draft?.workMs)
        vm.confirmSet(6); vm.confirmSet(6); runCurrent()
        assertEquals(1, vm.state.value.draft?.sets?.size)
        assertEquals(6, vm.state.value.draft?.totalReps)
        assertEquals(DrillPracticeStage.Resting, vm.state.value.draft?.stage)
    }
    @Test fun backgroundPausesAndForegroundDoesNotAutomaticallyResume() = workoutTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = model()
        runCurrent(); vm.startSet(); runCurrent()
        now = 1000; vm.foregroundChanged(false); runCurrent()
        now = 61_000; advanceTimeBy(250); runCurrent()
        vm.foregroundChanged(true); runCurrent()
        assertEquals(DrillPracticeStage.Paused, vm.state.value.draft?.stage)
        assertEquals(1000L, vm.state.value.draft?.workMs)
        vm.resume(); runCurrent()
        now = 62_000; advanceTimeBy(250); runCurrent()
        assertEquals(2000L, vm.state.value.draft?.workMs)
    }
    @Test fun restartedViewModelRecoversConfirmedSetsPaused() = workoutTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val first = model()
        runCurrent(); first.startSet(); runCurrent(); first.requestRepConfirmation(); runCurrent(); first.confirmSet(8); runCurrent()
        first.foregroundChanged(false); runCurrent()
        val id = requireNotNull(first.state.value.draft).id
        val resumed = model(resume = id)
        runCurrent()
        assertEquals(DrillPracticeStage.Paused, resumed.state.value.draft?.stage)
        assertEquals(8, resumed.state.value.draft?.totalReps)
        assertEquals(id, resumed.state.value.draft?.id)
    }
    @Test fun failedFinishCanRetryWithoutLosingSetsOrDuplicatingHistory() = workoutTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = model()
        runCurrent(); vm.startSet(); runCurrent(); vm.requestRepConfirmation(); runCurrent(); vm.confirmSet(9); runCurrent()
        val id = requireNotNull(vm.state.value.draft).id
        storage.failNextWrite = true
        vm.finish(); runCurrent()
        assertNotNull(vm.state.value.error)
        assertNull(vm.state.value.savedId)
        assertEquals(9, vm.state.value.draft?.totalReps)
        assertTrue(repository.history.first().isEmpty())
        vm.retrySave(); runCurrent()
        assertEquals(id, vm.state.value.savedId)
        vm.finish(); vm.retrySave(); runCurrent()
        assertEquals(1, repository.history.first().size)
        assertFalse(repository.history.first().single().completed)
    }
    @Test fun startedPremiumWorkoutCanFinishButNewPremiumStartIsBlockedAfterExpiry() = workoutTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        plans.plan.value = UserPlan.Pro
        val vm = model(1, "serve-jump-float")
        runCurrent()
        plans.plan.value = UserPlan.Free
        vm.startSet(); runCurrent(); vm.requestRepConfirmation(); runCurrent(); vm.confirmSet(10); runCurrent()
        assertNotNull(vm.state.value.savedId)
        val denied = model(1, "serve-jump-float")
        runCurrent()
        assertNull(denied.state.value.draft)
        assertNotNull(denied.state.value.error)
    }
    @Test fun accountSwitchCannotSaveIntoNewAccount() = workoutTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = model()
        runCurrent(); vm.startSet(); runCurrent()
        auth.switch("athlete-b")
        advanceTimeBy(250); runCurrent()
        vm.finish(); runCurrent()
        assertNotNull(vm.state.value.error)
        assertTrue(repository.history.first().isEmpty())
        assertNull(repository.draft.first())
    }
    @Test fun noConfirmedSetsCreatesNoHistoryAndExistingDraftCannotBeOverwritten() = workoutTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = model()
        runCurrent()
        val blocked = model()
        runCurrent()
        assertNotNull(blocked.state.value.error)
        assertEquals(vm.state.value.draft?.id, repository.draft.first()?.id)
        vm.finish(); runCurrent()
        assertTrue(vm.state.value.discarded)
        assertTrue(repository.history.first().isEmpty())
        assertNull(repository.draft.first())
    }
    @Test fun completedCheckpointRecoveredAfterProcessExitIsSavedOnce() = workoutTest {
        val draft = DrillPracticeDraft("final-checkpoint", "set-wall-reps", "Wall setting reps", Skill.Setting, DrillWorkoutConfig(1, 10, 30), 1000)
        repository.checkpoint("athlete-a", DrillPracticeEngine.completeSet(DrillPracticeEngine.startSet(draft), 7))
        val vm = model(1, resume = draft.id)
        runCurrent()
        assertEquals(draft.id, vm.state.value.savedId)
        assertEquals(7, repository.history.first().single().totalReps)
        assertNull(repository.draft.first())
    }
    @Test fun leavePausedWaitsForCheckpointAndCanRecoverConfirmedSets() = workoutTest {
        val vm = model()
        runCurrent(); vm.startSet(); runCurrent(); vm.requestRepConfirmation(); runCurrent(); vm.confirmSet(8); runCurrent()
        storage.failNextWrite = true
        vm.leavePaused(); runCurrent()
        assertFalse(vm.state.value.leftPaused)
        assertNotNull(vm.state.value.error)
        vm.leavePaused(); runCurrent()
        assertTrue(vm.state.value.leftPaused)
        val saved = requireNotNull(repository.draft.first())
        assertEquals(DrillPracticeStage.Paused, saved.stage)
        val recovered = model(resume = saved.id)
        runCurrent()
        assertEquals(8, recovered.state.value.draft?.totalReps)
    }
    @Test fun cameraGrantSurvivesPlanChangeButIsNotSharedWithNewCameraEntry() {
        val grant = DrillCameraGrantViewModel()
        models.put("camera", grant)
        grant.grant("athlete-a")
        plans.plan.value = UserPlan.Free
        assertEquals("athlete-a", grant.uid.value)
        val nextEntry = DrillCameraGrantViewModel()
        models.put("next-camera", nextEntry)
        assertNull(nextEntry.uid.value)
        grant.revoke()
        assertNull(grant.uid.value)
    }
    @Test fun expiredPremiumDraftCannotResumeButSavedPremiumHistoryIsReadable() = workoutTest {
        plans.plan.value = UserPlan.Pro
        val vm = model(3, "serve-jump-float")
        runCurrent(); vm.startSet(); runCurrent(); vm.requestRepConfirmation(); runCurrent(); vm.confirmSet(5); runCurrent()
        val id = requireNotNull(vm.state.value.draft).id
        vm.leavePaused(); runCurrent()
        plans.plan.value = UserPlan.Free
        val denied = model(3, "serve-jump-float", id)
        runCurrent()
        assertNull(denied.state.value.draft)
        assertNotNull(denied.state.value.error)
        assertEquals(id, repository.draft.first()?.id)
        val draft = requireNotNull(repository.draft.first())
        repository.finish("athlete-a", draft.record(2000))
        assertEquals(id, repository.history.first().single().id)
    }
}
