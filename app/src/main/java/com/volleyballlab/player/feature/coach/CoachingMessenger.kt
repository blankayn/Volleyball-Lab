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
            com.volleyballlab.player.feature.profile.ProfileAvatar(conversation.playerUid, name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }, Modifier.clip(CircleShape))
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

/** Open chat for a connected coach and player. Contact details stay blocked. */
@Composable
internal fun CoachingChatThread(
    conversation: CoachingConversation, forCoach: Boolean, active: Boolean, busy: Boolean, error: String?,
    onSend: (String) -> Unit, onRead: () -> Unit,
    onWriting: (Boolean) -> Unit = {}, onInteract: () -> Unit = {},
) {
    val draftKey = conversation.playerUid
    var draft by rememberSaveable(draftKey) { mutableStateOf("") }
    val messages = conversation.messages
    // Only messages the server accepted clear the draft, so a failed send keeps its text.
    val accepted = messages.count { it.fromCoach == forCoach && it.at > 0L }
    var submitted by rememberSaveable(draftKey) { mutableStateOf<Int?>(null) }
    LaunchedEffect(accepted) { submitted?.let { if (accepted > it) { draft = ""; submitted = null } } }
    val unread = conversation.unread(forCoach)
    LaunchedEffect(unread, messages.size) { if (unread > 0) onRead() }
    DisposableEffect(draftKey, active, draft.isNotBlank()) {
        onWriting(active && draft.isNotBlank())
        onDispose { onWriting(false) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (messages.isEmpty()) Text(if (forCoach) "No messages yet. Say hello and share the first thing to work on."
            else "No messages yet. Ask your coach about your training.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        messages.forEach { message ->
            key(message.id) {
                val own = message.fromCoach == forCoach
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
                    Surface(shape = RoundedCornerShape(18.dp), color = if (own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.widthIn(max = 320.dp)) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(message.text, style = MaterialTheme.typography.bodyLarge)
                            Text(messageTime(message.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (!active) Text("Connection ended · this conversation is read only.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            val blocked = hasCoachingContact(draft)
            OutlinedTextField(draft, { draft = it.take(1000); onInteract() },
                label = { Text(if (forCoach) "Message your player" else "Message your coach") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, shape = RoundedCornerShape(16.dp), enabled = !busy,
                isError = blocked, supportingText = {
                    Text(if (blocked) "No links, phone numbers, email addresses or social handles." else "${draft.length}/1000 · text only")
                })
            Button(onClick = { submitted = accepted; onSend(draft) }, enabled = !busy && draft.isNotBlank() && !blocked,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (busy) "Sending…" else "Send message")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
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
