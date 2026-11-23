package com.volleyballlab.player.feature.coach

import androidx.compose.foundation.Image
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
 * not yet sent, each with its own Send button.
 */
@Composable internal fun SendSessionCard(unsent: List<SessionResult>, noSessions: Boolean, sending: Boolean, error: String?, onSend: (String) -> Unit) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    HubSurface {
        Column(Modifier.padding(vertical = 16.dp)) {
            Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Get feedback on a session", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(when {
                    noSessions -> "Record a training session first, then send it here."
                    unsent.isEmpty() -> "You’ve sent all your sessions. New ones will show up here."
                    else -> "Send a recorded session and your coach will review your form."
                }, style = MaterialTheme.typography.bodyMedium, color = coachCardMuted())
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            if (unsent.isNotEmpty()) Spacer(Modifier.height(8.dp))
            val visible = if (showAll) unsent else unsent.take(3)
            visible.forEachIndexed { index, session ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 42.dp), color = coachCardOutline())
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(skillVisual(session.skill).accent))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${stringResource(session.skill.displayName)} · ${session.repCount} reps",
                            style = MaterialTheme.typography.bodyLarge)
                        Text(dateLabel(session.startedAtEpochMs), style = MaterialTheme.typography.bodySmall, color = coachCardMuted())
                    }
                    FilledTonalButton(onClick = { onSend(session.id) }, enabled = !sending, shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) { Text("Send") }
                }
            }
            if (sending) Text("Sending…", style = MaterialTheme.typography.bodySmall, color = coachCardMuted(),
                modifier = Modifier.padding(horizontal = 18.dp))
            if (unsent.size > 3) TextButton(onClick = { showAll = !showAll }, modifier = Modifier.padding(horizontal = 6.dp)) {
                Text(if (showAll) "Show fewer" else "Show ${unsent.size - 3} more")
            }
        }
    }
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
    HubSurface {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Note from your coach", style = MaterialTheme.typography.labelLarge, color = coachCardMuted())
            Text(note.text, style = MaterialTheme.typography.bodyLarge)
            NotePhoto(note)
            Text(dateLabel(note.createdAtEpochMs), style = MaterialTheme.typography.bodySmall, color = coachCardMuted())
        }
    }
}

internal val FeedbackWaitingColor = Waiting
internal val FeedbackDoneColor = Done
