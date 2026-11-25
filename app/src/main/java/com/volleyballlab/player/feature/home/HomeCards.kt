package com.volleyballlab.player.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.R
import com.volleyballlab.player.data.assessment.PlannerStatus
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.feature.assessment.PlanGoals
import com.volleyballlab.player.feature.assessment.PlanPractices
import com.volleyballlab.player.feature.assessment.PlanWaiting
import com.volleyballlab.player.ui.component.StudentCard
import java.text.DateFormat
import java.util.Date

private val CardShape = RoundedCornerShape(26.dp)
private const val SHOWN = 3

/** The coach's open assignments, so each can be found, followed and continued from the dashboard. */
@Composable
internal fun HomeAssignmentsCard(items: List<HomeAssignment>, onOpen: (String) -> Unit, onSeeAll: () -> Unit, modifier: Modifier = Modifier) {
    StudentCard(modifier.fillMaxWidth(), shape = CardShape) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.home_assignments_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (items.size > SHOWN) TextButton(onSeeAll) { Text(stringResource(R.string.home_assignments_all)) }
            }
            items.take(SHOWN).forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                AssignmentRow(item) { onOpen(item.assignment.id) }
            }
        }
    }
}

@Composable
private fun AssignmentRow(item: HomeAssignment, onOpen: () -> Unit) {
    val assignment = item.assignment
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(assignment.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val due = assignment.dueAtEpochMs?.let { stringResource(R.string.home_assignment_due, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))) }
                    ?: stringResource(R.string.home_assignment_no_due)
                Text("${stringResource(assignment.skill.displayName)} · $due", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(when (item.state) {
                HomeAssignmentState.New -> R.string.home_assignment_new
                HomeAssignmentState.InProgress -> R.string.home_assignment_in_progress
                HomeAssignmentState.Overdue -> R.string.home_assignment_overdue
                HomeAssignmentState.Sent -> R.string.home_assignment_sent
            }), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                color = if (item.state == HomeAssignmentState.Overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        ScoreBar(item.progress, MaterialTheme.colorScheme.primary, ThinBar)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_assignment_progress, item.repsRecorded, assignment.totalReps), Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onOpen) {
                Text(stringResource(when {
                    item.state == HomeAssignmentState.Sent -> R.string.home_assignment_view
                    item.started -> R.string.home_assignment_continue
                    else -> R.string.home_assignment_start
                }))
            }
        }
    }
}

/** The AI's plan for this week, or the way to get one. The full assessment is in Profile, under Settings. */
@Composable
internal fun WeekPlanCard(
    athlete: AthleteState?,
    status: PlannerStatus,
    drills: Map<String, Drill>,
    done: Set<String>,
    onOpenDrill: (String) -> Unit,
    onAssessment: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (athlete == null) return
    StudentCard(modifier.fillMaxWidth(), shape = CardShape) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.plan_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            val plan = athlete.plan
            if (athlete.answers == null) {
                Text(stringResource(R.string.assessment_start_body), style = MaterialTheme.typography.bodyMedium)
                Button(onAssessment, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.assessment_start)) }
                return@Column
            }
            if (athlete.isWaiting) PlanWaiting(status, onRetry)
            if (plan == null) return@Column
            val planned = plan.analysis.drillIds
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.plan_progress, done.size, planned.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ScoreBar(if (planned.isEmpty()) 0f else done.size.toFloat() / planned.size, MaterialTheme.colorScheme.primary, ThinBar)
            }
            if (plan.changes.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.plan_changes), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                plan.changes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            PlanPractices(plan, athlete.answers, drills, done, onOpenDrill)
            PlanGoals(plan)
        }
    }
}
