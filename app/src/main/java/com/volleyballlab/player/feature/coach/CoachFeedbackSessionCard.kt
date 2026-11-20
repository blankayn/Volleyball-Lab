package com.volleyballlab.player.feature.coach

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
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.SessionSubmission
import com.volleyballlab.player.ui.component.skillVisual
import com.volleyballlab.player.ui.theme.VbTabularNumerals

/**
 * One session sent to the coach: which session, whether it has been reviewed, its
 * form score, and the coach's comments. [content] holds the rating and question actions.
 */
@Composable
internal fun CoachFeedbackSessionCard(
    submission: SessionSubmission,
    session: SessionResult?,
    feedback: List<CoachNote>,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    HubSurface {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                session?.let { Box(Modifier.size(10.dp).clip(CircleShape).background(skillVisual(it.skill).accent)) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(session?.let { "${stringResource(it.skill.displayName)} · ${it.repCount} reps" } ?: "Shared session",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(dateLabel(session?.startedAtEpochMs ?: submission.submittedAtEpochMs),
                        style = MaterialTheme.typography.bodySmall, color = coachCardMuted())
                }
                session?.formScore()?.let { score ->
                    Column(horizontalAlignment = Alignment.End) {
                        Text("$score", style = MaterialTheme.typography.titleLarge.merge(VbTabularNumerals), fontWeight = FontWeight.SemiBold)
                        Text("form", style = MaterialTheme.typography.labelSmall, color = coachCardMuted())
                    }
                }
            }
            if (submission.isPending) StatusChip("Waiting for review", FeedbackWaitingColor)
            else StatusChip("Reviewed", FeedbackDoneColor)

            feedback.forEach { note ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .06f)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(note.text, style = MaterialTheme.typography.bodyMedium)
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
