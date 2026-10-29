package com.volleyballlab.player.feature.coachmode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AssignmentTurnedIn
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.R
import com.volleyballlab.player.ui.component.ResponsivePair

/** One grouped workspace, with a single destination for each coaching task. */
@Composable
internal fun CoachDashboardWorkspace(
    onOpenRoster: () -> Unit,
    onOpenAssign: () -> Unit,
    onOpenQuestions: () -> Unit,
    onOpenAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CoachCard(modifier) {
        WorkspaceEyebrow(stringResource(R.string.coach_workspace_eyebrow))
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.coach_workspace_title), style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        ResponsivePair(minItemWidth = 120.dp, first = { tile ->
            WorkspaceTaskCard(stringResource(R.string.nav_coach_roster), stringResource(R.string.coach_workspace_players),
                Icons.Rounded.Group, CoachAccent.Rust, onOpenRoster, tile)
        }, second = { tile ->
            WorkspaceTaskCard(stringResource(R.string.coach_workspace_assignments_title), stringResource(R.string.coach_workspace_assignments),
                Icons.Rounded.AssignmentTurnedIn, CoachAccent.Violet, onOpenAssign, tile)
        })
        Spacer(Modifier.height(12.dp))
        WorkspaceDestination(stringResource(R.string.coach_workspace_questions_title), stringResource(R.string.coach_workspace_questions),
            Icons.Rounded.QuestionAnswer, CoachAccent.Indigo, onOpenQuestions)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onOpenAccount, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.coach_workspace_account))
        }
    }
}

@Composable
private fun WorkspaceTaskCard(
    title: String, body: String, icon: ImageVector, accent: CoachAccent, onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(18.dp), color = accent.colors().tint) {
        Column(Modifier.heightIn(min = 174.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WorkspaceDestination(
    title: String, body: String, icon: ImageVector, accent: CoachAccent, onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
        .heightIn(min = 80.dp).padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CoachIconChip(icon, accent, size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
