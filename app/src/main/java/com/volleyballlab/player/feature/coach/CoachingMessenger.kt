package com.volleyballlab.player.feature.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.feature.coachmode.InitialsAvatar
import java.text.DateFormat
import java.util.Date

@Composable
internal fun CoachingUnreadBadge(count: Int, summary: Boolean = false) {
    if (count > 0) Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = CircleShape, modifier = Modifier.semantics { contentDescription = "$count unread messages" }) {
        Text((if (count > 99) "99+" else "$count") + if (summary) " Unread" else "", Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
}

@Composable
internal fun CoachingInboxRow(conversation: CoachingConversation, name: String, onClick: () -> Unit) {
    val unread = conversation.unread
    Row(Modifier.fillMaxWidth().background(if (unread > 0) MaterialTheme.colorScheme.primary.copy(alpha = .045f) else Color.Transparent)
        .clickable(onClick = onClick).heightIn(min = 92.dp).padding(vertical = 18.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box {
            InitialsAvatar(name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }, Modifier.clip(CircleShape))
            if (unread > 0) Box(Modifier.size(12.dp).align(Alignment.TopEnd).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(inboxTime(conversation.latest.at), style = MaterialTheme.typography.labelSmall,
                    color = if (unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text((if (conversation.latest.fromCoach) "You: " else "") + conversation.latest.text,
                    Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, fontWeight = if (unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (unread > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                CoachingUnreadBadge(unread)
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
}

/** A bounded training exchange, never an unrestricted direct-message composer. */
@Composable
internal fun RestrictedCoachingThread(
    question: CoachingQuestion, forCoach: Boolean, active: Boolean, busy: Boolean, error: String?,
    onSend: (String) -> Unit, onResolve: () -> Unit, onRead: () -> Unit,
    onWriting: (Boolean) -> Unit = {}, onInteract: () -> Unit = {},
) {
    var draft by rememberSaveable(question.id) { mutableStateOf("") }
    val lastAnswer = question.messages.lastOrNull { it.fromCoach == forCoach }?.at
    var submitted by rememberSaveable(question.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(lastAnswer) { if (submitted != null && lastAnswer != submitted) { draft = ""; submitted = null } }
    LaunchedEffect(question.messages, question.unread(forCoach)) { if (question.unread(forCoach) > 0) onRead() }
    val canSend = active && question.resolvedAt == null &&
        (if (forCoach) question.awaitingCoach else !question.awaitingCoach && question.followUpCount < 2)
    DisposableEffect(question.id, canSend, draft.isNotBlank()) {
        onWriting(canSend && draft.isNotBlank())
        onDispose { onWriting(false) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${if (question.sessionId == null) "Getting started" else "Session feedback"} · ${question.topic.label}",
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        question.messages.forEach { message ->
            val own = message.fromCoach == forCoach
            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
                Surface(shape = RoundedCornerShape(18.dp), color = if (own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(.9f)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(message.text, style = MaterialTheme.typography.bodyLarge)
                        Text(messageTime(message.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Text(when {
            !active -> "Connection ended · this conversation is read only."
            question.resolvedAt != null -> "Resolved · this question is read only."
            !forCoach && question.awaitingCoach -> "Waiting for your coach’s response."
            forCoach && !question.awaitingCoach -> "Waiting for the player, or mark this question resolved."
            !forCoach && question.followUpCount >= 2 -> "Both follow-ups used. Mark this question resolved before starting another."
            else -> "Training only · ${2 - question.followUpCount} follow-ups remaining."
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (canSend) {
            OutlinedTextField(draft, { draft = it.take(if (forCoach) 1000 else 300); onInteract() },
                label = { Text(if (forCoach) "Give training guidance…" else "Ask about your training…") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, shape = RoundedCornerShape(16.dp), enabled = !busy,
                isError = hasCoachingContact(draft), supportingText = {
                    Text(if (hasCoachingContact(draft)) "No links, phone numbers, email addresses or social handles."
                    else "${draft.length}/${if (forCoach) 1000 else 300} · text only")
                })
            Button(onClick = { submitted = lastAnswer ?: 0L; onSend(draft) },
                enabled = !busy && draft.isNotBlank() && !hasCoachingContact(draft),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (busy) "Sending…" else if (forCoach) "Send training response" else "Send follow-up")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (active && question.resolvedAt == null && !question.awaitingCoach) {
            TextButton(onClick = onResolve, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Mark question resolved") }
        }
    }
}

private fun messageTime(at: Long) = if (at <= 0L) "Sending…" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))

private fun inboxTime(at: Long): String {
    if (at <= 0L) return "Sending…"
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    val day = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    return (if (day == today) DateFormat.getTimeInstance(DateFormat.SHORT) else DateFormat.getDateInstance(DateFormat.SHORT)).format(Date(at))
}
