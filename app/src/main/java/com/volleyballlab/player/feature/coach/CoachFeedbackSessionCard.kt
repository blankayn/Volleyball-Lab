package com.volleyballlab.player.feature.coach

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.FeedbackHistory
import com.volleyballlab.player.domain.model.SessionReview
import com.volleyballlab.player.pose.analysis.CuePlainWords
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.domain.model.CoachNote
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.SessionSubmission
import com.volleyballlab.player.ui.component.skillVisual
import com.volleyballlab.player.ui.theme.VbTabularNumerals

/** The sent sessions at a glance: how many, how many are answered, and what keeps coming up. */
@Composable
internal fun FeedbackHistoryCard(history: FeedbackHistory, unread: Int) {
    HubSurface {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.feedback_history_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HistoryCount(history.sent, stringResource(R.string.feedback_history_sent), Modifier.weight(1f))
                HistoryCount(history.reviewed, stringResource(R.string.feedback_history_reviewed), Modifier.weight(1f), FeedbackDoneColor)
                HistoryCount(history.waiting, stringResource(R.string.feedback_history_waiting), Modifier.weight(1f), FeedbackWaitingColor)
            }
            if (unread > 0) Text(pluralStringResource(R.plurals.feedback_history_unread, unread, unread),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            history.workOn?.let { cue ->
                HorizontalDivider(color = coachCardOutline())
                SummaryLine(stringResource(R.string.feedback_history_repeated),
                    stringResource(R.string.feedback_history_repeated_value, CuePlainWords.name(cue.cueId), stringResource(cue.skill.displayName), cue.sessions, cue.of))
            }
        }
    }
}

@Composable
private fun HistoryCount(value: Int, label: String, modifier: Modifier, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$value", style = MaterialTheme.typography.headlineSmall.merge(VbTabularNumerals), fontWeight = FontWeight.SemiBold, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = coachCardMuted())
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = coachCardMuted())
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The app's strongest and weakest check of a session, side by side. */
@Composable
internal fun SessionChecks(strong: CueId?, weak: CueId?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        strong?.let { Box(Modifier.weight(1f)) { SummaryLine(stringResource(R.string.feedback_went_well), CuePlainWords.name(it)) } }
        weak?.let { Box(Modifier.weight(1f)) { SummaryLine(stringResource(R.string.feedback_work_on), CuePlainWords.name(it)) } }
    }
}

/**
 * One session sent to the coach. Closed, it is one small line: which session and
 * whether it is answered. Tapping opens the summary, every comment and [content], the
 * rating and question actions. Comments count as read once it has been opened.
 */
@Composable
internal fun CoachFeedbackSessionCard(
    submission: SessionSubmission,
    session: SessionResult?,
    feedback: List<CoachNote>,
    /** Opens the player's recording at a moment; null when the session is not on this phone. */
    onOpenMoment: ((Long) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    var open by rememberSaveable(submission.coachUid, submission.sessionId) { mutableStateOf(false) }
    val unread = rememberUnreadFeedback()
    val fresh = rememberSeenFeedback(if (open) feedback else emptyList())
    HubSurface {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = stringResource(if (open) R.string.feedback_hide else R.string.feedback_open)) { open = !open }
                .heightIn(min = 64.dp).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                session?.let { Box(Modifier.size(10.dp).clip(CircleShape).background(skillVisual(it.skill).accent)) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(session?.let { "${stringResource(it.skill.displayName)} · ${it.repCount} reps" } ?: "Shared session",
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(dateLabel(session?.startedAtEpochMs ?: submission.submittedAtEpochMs),
                        style = MaterialTheme.typography.bodySmall, color = coachCardMuted())
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (submission.isPending) StatusChip("Waiting", FeedbackWaitingColor)
                    else StatusChip("Reviewed", FeedbackDoneColor)
                    feedback.firstOrNull { it.id in unread }?.let { NewFeedbackChip(it, setOf(it.id)) }
                }
                ExpandIcon(open)
            }
            if (open) Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val review = remember(session) { session?.let(SessionReview::from) }
                val strong = review?.strengths?.firstOrNull()?.cue?.id
                val weak = review?.corrections?.firstOrNull()?.cue?.id
                session?.formScore()?.let { Text(stringResource(R.string.feedback_form_score, it), style = MaterialTheme.typography.titleSmall) }
                if (strong != null || weak != null) SessionChecks(strong, weak)
                feedback.forEach { note ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = .06f)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        NewFeedbackChip(note, fresh)
                        Text(note.text, style = MaterialTheme.typography.bodyMedium)
                        NoteMomentChip(note, onOpenMoment)
                        NotePhoto(note)
                        Text("Your coach · ${dateLabel(note.createdAtEpochMs)}", style = MaterialTheme.typography.labelSmall, color = coachCardMuted())
                    }
                }
                if (feedback.isEmpty()) Text(
                    if (submission.isPending) "Your coach hasn’t reviewed this yet." else "Reviewed without written comments.",
                    style = MaterialTheme.typography.bodyMedium, color = coachCardMuted())
                content()
            }
        }
    }
}
