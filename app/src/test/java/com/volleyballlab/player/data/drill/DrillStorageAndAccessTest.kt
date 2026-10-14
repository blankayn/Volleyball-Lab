package com.volleyballlab.player.data.drill

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.core.okio.OkioStorage
import com.volleyballlab.player.data.skill.StaticDrillRepository
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.domain.repository.PlanRepository
import com.volleyballlab.player.drilltest.*
import java.io.File
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DrillStorageAndAccessTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun draft(id: String = "workout") = DrillPracticeDraft(id, "set-wall-reps", "Wall setting reps", Skill.Setting, DrillWorkoutConfig(), 1000)
    private fun record(id: String = "workout") = DrillPracticeEngine.completeSet(DrillPracticeEngine.startSet(draft(id)), 7).record(2000)

    @Test fun writesAreAccountScopedAndStaleAccountWritesFail() = runBlocking {
        val auth = DrillTestAuth()
        val repository = DataStoreDrillPracticeRepository(DrillTestStore(), auth)
        repository.checkpoint("athlete-a", draft())
        repository.finish("athlete-a", record())
        auth.switch("athlete-b")
        assertTrue(repository.history.first().isEmpty())
        assertNull(repository.draft.first())
        assertTrue(runCatching { repository.finish("athlete-a", record("other")) }.isFailure)
        auth.switch("athlete-a")
        assertEquals("workout", repository.history.first().single().id)
        assertNull(repository.draft.first())
        auth.switch(null)
        assertTrue(repository.history.first().isEmpty())
    }
    @Test fun finishIsAtomicAndIdempotentAndCannotResurrectDraft() = runBlocking {
        val auth = DrillTestAuth()
        val repository = DataStoreDrillPracticeRepository(DrillTestStore(), auth)
        repository.checkpoint("athlete-a", draft())
        repository.finish("athlete-a", record())
        repository.finish("athlete-a", record())
        repository.checkpoint("athlete-a", draft())
        assertEquals(1, repository.history.first().size)
        assertNull(repository.draft.first())
    }
    @Test fun failedSaveKeepsDraftForRetry() = runBlocking {
        val auth = DrillTestAuth()
        val store = DrillTestStore()
        val repository = DataStoreDrillPracticeRepository(store, auth)
        repository.checkpoint("athlete-a", draft())
        store.failNextWrite = true
        assertTrue(runCatching { repository.finish("athlete-a", record()) }.isFailure)
        assertTrue(repository.history.first().isEmpty())
        assertEquals(draft(), repository.draftFor("athlete-a"))
        repository.finish("athlete-a", record())
        assertEquals(7, repository.history.first().single().totalReps)
    }
    @Test fun discardOnlyRemovesMatchingDraftAndEmptyWorkoutCannotBeSaved() = runBlocking {
        val repository = DataStoreDrillPracticeRepository(DrillTestStore(), DrillTestAuth())
        repository.checkpoint("athlete-a", draft())
        repository.discard("athlete-a", "different")
        assertNotNull(repository.draft.first())
        repository.discard("athlete-a", "workout")
        assertNull(repository.draft.first())
        assertTrue(runCatching { repository.finish("athlete-a", draft().record(2000)) }.isFailure)
    }
    @Test fun realDataStoreReopensDraftAndHistoryFromDisk() = runBlocking {
        val auth = DrillTestAuth()
        val file = File(temporary.root, "practice.preferences_pb")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Android FileStorage uses renameTo, which cannot replace an existing file on Windows.
        // Use DataStore's cross-platform storage with the same Preferences serializer for host IO.
        val first = DataStoreDrillPracticeRepository(PreferenceDataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { file.toOkioPath() }, scope = firstScope), auth)
        try {
            first.finish("athlete-a", record("old"))
            first.checkpoint("athlete-a", draft())
        } finally { firstScope.cancel(); firstScope.coroutineContext[Job]?.join() }
        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val next = DataStoreDrillPracticeRepository(PreferenceDataStoreFactory.create(
                storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { file.toOkioPath() }, scope = nextScope), auth)
            assertEquals(draft(), next.draftFor("athlete-a"))
            assertEquals("old", next.history.first().single().id)
        } finally { nextScope.cancel(); nextScope.coroutineContext[Job]?.join() }
    }
    @Test fun premiumStartsAreDeniedUntilTrustedPlanGrantsAccess() = runBlocking {
        val auth = DrillTestAuth()
        val plans = DrillTestPlans()
        val controller = DrillAccessController(auth, plans, StaticDrillRepository())
        assertTrue(runCatching { controller.requireAccess("serve-jump-float") }.isFailure)
        plans.plan.value = UserPlan.Pro
        assertEquals("serve-jump-float", controller.requireAccess("serve-jump-float").second.id)
        assertTrue(runCatching { controller.requireAccess("unknown") }.isFailure)
        plans.plan.value = UserPlan.Free
        assertTrue(runCatching { controller.requireAccess("serve-jump-float") }.isFailure)
    }
    @Test fun startersDoNotWaitForUnresolvedPremiumEntitlement() = runBlocking {
        val unknown = object : PlanRepository {
            override val plan = emptyFlow<UserPlan>()
            override suspend fun upgradeToPro() = Unit
            override suspend fun downgradeToFree() = Unit
        }
        val access = DrillAccessController(DrillTestAuth(), unknown, StaticDrillRepository())
        assertEquals("set-wall-reps", access.requireAccess("set-wall-reps").second.id)
    }
    @Test fun accountSwitchDuringAccessCheckCannotReusePreviousGrant() = runBlocking {
        val auth = DrillTestAuth()
        val switching = object : PlanRepository {
            override val plan = flow { auth.switch("athlete-b"); emit(UserPlan.Pro) }
            override suspend fun upgradeToPro() = Unit
            override suspend fun downgradeToFree() = Unit
        }
        assertTrue(runCatching { DrillAccessController(auth, switching, StaticDrillRepository()).requireAccess("serve-jump-float") }.isFailure)
    }
    @Test fun ownerGetsAllDrillsFromExistingOwnerPolicy() = runBlocking {
        val auth = DrillTestAuth(ProEntitlement.OWNER_UID)
        val plans = DrillTestPlans(ProEntitlement.plan(auth.currentUid, null, null, 0))
        val controller = DrillAccessController(auth, plans, StaticDrillRepository())
        for (skill in Skill.All) for (drill in StaticDrillRepository().drillsFor(skill)) assertEquals(drill.id, controller.requireAccess(drill.id).second.id)
    }
}
