package com.volleyballlab.player.feature.coach

import androidx.compose.foundation.Image
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.ui.component.DrillArt
import com.volleyballlab.player.ui.component.skillVisual
import com.volleyballlab.player.R
import java.io.File
import java.text.DateFormat
import java.util.Date

private val Waiting = Color(0xFFB7791F)
private val Done = Color(0xFF2E9D5B)

@Composable private fun shortDate(epochMs: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))

/** A small rounded status label in sentence case, coloured by meaning. */
@Composable internal fun StatusChip(label: String, color: Color) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = color,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = .12f)).padding(horizontal = 8.dp, vertical = 3.dp))
}

/** A drill's picture on its skill colour, so every drill reads the same way across the app. */
@Composable private fun DrillTile(assignment: CoachAssignment, size: Int) {
    val visual = skillVisual(assignment.skill)
    val art = assignment.libraryDrillId?.let(DrillArt::forDrill) ?: visual.illustration
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(16.dp)).background(visual.wash), contentAlignment = Alignment.Center) {
        Image(painterResource(art), null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
    }
}

/** One drill still to do: what it is, how much, when it is due. Tapping opens it. */
@Composable internal fun PlanAssignmentCard(assignment: CoachAssignment, onClick: () -> Unit) {
    val now = remember { System.currentTimeMillis() }
    HubSurface(onClick = onClick) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DrillTile(assignment, 60)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(assignment.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${stringResource(assignment.skill.displayName)} · ${assignment.targetSets} sets × ${assignment.targetReps} reps",
                    style = MaterialTheme.typography.bodySmall, color = coachCardMuted())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (assignment.status == AssignmentStatus.InProgress) StatusChip("In progress", MaterialTheme.colorScheme.primary)
                    assignment.dueAtEpochMs?.let {
                        if (assignment.isOverdue(now)) StatusChip("Overdue · ${shortDate(it)}", MaterialTheme.colorScheme.error)
                        else Text("Due ${shortDate(it)}", style = MaterialTheme.typography.bodySmall, color = coachCardMuted())
                    }
                }
            }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = coachCardMuted())
        }
    }
}

/** Finished drills, kept short: one line each in a single card. */
@Composable internal fun CompletedAssignments(done: List<CoachAssignment>, onOpen: (String) -> Unit) {
    HubSurface {
        Column {
            done.forEachIndexed { index, assignment ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 56.dp), color = coachCardOutline())
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(assignment.id) }.heightIn(min = 56.dp)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(22.dp), tint = Done)
                    Text(assignment.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(assignment.skill.displayName), style = MaterialTheme.typography.bodySmall, color = coachCardMuted())
                }
            }
        }
    }
}

/** A quiet "nothing here yet" card: what will appear, and why. */
@Composable internal fun CoachPlainEmpty(title: String, body: String) {
    HubSurface {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = coachCardMuted())
        }
    }
}

/**
 * Sending a recorded session for review, at the top of Feedback: the newest sessions
 * not yet sent, each with a short summary so the player can tell which one to send.
 */
@Composable internal fun SendSessionCard(unsent: List<SessionResult>, noSessions: Boolean, sending: Boolean, error: String?, onSend: (String) -> Unit) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    HubSurface {
        Column(Modifier.padding(vertical = 16.dp)) {
            Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.send_session_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(when {
                    noSessions -> R.string.send_session_none
                    unsent.isEmpty() -> R.string.send_session_all_sent
                    else -> R.string.send_session_body
                }), style = MaterialTheme.typography.bodyMedium, color = coachCardMuted())
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            if (unsent.isNotEmpty()) Spacer(Modifier.height(8.dp))
            val visible = if (showAll) unsent else unsent.take(3)
            visible.forEachIndexed { index, session ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = coachCardOutline())
                SendSessionRow(session, sending, sending && chosen == session.id) {
                    chosen = session.id
                    onSend(session.id)
                }
            }
            if (unsent.size > 3) TextButton(onClick = { showAll = !showAll }, modifier = Modifier.padding(horizontal = 6.dp)) {
                Text(if (showAll) "Show fewer" else "Show ${unsent.size - 3} more")
            }
        }
    }
}

/** One small line per session. Tapping it opens the summary; Send is always in reach. */
@Composable private fun SendSessionRow(session: SessionResult, sending: Boolean, sendingThis: Boolean, onSend: () -> Unit) {
    var open by rememberSaveable(session.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = stringResource(if (open) R.string.feedback_hide else R.string.feedback_open)) { open = !open }
            .heightIn(min = 60.dp).padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(skillVisual(session.skill).accent))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${stringResource(session.skill.displayName)} · ${session.repCount} reps", style = MaterialTheme.typography.bodyLarge)
                Text(dateLabel(session.startedAtEpochMs), style = MaterialTheme.typography.bodySmall, color = coachCardMuted())
            }
            ExpandIcon(open)
            FilledTonalButton(onClick = onSend, enabled = !sending, shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) { Text(if (sendingThis) "Sending…" else "Send") }
        }
        if (open) {
            val review = remember(session) { SessionReview.from(session) }
            val strong = review.strengths.firstOrNull()?.cue?.id
            val weak = review.corrections.firstOrNull()?.cue?.id
            val hasVideo = remember(session.videoPath) { session.videoPath?.let { File(it).isFile } == true }
            Column(Modifier.fillMaxWidth().padding(start = 40.dp, end = 18.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                review.formScore?.let { Text(stringResource(R.string.feedback_form_score, it), style = MaterialTheme.typography.titleSmall) }
                if (strong != null || weak != null) SessionChecks(strong, weak)
                else Text(stringResource(if (session.repCount == 0) R.string.send_session_no_reps else R.string.send_session_not_measured),
                    style = MaterialTheme.typography.bodySmall, color = coachCardMuted())
                if (hasVideo) Text(stringResource(R.string.send_session_video), style = MaterialTheme.typography.bodySmall, color = coachCardMuted())
            }
        }
    }
}

@Composable internal fun ExpandIcon(open: Boolean) {
    Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(22.dp), tint = coachCardMuted())
}

/** All / Reviewed / Waiting, as a standard segmented control. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun FeedbackFilter(selected: Int, waiting: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("All", "Reviewed", if (waiting > 0) "Waiting ($waiting)" else "Waiting")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { index, label ->
            SegmentedButton(selected = selected == index, onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index, labels.size), icon = {}) {
                Text(label, maxLines = 1)
            }
        }
    }
}

/** A coach note that is not tied to a session. */
@Composable internal fun CoachGeneralNote(note: CoachNote) {
    val fresh = rememberSeenFeedback(listOf(note))
    HubSurface {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Note from your coach", style = MaterialTheme.typography.labelLarge, color = coachCardMuted())
            NewFeedbackChip(note, fresh)
            Text(note.text, style = MaterialTheme.typography.bodyLarge)
            NotePhoto(note)
            Text(dateLabel(note.createdAtEpochMs), style = MaterialTheme.typography.bodySmall, color = coachCardMuted())
        }
    }
}

internal val FeedbackWaitingColor = Waiting
internal val FeedbackDoneColor = Done
