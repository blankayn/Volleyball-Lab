package com.volleyballlab.player.feature.coach

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.QuestionAnswer
import com.volleyballlab.player.feature.coachmode.coachWorkspaceBackground
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.data.coach.CoachingHubRepository
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.ui.component.StudentCard
import com.volleyballlab.player.ui.component.studentBackground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class CoachingActionsModel(val hub: CoachingHubRepository) : ViewModel() {
    val busy = MutableStateFlow<Set<String>>(emptySet())
    val errors = MutableStateFlow<Map<String, String>>(emptyMap())
    fun perform(key: String, action: suspend () -> Unit) {
        if (key in busy.value) return
        busy.update { it + key }; errors.update { it - key }
        viewModelScope.launch {
            try { action() }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                errors.update { it + (key to "Could not save. Check your connection and try again. Your text is still here.") }
            } finally { busy.update { it - key } }
        }
    }
    companion object {
        val Factory = viewModelFactory { initializer { CoachingActionsModel(appContainer.coachingHubRepository) } }
    }
}

@Composable
fun MyCoachScreen(
    onBack: () -> Unit,
    onBrowse: () -> Unit,
    onOpenAssignment: (String) -> Unit,
    contentPadding: PaddingValues,
    onOpenProfile: (String) -> Unit = {},
    section: String? = null,
    onOpenSection: (String) -> Unit = {},
) {
    val model: CoachLinkViewModel = viewModel(factory = CoachLinkViewModel.Factory)
    val state by model.state.collectAsStateWithLifecycle()
    val sending by model.sending.collectAsStateWithLifecycle()
    val submitError by model.submitError.collectAsStateWithLifecycle()
    val leaving by model.leaving.collectAsStateWithLifecycle()
    val leaveError by model.leaveError.collectAsStateWithLifecycle()
    val actions: CoachingActionsModel = viewModel(factory = CoachingActionsModel.Factory)
    val questions by actions.hub.playerQuestions.collectAsStateWithLifecycle(emptyList())
    val reviews by actions.hub.myReviews.collectAsStateWithLifecycle(emptyList())
    val busy by actions.busy.collectAsStateWithLifecycle()
    val errors by actions.errors.collectAsStateWithLifecycle()
    val selected = section?.let { value -> CoachWorkspaceTab.entries.firstOrNull { it.name == value } ?: CoachWorkspaceTab.Overview }
    var feedbackFilter by rememberSaveable { mutableIntStateOf(0) }
    var showLeave by rememberSaveable { mutableStateOf(false) }
    val link = state.link
    val active = link?.isActive == true
    val assignments = state.assignments.filter { it.coachUid == link?.coachUid }
    val openAssignments = assignments.filter { it.isOpen }
    val currentReviewed = state.submissions.count { it.coachUid == link?.coachUid && !it.isPending }
    val summary = if (active) rememberCoachSummary(link.coachUid).value else CoachPublicSummary()
    val coach = link?.let { connection -> state.directory.firstOrNull { it.coachUid == connection.coachUid }
        ?: CoachPreview(connection.coachUid, CoachProfile(name = connection.coachName, teamName = connection.teamName), 0) }
    LaunchedEffect(active, leaving, leaveError) { if (!active && !leaving && leaveError == null) showLeave = false }

    LazyColumn(Modifier.fillMaxSize().coachPageBackground(), contentPadding = hubPadding(contentPadding), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item(key = "header") { Column { HubHeader(selected?.label ?: "My coach", onBack); HubSync(actions.hub) } }
        if (!state.loaded) item { CircularProgressIndicator() }
        else {
            if (active && coach != null) {
                item(key = "identity") { com.volleyballlab.player.ui.component.EntranceAnimation(index = 0) {
                    CoachIdentityHero(coach, summary, connected = true, onViewProfile = { onOpenProfile(coach.coachUid) })
                } }
                item(key = "metrics") { CoachMetrics(summary) }
            }
            when (selected) {
                null -> item(key = "workspace-sections") { MyCoachHubSections(state, onManageConnection = { showLeave = true }) { onOpenSection(it.name) } }
                CoachWorkspaceTab.Overview -> {
                    if (active) {
                        item(key = "intro") {
                            CoachSectionCard(tint = true) {
                                CoachEyebrow("Your next step")
                                Text("Get your training direction", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                                Text("Ask what to practise first, what to focus on, or how to record your first session.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                CoachingQuestionCard(link.coachUid, null, questions.firstOrNull { it.coachUid == link.coachUid && it.sessionId == null }, true, actions, busy, errors)
                            }
                        }
                        item(key = "overview-counts") {
                            com.volleyballlab.player.ui.component.ResponsivePair(
                                first = { CoachShortcut(openAssignments.size.toString(), "Your training plan", "Assignments to work on", { onOpenSection(CoachWorkspaceTab.Training.name) }, it) },
                                second = { CoachShortcut(currentReviewed.toString(), "Coach feedback", "Reviewed sessions", { onOpenSection(CoachWorkspaceTab.Feedback.name) }, it) })
                        }
                        state.notes.firstOrNull { it.coachUid == link.coachUid && it.sessionId == null }?.let { note -> item(key = "general-note") {
                            CoachSectionCard {
                                CoachEyebrow("From your coach")
                                Text(note.text, style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp)
                                Text(dateLabel(note.createdAtEpochMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } }
                    } else item(key = "no-connection") {
                        CoachSectionCard {
                            CoachEyebrow(if (link?.isPending == true) "Request sent" else "Your next chapter")
                            Text(if (link?.isPending == true) "Your coach is reviewing your request" else "Find your training connection", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            Text(if (link?.isPending == true) "You’ll see your coach workspace here when they accept." else "Explore coach profiles and choose the guidance that fits your goals.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (link == null) Button(onClick = onBrowse, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Find a coach") }
                            if (state.submissions.isNotEmpty()) TextButton(onClick = { onOpenSection(CoachWorkspaceTab.Feedback.name) }) { Text("View previous coaching feedback") }
                        }
                    }
                }
                CoachWorkspaceTab.Training -> {
                    item(key = "training-heading") { CoachSectionTitle("Your training plan", "Clear next steps from your coach", assignments.size) }
                    if (active) {
                        state.latestSession?.let { latest -> item(key = "latest-session") {
                            CoachSectionCard(tint = true) {
                                CoachEyebrow(if (state.latestIsSubmitted) "Shared with your coach" else "Ready for feedback")
                                Text("${latest.skill.name} · ${latest.repCount} reps", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                Text(dateLabel(latest.startedAtEpochMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (state.latestIsSubmitted) Text("Your coach can review this session in their queue.", style = MaterialTheme.typography.bodyMedium)
                                else {
                                    Text(if (latest.videoPath == null) "Share this session’s form summary with your coach." else "Share the recording and form feedback from this session.", style = MaterialTheme.typography.bodyMedium)
                                    submitError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                    Button(onClick = { model.submit(latest.id) }, enabled = !sending, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                        if (sending) { CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
                                        Text(if (sending) "Sharing session…" else "Send session for review")
                                    }
                                }
                            }
                        } }
                    }
                    if (assignments.isEmpty()) item(key = "no-assignments") {
                        CoachEmptyState("Your plan starts here", if (active) "Your coach’s assigned drills will appear here. You can ask for a starting drill from Overview." else "Connect with a coach to receive a training plan.")
                    }
                    items(assignments.sortedWith(compareBy<CoachAssignment> { !it.isOpen }.thenBy { it.dueAtEpochMs ?: Long.MAX_VALUE }), key = { "assignment-${it.id}" }) { assignment ->
                        CoachAssignmentTile(assignment) { onOpenAssignment(assignment.id) }
                    }
                }
                CoachWorkspaceTab.Feedback -> {
                    item(key = "feedback-heading") { CoachSectionTitle("Your progress, with context", "Session feedback, coaching questions, and your reviews", state.submissions.size) }
                    item(key = "feedback-filters") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("All sessions", "Reviewed", "Awaiting feedback").forEachIndexed { index, label ->
                                FilterChip(selected = feedbackFilter == index, onClick = { feedbackFilter = index }, label = { Text(label) })
                            }
                        }
                    }
                    val filtered = state.submissions.filter { feedbackFilter == 0 || (feedbackFilter == 1 && !it.isPending) || (feedbackFilter == 2 && it.isPending) }.sortedByDescending { it.submittedAtEpochMs }
                    if (filtered.isEmpty()) item(key = "empty-history") {
                        CoachEmptyState(if (state.submissions.isEmpty()) "Your story is just starting" else "Nothing in this view yet", if (state.submissions.isEmpty()) "Share your first training session to build a history of feedback and progress." else "Try another filter to see your coaching sessions.")
                    }
                    items(filtered, key = { "feedback-${it.coachUid}-${it.sessionId}" }) { submission ->
                        val session = state.sessions.firstOrNull { it.id == submission.sessionId }
                        val feedback = state.notes.filter { it.coachUid == submission.coachUid && it.sessionId == submission.sessionId }.sortedBy { it.createdAtEpochMs }
                        val review = reviews.firstOrNull { it.coachUid == submission.coachUid && it.sessionId == submission.sessionId }
                        val connected = active && link.coachUid == submission.coachUid
                        CoachSectionCard {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    CoachEyebrow(if (submission.isPending) "Awaiting feedback" else "Reviewed session")
                                    Text(session?.skill?.name ?: "Shared session", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Text(dateLabel(submission.submittedAtEpochMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                session?.formScore()?.let { score ->
                                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .07f)) {
                                        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(score.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                            Text("FORM / 100", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                                        }
                                    }
                                }
                            }
                            if (feedback.isEmpty()) Text(if (submission.isPending) "Your session is with your coach. Their guidance will appear here." else "This session has been reviewed.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            feedback.forEach { note ->
                                val rule = MaterialTheme.colorScheme.primary.copy(alpha = .5f)
                                Column(Modifier.drawBehind { drawLine(rule, Offset.Zero, Offset(0f, size.height), 3.dp.toPx()) }.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CoachEyebrow("Coach feedback")
                                    Text(note.text, style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp)
                                    Text(dateLabel(note.createdAtEpochMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (!submission.isPending) {
                                CoachingQuestionCard(submission.coachUid, submission.sessionId, questions.firstOrNull { it.coachUid == submission.coachUid && it.sessionId == submission.sessionId }, connected, actions, busy, errors)
                                ReviewEditor(submission, review, connected, actions, busy, errors)
                            }
                        }
                    }
                    items(state.notes.filter { it.sessionId == null }, key = { "general-${it.id}" }) { note ->
                        CoachSectionCard {
                            CoachEyebrow("Coaching guidance")
                            Text(note.text, style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp)
                            Text(dateLabel(note.createdAtEpochMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(questions.filter { it.sessionId == null && (!active || it.coachUid != link.coachUid) }, key = { "intro-${it.id}" }) { question ->
                        CoachSectionCard {
                            CoachEyebrow("Earlier coaching guidance")
                            CoachingQuestionCard(question.coachUid, null, question, false, actions, busy, errors)
                        }
                    }
                }
            }
        }
        item(key = "bottom-space") { Spacer(Modifier.height(8.dp)) }
    }
    if (showLeave) AlertDialog(
        onDismissRequest = { if (!leaving) showLeave = false },
        title = { Text("Leave this coach’s team?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your previous feedback stays available. To send new sessions or questions, you’ll need a new approved connection.")
            leaveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = model::leave, enabled = !leaving) { Text(if (leaving) "Leaving…" else "Leave team", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { showLeave = false }, enabled = !leaving) { Text("Keep connection") } },
    )
}

@Composable
private fun CoachShortcut(value: String, label: String, hint: String, onClick: () -> Unit, modifier: Modifier) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))) {
        Column(Modifier.padding(18.dp).heightIn(min = 112.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(value.padStart(2, '0'), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CoachAssignmentTile(assignment: CoachAssignment, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                CoachEyebrow(assignment.skill.name)
                Text(if (assignment.status == AssignmentStatus.Completed) "Completed" else if (assignment.status == AssignmentStatus.InProgress) "In progress" else "To practise", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(assignment.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (assignment.summary.isNotBlank()) Text(assignment.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${assignment.targetSets} sets × ${assignment.targetReps} reps", style = MaterialTheme.typography.labelLarge)
                    assignment.dueAtEpochMs?.let { Text("Due ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}


@Composable
private fun CoachingQuestionCard(coachUid: String, sessionId: String?, question: CoachingQuestion?, connected: Boolean,
    actions: CoachingActionsModel, busy: Set<String>, errors: Map<String, String>) {
    val key = "question-$coachUid-${sessionId ?: "intro"}"
    var open by rememberSaveable(key) { mutableStateOf(false) }
    var answerOpen by rememberSaveable(key) { mutableStateOf(false) }
    var text by rememberSaveable(key) { mutableStateOf("") }
    val topics = CoachingTopic.entries.filter { it.introductory == (sessionId == null) }
    var topic by rememberSaveable(key) { mutableStateOf(topics.first()) }
    val allQuestions by actions.hub.playerQuestions.collectAsStateWithLifecycle(emptyList())
    val current = allQuestions.firstOrNull { it.coachUid == coachUid && it.resolvedAt == null } ?: question
    var startNew by rememberSaveable(key) { mutableStateOf(false) }
    LaunchedEffect(question?.id) { startNew = false; open = false }
    if (current != null && !startNew) {
        TextButton(onClick = { answerOpen = !answerOpen }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (answerOpen) "Hide training conversation" else "Open training conversation")
            Spacer(Modifier.width(8.dp)); CoachingUnreadBadge(current.unread(false))
        }
        if (answerOpen) RestrictedCoachingThread(current, false, connected, current.id in busy, errors[current.id],
            onSend = { value -> actions.perform(current.id) { actions.hub.followUp(current, value) } },
            onResolve = { actions.perform(current.id) { actions.hub.resolve(current) } },
            onRead = { actions.perform("read-${current.id}") { actions.hub.markRead(current, false) } })
        if (connected && current.resolvedAt != null) OutlinedButton(onClick = { text = ""; startNew = true; open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Start a new training question")
        }
    } else if (connected) {
        if (!open) OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(14.dp)) {
            Text(if (sessionId == null) "Ask about getting started" else "Ask a coaching question", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(19.dp))
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CoachEyebrow("Ask your coach")
            Text("Choose a training topic", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                topics.forEach { option -> FilterChip(selected = option == topic, onClick = { topic = option }, label = { Text(option.label) }, enabled = key !in busy) }
            }
            OutlinedTextField(text, { text = it.take(300) }, label = { Text("What would you like to understand?") },
                minLines = 3, shape = RoundedCornerShape(16.dp), isError = hasCoachingContact(text),
                supportingText = { Text(if (hasCoachingContact(text)) "No links or contact details." else "${text.length}/300 · one open training question at a time") },
                enabled = key !in busy, modifier = Modifier.fillMaxWidth())
            errors[key]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = { actions.perform(key) { actions.hub.ask(coachUid, sessionId, topic, text) } },
                enabled = text.isNotBlank() && !hasCoachingContact(text) && key !in busy,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                if (key in busy) { CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
                Text(if (key in busy) "Sending question…" else "Send coaching question")
            }
            TextButton(onClick = { open = false }, enabled = key !in busy, modifier = Modifier.fillMaxWidth()) { Text("Keep for later") }
        }
    }
}

@Composable
private fun ReviewEditor(submission: SessionSubmission, review: CoachReview?, connected: Boolean,
    actions: CoachingActionsModel, busy: Set<String>, errors: Map<String, String>) {
    if (!connected && review == null) return
    val key = "review-${submission.coachUid}-${submission.sessionId}"
    var editing by rememberSaveable(key) { mutableStateOf(false) }
    var submitted by rememberSaveable(key) { mutableStateOf(false) }
    var rating by rememberSaveable(key, review?.rating) { mutableIntStateOf(review?.rating ?: 0) }
    var text by rememberSaveable(key, review?.text) { mutableStateOf(review?.text.orEmpty()) }
    LaunchedEffect(review?.rating, review?.text, key in busy, errors[key]) {
        if (submitted && key !in busy) {
            if (errors[key] != null) submitted = false
            else if (review != null && review.rating == rating && review.text == text.trim()) {
                editing = false
                submitted = false
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
    if (!editing) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (review == null) "How was the guidance?" else "Your coaching review", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (review != null) Text("★".repeat(review.rating.coerceIn(1, 5)), color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
            else Text("Rate your coaching experience", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { editing = true }) { Text(if (review == null) "Rate session" else "Edit review") }
    } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CoachEyebrow("Your experience")
        Text("Rate the coaching guidance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            (1..5).forEach { star -> IconButton(onClick = { rating = star; submitted = false }, enabled = key !in busy, modifier = Modifier.size(48.dp)) {
                Icon(if (star <= rating) Icons.Rounded.Star else Icons.Rounded.StarBorder, contentDescription = "Select $star out of 5 stars", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            } }
        }
        OutlinedTextField(text, { text = it.take(500); submitted = false }, label = { Text("What helped you? (optional)") },
            minLines = 3, shape = RoundedCornerShape(16.dp), supportingText = { Text("${text.length}/500") }, enabled = key !in busy, modifier = Modifier.fillMaxWidth())
        Text("Your rating and review are visible on this coach’s profile as a verified player.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        errors[key]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = { submitted = true; actions.perform(key) { actions.hub.rate(submission.coachUid, submission.sessionId, rating, text) } },
            enabled = rating in 1..5 && key !in busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
            Text(if (key in busy) "Saving review…" else "Save review")
        }
        TextButton(onClick = { editing = false; submitted = false; rating = review?.rating ?: 0; text = review?.text.orEmpty() }, enabled = key !in busy, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}


@Composable
fun CoachQuestionsScreen(onBack: () -> Unit, contentPadding: PaddingValues) {
    val actions: CoachingActionsModel = viewModel(factory = CoachingActionsModel.Factory)
    val questions by actions.hub.coachQuestions.collectAsStateWithLifecycle(emptyList())
    val busy by actions.busy.collectAsStateWithLifecycle()
    val errors by actions.errors.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val graph = remember(context) { (context.applicationContext as com.volleyballlab.player.VolleyballLabApplication).container }
    val roster by graph.coachRepository.roster.collectAsStateWithLifecycle(emptyList())
    val syncError by actions.hub.error.collectAsStateWithLifecycle(null)
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(selected != null) { selected = null }
    val inbox = coachingInbox(questions)
    val conversation = inbox.firstOrNull { it.playerUid == selected }
    val playerName = roster.firstOrNull { it.playerUid == selected }?.playerName?.ifBlank { "Player" } ?: "Player"
    CoachQuestionsPage(onBack = { if (selected != null) selected = null else onBack() }, contentPadding = contentPadding,
        title = if (selected == null) "Inbox" else playerName, unread = if (selected == null) inbox.sumOf { it.unread } else 0) {
        syncError?.let { message -> item(key = "sync-error") {
            Column { Text(message, color = MaterialTheme.colorScheme.error); TextButton(onClick = actions.hub::retry) { Text("Retry coaching sync") } }
        } }
        if (selected == null) {
            if (inbox.isEmpty()) item { Text("Player training questions will appear here after a player starts a conversation.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(inbox, key = { it.playerUid }) { entry ->
                CoachingInboxRow(entry, roster.firstOrNull { it.playerUid == entry.playerUid }?.playerName?.ifBlank { "Player" } ?: "Player") { selected = entry.playerUid }
            }
        } else {
            item { Text("Training questions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            items(conversation?.questions?.sortedByDescending { it.createdAt }.orEmpty(), key = { it.id }) { question ->
                val active = roster.any { it.playerUid == question.playerUid && it.isActive }
                RestrictedCoachingThread(question, true, active, question.id in busy, errors[question.id],
                    onSend = { text -> actions.perform(question.id) { actions.hub.respond(question, text) } },
                    onResolve = { actions.perform(question.id) { actions.hub.resolve(question) } },
                    onRead = { actions.perform("read-${question.id}") { actions.hub.markRead(question, true) } },
                    onWriting = { graph.coachPresenceReporter.writing(question.id, it) },
                    onInteract = graph.coachPresenceReporter::interact)
                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
@Composable private fun HubSync(hub: CoachingHubRepository) {
    val error by hub.error.collectAsStateWithLifecycle(null)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = hub::retry) { Text("Retry coaching sync") } }
}
@Composable private fun HubHeader(title: String, onBack: () -> Unit, eyebrow: String = "Your training space") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", Modifier.size(21.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            CoachEyebrow(eyebrow)
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}
@Composable private fun HubCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    StudentCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        content()
    } }
}
private fun hubPadding(padding: PaddingValues) = PaddingValues(20.dp, padding.calculateTopPadding() + 12.dp, 20.dp, padding.calculateBottomPadding() + 24.dp)
private fun dateLabel(time: Long): String = if (time == 0L) "Syncing…" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))
