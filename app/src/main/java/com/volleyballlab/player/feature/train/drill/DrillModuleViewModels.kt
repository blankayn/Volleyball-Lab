package com.volleyballlab.player.feature.train.drill

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.data.drill.*
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.domain.repository.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DrillLibraryState(
    val drills: List<Drill> = emptyList(),
    val entitlement: DrillEntitlement = DrillEntitlement(),
    val history: List<DrillPracticeRecord> = emptyList(),
    val draft: DrillPracticeDraft? = null,
    val loaded: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DrillLibraryViewModel(
    private val drills: DrillRepository,
    private val practice: DrillPracticeRepository,
    private val access: DrillAccessController,
) : ViewModel() {
    private val _state = MutableStateFlow(DrillLibraryState())
    val state = _state.asStateFlow()
    private var loadJob: Job? = null
    init { refresh() }
    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = DrillLibraryState()
            val catalogue = Skill.All.flatMap { drills.drillsFor(it) }
            access.entitlement.flatMapLatest { entitlement -> flow {
                emit(DrillLibraryState(catalogue, entitlement))
                emitAll(combine(practice.history, practice.draft) { history, draft ->
                    if (access.currentUid == entitlement.uid) DrillLibraryState(catalogue, entitlement, history, draft, true)
                    else DrillLibraryState(catalogue, DrillEntitlement(uid = access.currentUid))
                })
            } }.catch { failure ->
                if (failure is CancellationException) throw failure
                emit(_state.value.copy(error = "Drill data could not be loaded. Please retry."))
            }.collect { _state.value = it }
        }
    }
    fun discardDraft() {
        val snapshot = _state.value
        val uid = snapshot.entitlement.uid ?: return
        val draft = snapshot.draft ?: return
        viewModelScope.launch {
            try { practice.discard(uid, draft.id) }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                _state.value = _state.value.copy(error = "Workout could not be discarded. Please retry.")
            }
        }
    }
    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { DrillLibraryViewModel(appContainer.drillRepository, appContainer.drillPracticeRepository, appContainer.drillAccessController) }
        }
    }
}

data class DrillWorkoutState(
    val draft: DrillPracticeDraft? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val confirmingReps: Boolean = false,
    val savedId: String? = null,
    val discarded: Boolean = false,
    val leftPaused: Boolean = false,
)

