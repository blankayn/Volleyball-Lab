package com.volleyballlab.player.feature.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volleyballlab.player.domain.model.CoachNote
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.SessionSubmission

@Composable
internal fun CoachFeedbackSessionCard(
    submission: SessionSubmission,
    session: SessionResult?,
    feedback: List<CoachNote>,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    CoachSectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.padding(top = 10.dp).size(8.dp).background(
                if (submission.isPending) coachCardOutline() else MaterialTheme.colorScheme.primary, CircleShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(session?.skill?.name ?: "Shared session", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    CoachStatusPill(if (submission.isPending) "AWAITING FEEDBACK" else "REVIEWED", accent = !submission.isPending, compact = true)
                }
                Text(dateLabel(session?.startedAtEpochMs ?: submission.submittedAtEpochMs),
                    style = MaterialTheme.typography.labelSmall, color = coachCardMuted())
            }
            session?.formScore()?.let { score ->
                Surface(shape = RoundedCornerShape(13.dp), color = coachCardMuted().copy(alpha = .045f)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(score.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("/100", style = MaterialTheme.typography.labelSmall, color = coachCardMuted())
                    }
                }
            }
        }
        feedback.forEach { note ->
            val rule = MaterialTheme.colorScheme.primary.copy(alpha = .65f)
            Column(
                Modifier.fillMaxWidth().background(coachCardMuted().copy(alpha = .025f))
                    .drawBehind { drawLine(rule, Offset.Zero, Offset(0f, size.height), 1.dp.toPx()) }
                    .padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("“${note.text}”", style = MaterialTheme.typography.bodyMedium)
                Text("Coach feedback · ${dateLabel(note.createdAtEpochMs)}",
                    style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = coachCardMuted())
            }
        }
        if (feedback.isEmpty()) {
            Row(Modifier.heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(6.dp).background(if (submission.isPending) Color(0xFFF3BA35)
                    else MaterialTheme.colorScheme.primary, CircleShape))
                Text(if (submission.isPending) "With coach for review" else "This session has been reviewed.",
                    style = MaterialTheme.typography.bodySmall, color = coachCardMuted())
            }
        }
        content()
    }
}
