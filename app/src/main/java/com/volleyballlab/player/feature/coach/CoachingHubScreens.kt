package com.volleyballlab.player.feature.coach

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
fun MyCoachScreen(onBack: () -> Unit, onBrowse: () -> Unit, onOpenAssignment: (String) -> Unit, contentPadding: PaddingValues) {
    val model: CoachLinkViewModel = viewModel(factory = CoachLinkViewModel.Factory)
    val state by model.state.collectAsStateWithLifecycle()
    val sending by model.sending.collectAsStateWithLifecycle()
    val actions: CoachingActionsModel = viewModel(factory = CoachingActionsModel.Factory)
    val questions by actions.hub.playerQuestions.collectAsStateWithLifecycle(emptyList())
    val reviews by actions.hub.myReviews.collectAsStateWithLifecycle(emptyList())
    val busy by actions.busy.collectAsStateWithLifecycle()
    val errors by actions.errors.collectAsStateWithLifecycle()
    val link = state.link
    LazyColumn(Modifier.fillMaxSize().studentBackground(), contentPadding = hubPadding(contentPadding), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { HubHeader("My coach", onBack) }
        item { HubSync(actions.hub) }
        if (!state.loaded) item { CircularProgressIndicator() }
        else {
            if (link?.isActive == true) {
                item { CoachPublicDetails(link.coachUid) }
                item { LinkedCard(state, model::submit, model::leave, onOpenAssignment, sending) }
                item {
                    HubCard("Getting started with your coach") {
                        Text("Ask what to practice first, what to focus on, or how to record your first session.", style = MaterialTheme.typography.bodyMedium)
                        CoachingQuestionCard(link.coachUid, null, questions.firstOrNull { it.coachUid == link.coachUid && it.sessionId == null }, true, actions, busy, errors)
                    }
                }
            } else item {
                HubCard(if (link?.isPending == true) "Waiting for approval" else "Your coach workspace") {
                    Text(if (link?.isPending == true) "Your coach will review your connection request." else "Connect with a coach to start a new training chapter. Your previous feedback stays below.")
                    if (link == null) TextButton(onClick = onBrowse) { Text("Find a coach") }
                }
            }
            item { Text("Coaching feedback history", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            if (state.submissions.isEmpty()) item {
                Text("Your shared sessions, coach feedback, and ratings will appear here after your first submission.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.submissions.sortedByDescending { it.submittedAtEpochMs }, key = { "${it.coachUid}_${it.sessionId}" }) { submission ->
                val session = state.sessions.firstOrNull { it.id == submission.sessionId }
                val feedback = state.notes.filter { it.coachUid == submission.coachUid && it.sessionId == submission.sessionId }.sortedBy { it.createdAtEpochMs }
                val review = reviews.firstOrNull { it.coachUid == submission.coachUid && it.sessionId == submission.sessionId }
                val connected = link?.isActive == true && link.coachUid == submission.coachUid
                HubCard(session?.skill?.name ?: "Shared session") {
                    Text(dateLabel(submission.submittedAtEpochMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    session?.formScore()?.let { Text("Session form: $it / 100", style = MaterialTheme.typography.titleSmall) }
                    Text(if (submission.isPending) "Awaiting coach feedback" else "Reviewed by your coach", style = MaterialTheme.typography.labelLarge)
                    feedback.forEach { note ->
                        Text(note.text, style = MaterialTheme.typography.bodyLarge)
                        Text(dateLabel(note.createdAtEpochMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!submission.isPending) {
                        HorizontalDivider()
                        CoachingQuestionCard(submission.coachUid, submission.sessionId,
                            questions.firstOrNull { it.coachUid == submission.coachUid && it.sessionId == submission.sessionId }, connected, actions, busy, errors)
                        ReviewEditor(submission, review, connected, actions, busy, errors)
                    }
                }
            }
            items(questions.filter { it.sessionId == null && (link?.isActive != true || it.coachUid != link.coachUid) }, key = { "intro-${it.id}" }) { question ->
                HubCard("Previous coach introduction") {
                    CoachingQuestionCard(question.coachUid, null, question, false, actions, busy, errors)
                }
            }
        }
    }
}

@Composable
fun CoachPublicDetails(coachUid: String) {
    val actions: CoachingActionsModel = viewModel(factory = CoachingActionsModel.Factory)
    val summaryFlow = remember(coachUid) { actions.hub.summary(coachUid) }
    val summary by summaryFlow.collectAsStateWithLifecycle(CoachPublicSummary())
    HubCard("Coach at a glance") {
        Text(summary.availability?.label ?: "Availability not set", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (summary.availability != null) Text("Availability is set by the coach.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(summary.typicalResponseMs?.let { "Typical response: ${coachingResponseLabel(it)}" } ?: "Response time: building history (${summary.responseTimesMs.size}/3 replies)")
        Text("Based on the median of up to 20 recent answered questions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(summary.averageRating?.let { "★ ${String.format(Locale.getDefault(), "%.1f", it)} / 5 · ${summary.reviews.size} verified session reviews" } ?: "No session reviews yet",
            style = MaterialTheme.typography.titleSmall)
        summary.reviews.take(5).forEach { review ->
            HorizontalDivider()
            Text("${"★".repeat(review.rating.coerceIn(1, 5))} · Verified player · ${dateLabel(review.createdAt)}", style = MaterialTheme.typography.labelMedium)
            if (review.text.isNotBlank()) Text(review.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CoachingQuestionCard(coachUid: String, sessionId: String?, question: CoachingQuestion?, connected: Boolean,
    actions: CoachingActionsModel, busy: Set<String>, errors: Map<String, String>) {
    val key = "question-$coachUid-${sessionId ?: "intro"}"
    var open by rememberSaveable(key) { mutableStateOf(false) }
    var text by rememberSaveable(key) { mutableStateOf("") }
    val topics = CoachingTopic.entries.filter { it.introductory == (sessionId == null) }
    var topic by rememberSaveable(key) { mutableStateOf(topics.first()) }
    if (question != null) {
        Text("Coaching question · ${question.topic.label}", style = MaterialTheme.typography.titleSmall)
        Text(question.text)
        Text(dateLabel(question.createdAt), style = MaterialTheme.typography.labelSmall)
        question.answer?.let {
            Text("Coach response", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(it)
            question.answeredAt?.let { time -> Text(dateLabel(time), style = MaterialTheme.typography.labelSmall) }
        } ?: Text("Waiting for your coach’s response", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (connected) {
        if (!open) OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (sessionId == null) "Ask about getting started" else "Ask a coaching question")
        } else {
            Text("One question and one coach response for this ${if (sessionId == null) "introduction" else "session"}.", style = MaterialTheme.typography.bodySmall)
            topics.forEach { option -> FilterChip(selected = option == topic, onClick = { topic = option }, label = { Text(option.label) }) }
            OutlinedTextField(text, { text = it.take(300) }, label = { Text("Your training question") }, isError = hasCoachingLink(text), supportingText = { Text(if (hasCoachingLink(text)) "Text only; links are not supported." else "${text.length}/300") }, enabled = key !in busy, modifier = Modifier.fillMaxWidth())
            errors[key]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { actions.perform(key) { actions.hub.ask(coachUid, sessionId, topic, text) } },
                enabled = text.isNotBlank() && !hasCoachingLink(text) && key !in busy, modifier = Modifier.fillMaxWidth()) { Text(if (key in busy) "Sending…" else "Send coaching question") }
        }
    }
}

@Composable
private fun ReviewEditor(submission: SessionSubmission, review: CoachReview?, connected: Boolean,
    actions: CoachingActionsModel, busy: Set<String>, errors: Map<String, String>) {
    if (!connected && review == null) return
    val key = "review-${submission.coachUid}-${submission.sessionId}"
    var editing by rememberSaveable(key) { mutableStateOf(false) }
    var rating by rememberSaveable(key, review?.rating) { mutableIntStateOf(review?.rating ?: 0) }
    var text by rememberSaveable(key, review?.text) { mutableStateOf(review?.text.orEmpty()) }
    HorizontalDivider()
    Text(review?.let { "Your coach rating: ${it.rating} / 5" } ?: "Rate this coaching session", style = MaterialTheme.typography.titleSmall)
    if (!editing) TextButton(onClick = { editing = true }) { Text(if (review == null) "Leave a rating and review" else "Edit your review") }
    else {
        Row { (1..5).forEach { star -> IconButton(onClick = { rating = star }, enabled = key !in busy) {
            Icon(if (star <= rating) Icons.Rounded.Star else Icons.Rounded.StarBorder, contentDescription = "$star out of 5 stars", tint = MaterialTheme.colorScheme.primary)
        } } }
        OutlinedTextField(text, { text = it.take(500) }, label = { Text("Review (optional)") }, supportingText = { Text("${text.length}/500") }, enabled = key !in busy, modifier = Modifier.fillMaxWidth())
        Text("Your rating and review will be visible on this coach’s profile as a verified player.", style = MaterialTheme.typography.bodySmall)
        errors[key]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { actions.perform(key) { actions.hub.rate(submission.coachUid, submission.sessionId, rating, text) } }, enabled = rating in 1..5 && key !in busy) { Text(if (key in busy) "Saving…" else "Save review") }
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
    val user by graph.authRepository.currentUser.collectAsStateWithLifecycle(null)
    LazyColumn(Modifier.fillMaxSize().studentBackground(), contentPadding = hubPadding(contentPadding), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { HubHeader("Player questions", onBack) }
        item { HubSync(actions.hub) }
        user?.uid?.let { uid -> item {
            val summaryFlow = remember(uid) { actions.hub.summary(uid) }
            val summary by summaryFlow.collectAsStateWithLifecycle(CoachPublicSummary())
            HubCard("Your availability") {
                Text("Shown on your coach profile. Set this when your capacity changes.", style = MaterialTheme.typography.bodySmall)
                CoachAvailability.entries.forEach { status -> FilterChip(selected = status == summary.availability,
                    onClick = { actions.perform("availability") { actions.hub.setAvailability(status) } }, enabled = "availability" !in busy, label = { Text(status.label) }) }
                errors["availability"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        } }
        if (questions.isEmpty()) item { Text("New-player introductions and questions about reviewed sessions will appear here.") }
        items(questions.sortedWith(compareBy<CoachingQuestion> { it.answer != null }.thenBy { it.createdAt }), key = { it.id }) { question ->
            val active = roster.any { it.playerUid == question.playerUid && it.isActive }
            HubCard(roster.firstOrNull { it.playerUid == question.playerUid }?.playerName?.ifBlank { "Player" } ?: "Player") {
                Text("${question.topic.label} · ${if (question.sessionId == null) "Getting started" else "Session question"}", style = MaterialTheme.typography.labelMedium)
                Text(question.text, style = MaterialTheme.typography.bodyLarge)
                Text(dateLabel(question.createdAt), style = MaterialTheme.typography.labelSmall)
                if (question.answer != null) { Text("Your response", style = MaterialTheme.typography.titleSmall); Text(question.answer) }
                else if (active) {
                    var reply by rememberSaveable(question.id) { mutableStateOf("") }
                    OutlinedTextField(reply, { reply = it.take(1000) }, label = { Text("Training guidance") }, isError = hasCoachingLink(reply), supportingText = { if (hasCoachingLink(reply)) Text("Text only; links are not supported.") }, enabled = question.id !in busy, modifier = Modifier.fillMaxWidth())
                    errors[question.id]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = { actions.perform(question.id) { actions.hub.answer(question, reply) } }, enabled = reply.isNotBlank() && !hasCoachingLink(reply) && question.id !in busy) { Text(if (question.id in busy) "Sending…" else "Send coach response") }
                } else Text("This player is no longer on your active roster.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun HubSync(hub: CoachingHubRepository) {
    val error by hub.error.collectAsStateWithLifecycle(null)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = hub::retry) { Text("Retry coaching sync") } }
}
@Composable private fun HubHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
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