class DrillPracticeViewModel(
    private val drillId: String,
    private val sets: Int,
    private val reps: Int,
    private val restSeconds: Int,
    private val resumeId: String?,
    private val access: DrillAccessController,
    private val practice: DrillPracticeRepository,
    private val auth: AuthRepository,
    private val monotonicNow: () -> Long = { SystemClock.elapsedRealtime() },
) : ViewModel() {
    private val _state = MutableStateFlow(DrillWorkoutState())
    val state = _state.asStateFlow()
    private val lock = Mutex()
    private var ownerUid: String? = null
    private var foreground = true
    private var lastTick = monotonicNow()

    init {
        viewModelScope.launch { initialize() }
        viewModelScope.launch {
            while (isActive) {
                delay(250)
                lock.withLock {
                    if (ownerUid != null && auth.currentUid != ownerUid) {
                        foreground = false
                        _state.value = _state.value.copy(draft = _state.value.draft?.let(DrillPracticeEngine::pause), error = "Your account changed. Return to Train.")
                    } else {
                        val before = _state.value.draft?.stage
                        advanceClock()
                        if (before == DrillPracticeStage.Resting && _state.value.draft?.stage == DrillPracticeStage.Ready) checkpoint()
                    }
                }
            }
        }
    }

    private suspend fun initialize() = lock.withLock {
        try {
            val (uid, drill) = access.requireAccess(drillId)
            ownerUid = uid
            val previous = practice.draftFor(uid)
            val draft = if (resumeId != null) {
                check(previous?.id == resumeId && previous.drillId == drillId) { "Interrupted workout not found. Return to the drill library." }
                DrillPracticeEngine.recover(requireNotNull(previous))
            } else {
                check(previous == null) { "You have an unfinished workout. Resume or discard it in Drill library first." }
                DrillPracticeDraft(UUID.randomUUID().toString(), drill.id, drill.name, drill.skill,
                    DrillWorkoutConfig(sets, reps, restSeconds), System.currentTimeMillis())
            }
            lastTick = monotonicNow()
            _state.value = DrillWorkoutState(draft = if (foreground) draft else DrillPracticeEngine.pause(draft), loading = false)
            checkpoint()
            if (draft.stage == DrillPracticeStage.Complete && _state.value.error == null) saveFinished()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            _state.value = DrillWorkoutState(loading = false, error = failure.message ?: "Workout could not be opened.")
        }
    }

    private fun advanceClock() {
        val now = monotonicNow()
        val delta = (now - lastTick).coerceAtLeast(0)
        lastTick = now
        val state = _state.value
        val draft = state.draft ?: return
        if (foreground && !state.busy && state.error == null && !state.confirmingReps && state.savedId == null && !state.discarded)
            _state.value = state.copy(draft = DrillPracticeEngine.tick(draft, delta))
    }

    private suspend fun checkpoint() {
        val uid = ownerUid ?: return
        val draft = _state.value.draft ?: return
        if (_state.value.savedId != null || _state.value.discarded || _state.value.leftPaused) return
        try { practice.checkpoint(uid, draft) }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            _state.value = _state.value.copy(draft = DrillPracticeEngine.pause(draft), error = "Workout could not be saved. Your progress is kept here; tap Retry save.")
        }
    }

    private fun change(transform: (DrillPracticeDraft) -> DrillPracticeDraft) {
        viewModelScope.launch {
            lock.withLock {
                val uid = ownerUid ?: return@withLock
                if (auth.currentUid != uid || _state.value.busy || _state.value.error != null || _state.value.savedId != null) return@withLock
                advanceClock()
                val draft = _state.value.draft ?: return@withLock
                val updated = transform(draft)
                _state.value = _state.value.copy(draft = updated)
                lastTick = monotonicNow()
                checkpoint()
            }
        }
    }
    fun startSet() = change(DrillPracticeEngine::startSet)
    fun pause() = change(DrillPracticeEngine::pause)
    fun resume() = change(DrillPracticeEngine::resume)
    fun skipRest() = change(DrillPracticeEngine::skipRest)

    fun foregroundChanged(active: Boolean) {
        // Stop ticking immediately, before the checkpoint coroutine gets its turn.
        if (!active) {
            advanceClock()
            foreground = false
            viewModelScope.launch { lock.withLock {
                _state.value = _state.value.copy(draft = _state.value.draft?.let(DrillPracticeEngine::pause))
                checkpoint()
            } }
        } else { foreground = true; lastTick = monotonicNow() }
    }

    fun requestRepConfirmation() = change { draft ->
        if (draft.stage == DrillPracticeStage.Working) {
            _state.value = _state.value.copy(confirmingReps = true)
            DrillPracticeEngine.pause(draft)
        } else draft
    }
    fun cancelRepConfirmation() { _state.value = _state.value.copy(confirmingReps = false) }
    fun confirmSet(actualReps: Int) {
        viewModelScope.launch { lock.withLock {
            val current = _state.value
            val draft = current.draft ?: return@withLock
            if (!current.confirmingReps || current.busy || current.error != null || auth.currentUid != ownerUid) return@withLock
            val updated = DrillPracticeEngine.completeSet(DrillPracticeEngine.resume(draft), actualReps)
            _state.value = current.copy(draft = updated, confirmingReps = false)
            lastTick = monotonicNow()
            checkpoint()
            if (updated.stage == DrillPracticeStage.Complete) saveFinished()
        } }
    }

    fun finish() { viewModelScope.launch { lock.withLock {
        advanceClock()
        val draft = _state.value.draft ?: return@withLock
        if (_state.value.busy || _state.value.savedId != null || auth.currentUid != ownerUid) return@withLock
        _state.value = _state.value.copy(draft = draft.copy(stage = DrillPracticeStage.Complete), confirmingReps = false)
        saveFinished()
    } } }

    private suspend fun saveFinished() {
        val uid = ownerUid ?: return
        val draft = _state.value.draft ?: return
        _state.value = _state.value.copy(busy = true, error = null)
        try {
            if (draft.sets.isEmpty()) {
                practice.discard(uid, draft.id)
                _state.value = _state.value.copy(discarded = true, busy = false)
            } else {
                practice.finish(uid, draft.record(System.currentTimeMillis()))
                _state.value = _state.value.copy(savedId = draft.id, busy = false)
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            _state.value = _state.value.copy(busy = false, error = failure.message ?: "Workout could not be saved. Tap Retry save.")
        }
    }
    fun retrySave() { viewModelScope.launch { lock.withLock {
        if (auth.currentUid != ownerUid || _state.value.savedId != null || _state.value.discarded) return@withLock
        _state.value = _state.value.copy(error = null)
        if (_state.value.draft?.stage == DrillPracticeStage.Complete) saveFinished() else checkpoint()
    } } }
    fun leavePaused() { viewModelScope.launch { lock.withLock {
        if (_state.value.busy || _state.value.savedId != null || _state.value.discarded || auth.currentUid != ownerUid) return@withLock
        advanceClock()
        val draft = _state.value.draft ?: return@withLock
        _state.value = _state.value.copy(draft = DrillPracticeEngine.pause(draft), confirmingReps = false, busy = true, error = null)
        checkpoint()
        _state.value = _state.value.copy(busy = false, leftPaused = _state.value.error == null)
    } } }
    fun discard() { viewModelScope.launch { lock.withLock {
        val uid = ownerUid ?: return@withLock
        val draft = _state.value.draft ?: return@withLock
        _state.value = _state.value.copy(busy = true)
        try {
            practice.discard(uid, draft.id)
            _state.value = _state.value.copy(discarded = true, busy = false)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            _state.value = _state.value.copy(busy = false, error = "Workout could not be discarded. Please retry.")
        }
    } } }
    companion object {
        fun factory(drillId: String, sets: Int, reps: Int, rest: Int, resumeId: String?) = viewModelFactory {
            initializer { DrillPracticeViewModel(drillId, sets, reps, rest, resumeId,
                appContainer.drillAccessController, appContainer.drillPracticeRepository, appContainer.authRepository) }
        }
    }
}

/** A camera start grant lasts for this navigation entry, including configuration changes.
 * It is deliberately not persisted: a new entry or process restart checks access again. */
class DrillCameraGrantViewModel : ViewModel() {
    private val _uid = MutableStateFlow<String?>(null)
    val uid = _uid.asStateFlow()
    fun grant(uid: String) { _uid.value = uid }
    fun revoke() { _uid.value = null }
}
