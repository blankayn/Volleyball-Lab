package com.volleyballlab.player.feature.coach

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Read-only previews: forms and submission actions remain in their full sections. */
@Composable
internal fun MyCoachHubSections(state: CoachLinkState, onManageConnection: (() -> Unit)? = null, onOpen: (CoachWorkspaceTab) -> Unit) {
    val active = state.link?.isActive == true
    val assignments = state.assignments.filter { it.coachUid == state.link?.coachUid && it.isOpen }
    val reviewed = state.submissions.count { !it.isPending }
    val pending = state.submissions.count { it.isPending }
    val latestNote = state.notes.maxByOrNull { it.createdAtEpochMs }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CoachHubSectionCard(CoachWorkspaceTab.Overview, Icons.Rounded.Insights,
            if (active) "Connected" else if (state.link?.isPending == true) "Request pending" else "Get connected",
            if (active) "Coach’s guidance & next practice steps"
            else if (state.link?.isPending == true) "Your coach is reviewing your request."
            else "Find a coach and start building your training plan.", onOpen)
        CoachHubSectionCard(CoachWorkspaceTab.Training, Icons.Rounded.FitnessCenter,
            "${assignments.size} active",
            if (active) "Assigned drills & sessions ready to log"
                else "Your training plan will be ready when a coach accepts you.", onOpen)
        CoachHubSectionCard(CoachWorkspaceTab.Feedback, Icons.Rounded.QuestionAnswer,
            if (pending > 0) "$pending pending" else "$reviewed reviewed",
            "$reviewed reviewed" + (latestNote?.text?.takeIf { it.isNotBlank() }?.let { " · $it" }
                ?: " · Session feedback and your reviews"), onOpen)
        if (active && onManageConnection != null) {
            TextButton(onClick = onManageConnection, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Manage coach connection", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = coachCardMuted())
            }
        }
    }
}

@Composable
private fun CoachHubSectionCard(tab: CoachWorkspaceTab, icon: ImageVector, status: String, preview: String, onOpen: (CoachWorkspaceTab) -> Unit) {
    Surface(onClick = { onOpen(tab) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, coachCardOutline()), shadowElevation = 1.dp) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .055f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .15f))) {
                Icon(icon, null, Modifier.padding(11.dp).size(21.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tab.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    CoachStatusPill(status, accent = tab != CoachWorkspaceTab.Training)
                }
                Text(preview, style = MaterialTheme.typography.bodySmall, color = coachCardMuted(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = coachCardMuted().copy(alpha = .7f), modifier = Modifier.size(18.dp))
        }
    }
}
