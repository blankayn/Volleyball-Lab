package com.volleyballlab.player.feature.coach

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.domain.repository.CoachRepository
import com.volleyballlab.player.domain.repository.DrillRepository
import com.volleyballlab.player.domain.repository.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

data class PlayerAssignmentState(
    val assignment: CoachAssignment? = null,
    val coachName: String = "Coach",
    val connected: Boolean = false,
    val sessions: List<SessionResult> = emptyList(),
    val submissions: List<SessionSubmission> = emptyList(),
    val notes: List<CoachNote> = emptyList(),
    val loaded: Boolean = false,
)

class PlayerAssignmentViewModel(
    private val assignmentId: String,
    private val coaches: CoachRepository,
    private val drills: DrillRepository,
    sessions: SessionRepository,
) : ViewModel() {
    val state = combine(
        coaches.assignmentsForMe, sessions.sessions, coaches.myCoach,
        coaches.mySubmissions, coaches.notesForMe,
    ) { assignments, recordings, link, submissions, notes ->
        val assignment = assignments.firstOrNull { it.id == assignmentId }
        val taskSessions = recordings.filter { it.assignmentId == assignmentId }.sortedByDescending { it.startedAtEpochMs }
        val ids = taskSessions.map { it.id }.toSet()
        PlayerAssignmentState(
            assignment = assignment,
            coachName = link?.takeIf { it.coachUid == assignment?.coachUid }?.coachName?.takeIf { it.isNotBlank() } ?: "Coach",
            connected = link?.isActive == true && link.coachUid == assignment?.coachUid,
            sessions = taskSessions,
            submissions = submissions.filter { it.sessionId in ids && it.coachUid == assignment?.coachUid },
            notes = notes.filter { it.sessionId in ids && it.coachUid == assignment?.coachUid },
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerAssignmentState())

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _sentSession = MutableStateFlow<String?>(null)
    val sentSession = _sentSession.asStateFlow()

    fun start(onReady: (Skill, String) -> Unit) {
        if (_busy.value) return
        val task = state.value.assignment ?: return
        if (!state.value.connected || !task.isOpen) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val drill = task.libraryDrillId?.let { drills.byId(it) }?.takeIf { it.skill == task.skill }
                    ?: drills.drillsFor(task.skill).let { candidates ->
                        candidates.firstOrNull { it.level == task.level } ?: candidates.firstOrNull()
                    }
                    ?: error("No camera drill is available for this skill.")
                coaches.updateAssignmentStatus(task.id, AssignmentStatus.InProgress)
                if (state.value.connected && state.value.assignment?.isOpen == true) onReady(task.skill, drill.id)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                _error.value = "Unable to start this task. Check your connection and try again."
            } finally { _busy.value = false }
        }
    }

    fun send(session: SessionResult) {
        if (_busy.value || _sentSession.value == session.id) return
        if (!state.value.connected || state.value.assignment?.skill != session.skill) return
        if (session.assignmentId != assignmentId || session.repCount == 0) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                coaches.submitAssignmentSession(assignmentId, session)
                _sentSession.value = session.id
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                _error.value = "Result not sent. Your recording is saved. Check your connection and coach membership, then retry."
            } finally { _busy.value = false }
        }
    }

    companion object {
        fun factory(assignmentId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { PlayerAssignmentViewModel(assignmentId, appContainer.coachRepository, appContainer.drillRepository, appContainer.sessionRepository) }
        }
    }
}

@Composable
fun PlayerAssignmentScreen(
    assignmentId: String,
    onBack: () -> Unit,
    onStart: (Skill, String) -> Unit,
    onOpenResult: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val model: PlayerAssignmentViewModel = viewModel(key = "assignment-$assignmentId", factory = PlayerAssignmentViewModel.factory(assignmentId))
    val state by model.state.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val task = state.assignment
    LazyColumn(modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to Coach") }
                Text("Assignment", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
        }
        when {
            !state.loaded -> item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            task == null -> item { TaskCard { Text("This assignment is no longer available."); TextButton(onBack) { Text("Back to Coach") } } }
            else -> {
                item {
                    TaskCard {
                        Text(task.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text("Assigned by ${state.coachName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${stringResource(task.skill.displayName)} · ${stringResource(task.level.displayName)}")
                        Text(stringResource(task.status.displayName), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        HorizontalDivider()
                        Text("${task.targetSets} sets × ${task.targetReps} reps", style = MaterialTheme.typography.titleMedium)
                        task.dueAtEpochMs?.let { Text("Due ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))}") }
                    }
                }
                if (task.summary.isNotBlank()) item { TaskCard { Text("Coach’s message", style = MaterialTheme.typography.titleMedium); Text(task.summary) } }
                item {
                    TaskCard {
                        Text("Instructions", style = MaterialTheme.typography.titleMedium)
                        Text(task.instructions.ifBlank { "Follow the assigned sets and repetitions for this skill." })
                    }
                }
                item {
                    TaskCard {
                        Text("Record and review", style = MaterialTheme.typography.titleMedium)
                        Text("Record the task with your full body in frame. Review your form score and feedback, then send the result to your coach.")
                        state.sessions.firstOrNull()?.let { saved ->
                            OutlinedButton(onClick = { onOpenResult(saved.id) }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (state.submissions.any { it.sessionId == saved.id }) "View submitted result" else "Review saved result")
                            }
                        }
                        if (task.isOpen) {
                            Button(enabled = state.connected && !busy, onClick = { model.start(onStart) }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (busy) "Preparing camera…" else if (state.sessions.isEmpty()) "Start camera task" else "Record again")
                            }
                        }
                        if (!state.connected) Text("Connect with this coach to record and submit this task.", style = MaterialTheme.typography.bodySmall)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                if (state.notes.isNotEmpty()) item {
                    TaskCard {
                        Text("Coach feedback", style = MaterialTheme.typography.titleMedium)
                        state.notes.forEach { Text(it.text) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun AssignmentResultActions(assignmentId: String, session: SessionResult, modifier: Modifier = Modifier) {
    val model: PlayerAssignmentViewModel = viewModel(key = "assignment-result-$assignmentId", factory = PlayerAssignmentViewModel.factory(assignmentId))
    val state by model.state.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val sentSession by model.sentSession.collectAsStateWithLifecycle()
    val receipt = state.submissions.firstOrNull { it.sessionId == session.id }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(state.assignment?.title ?: "Coach assignment", style = MaterialTheme.typography.titleMedium)
            state.assignment?.let { Text("${session.repCount} detected reps · Target: ${it.targetSets} sets × ${it.targetReps} reps") }
            when {
                receipt != null || sentSession == session.id -> Text(
                    if (receipt?.reviewedAtEpochMs != null) "Reviewed by your coach. See feedback in the assignment." else "Sent to your coach. Awaiting review.",
                    color = MaterialTheme.colorScheme.primary,
                )
                else -> {
                    Text(if (session.videoPath != null) "Send your form summary, video, skeleton, and error markers privately to your coach."
                        else "Send your measured form summary to your coach. This session has no video.", style = MaterialTheme.typography.bodySmall)
                    Button(
                        enabled = state.loaded && state.connected && session.assignmentId == assignmentId && session.repCount > 0 && !busy,
                        onClick = { model.send(session) }, modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (busy) "Sending…" else "Send result to coach") }
                    if (session.repCount == 0) Text("No reps were detected. Record the task again before submitting.", style = MaterialTheme.typography.bodySmall)
                    if (state.loaded && !state.connected) Text("This coach connection is no longer active.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun TaskCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
