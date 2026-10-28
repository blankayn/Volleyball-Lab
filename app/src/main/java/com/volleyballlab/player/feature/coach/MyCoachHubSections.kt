package com.volleyballlab.player.feature.coach

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Read-only previews: forms and submission actions remain in their full sections. */
@Composable
internal fun MyCoachHubSections(state: CoachLinkState, onOpen: (CoachWorkspaceTab) -> Unit) {
    val active = state.link?.isActive == true
    val assignments = state.assignments.filter { it.coachUid == state.link?.coachUid && it.isOpen }
    val reviewed = state.submissions.count { !it.isPending }
    val pending = state.submissions.count { it.isPending }
    val latestNote = state.notes.maxByOrNull { it.createdAtEpochMs }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CoachSectionTitle("Your coaching workspace", "Your connection, next steps and progress in one place")
        CoachHubSectionCard(CoachWorkspaceTab.Overview, Icons.Rounded.Insights,
            if (active) "Your connection" else if (state.link?.isPending == true) "Request pending" else "Get connected",
            if (active) "See your coach’s guidance and ask what to practise next."
            else if (state.link?.isPending == true) "Your coach is reviewing your request."
            else "Find a coach and start building your training plan.", onOpen)
        CoachHubSectionCard(CoachWorkspaceTab.Training, Icons.Rounded.FitnessCenter,
            "${assignments.size} ${if (assignments.size == 1) "assignment" else "assignments"} to work on",
            assignments.firstOrNull()?.let { "Up next: ${it.title}" }
                ?: if (active) "Your assigned drills and sessions ready to share will appear here."
                else "Your training plan will be ready when a coach accepts you.", onOpen)
        CoachHubSectionCard(CoachWorkspaceTab.Feedback, Icons.Rounded.QuestionAnswer,
            "$reviewed reviewed · $pending awaiting feedback",
            latestNote?.text?.take(180)?.takeIf { it.isNotBlank() }
                ?: "See session feedback, revisit coaching questions and review completed sessions.", onOpen)
    }
}

@Composable
private fun CoachHubSectionCard(tab: CoachWorkspaceTab, icon: ImageVector, status: String, preview: String, onOpen: (CoachWorkspaceTab) -> Unit) {
    Surface(onClick = { onOpen(tab) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .08f)) {
                    Icon(icon, null, Modifier.padding(12.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(tab.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(preview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Open ${tab.label.lowercase(java.util.Locale.ROOT)}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}
