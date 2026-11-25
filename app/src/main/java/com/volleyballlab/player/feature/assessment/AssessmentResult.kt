package com.volleyballlab.player.feature.assessment

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.R
import com.volleyballlab.player.data.assessment.PlannerStatus
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.ui.component.StudentCard
import com.volleyballlab.player.ui.theme.vbColors

/** The whole assessment, or why there is none yet. */
@Composable
internal fun AssessmentResult(plan: AthletePlan?, answers: AssessmentAnswers?, drills: Map<String, Drill>, status: PlannerStatus, onRetry: () -> Unit, onOpenDrill: ((String) -> Unit)? = null) {
    if (plan == null) { PlanWaiting(status, onRetry); return }
    AssessmentSummary(plan, answers)
    Text(stringResource(R.string.plan_title), style = MaterialTheme.typography.titleLarge)
    PlanPractices(plan, answers, drills, emptySet(), onOpenDrill)
    PlanGoals(plan)
}

/** Nothing is made up while the AI is away. */
@Composable
internal fun PlanWaiting(status: PlannerStatus, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (status) {
            PlannerStatus.Working -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.plan_working), style = MaterialTheme.typography.bodyMedium)
            }
            PlannerStatus.Limited -> Text(stringResource(R.string.plan_limited), style = MaterialTheme.typography.bodyMedium)
            else -> {
                Text(stringResource(R.string.plan_unavailable), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onRetry, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.plan_retry)) }
            }
        }
    }
}

@Composable
internal fun AssessmentSummary(plan: AthletePlan, answers: AssessmentAnswers?) {
    val facts = answers?.let { AssessmentFacts.of(it, plan.measured, plan.checks) }.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.assessment_by_ai), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text(plan.analysis.summary, style = MaterialTheme.typography.bodyLarge)
        Points(stringResource(R.string.assessment_strengths), plan.analysis.strengths, facts)
        Points(stringResource(R.string.assessment_weaknesses), plan.analysis.weaknesses, facts)
        Points(stringResource(R.string.assessment_improve), plan.analysis.improvements, facts)
        Text(stringResource(if (plan.measured.any { it.score != null }) R.string.assessment_basis_measured else R.string.assessment_basis_answers),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Points(title: String, points: List<AssessmentPoint>, facts: Map<String, AssessmentFact>) {
    if (points.isEmpty()) return
    StudentCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            points.forEach { point ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(point.skill.displayName), style = MaterialTheme.typography.labelLarge)
                    Text(point.text, style = MaterialTheme.typography.bodyMedium)
                    // The athlete can see what each line came from.
                    val from = point.evidence.mapNotNull { facts[it] }.map { factText(it) }
                    if (from.isNotEmpty()) Text(stringResource(R.string.assessment_from, from.joinToString(" · ")),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun factText(fact: AssessmentFact): String = when {
    fact.statement != null -> "“${fact.statement.text}” ${fact.answer?.label.orEmpty()}" +
        if (fact.details.isEmpty()) "" else ": ${fact.details.joinToString { it.text }}"
    fact.measured != null -> stringResource(R.string.assessment_fact_score, stringResource(fact.measured.skill.displayName), fact.measured.score ?: 0)
    fact.cueId != null -> stringResource(R.string.assessment_fact_check, FormCueCatalog.byId(fact.cueId)?.let { stringResource(it.label) }.orEmpty())
    else -> fact.weakPoint?.label.orEmpty()
}

/** Each practice with its drills. [done] drills are ticked. */
@Composable
internal fun PlanPractices(plan: AthletePlan, answers: AssessmentAnswers?, drills: Map<String, Drill>, done: Set<String>, onOpenDrill: ((String) -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        plan.analysis.practices.forEach { practice ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(practice.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.plan_minutes, PlanRules.practiceMinutes(practice, drills)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                practice.drills.forEach { planned -> drills[planned.drillId]?.let { PlannedDrillRow(planned, it, answers, plan, planned.drillId in done, onOpenDrill) } }
            }
        }
    }
}

@Composable
private fun PlannedDrillRow(planned: PlannedDrill, drill: Drill, answers: AssessmentAnswers?, plan: AthletePlan, done: Boolean, onOpen: ((String) -> Unit)?) {
    val wanted = answers?.let { a -> PlanRules.levelFor(a.experience, plan.measured.firstOrNull { it.skill == drill.skill }?.score) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).let { if (onOpen == null) it else it.clickable(role = Role.Button) { onOpen(drill.id) } },
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(drill.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.plan_drill, stringResource(drill.skill.displayName), planned.sets, planned.reps, stringResource(drill.level.displayName)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(planned.why, style = MaterialTheme.typography.bodyMedium)
            if (wanted != null && wanted != drill.level) Text(stringResource(R.string.plan_closest, stringResource(wanted.displayName).lowercase()),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (done) Icon(Icons.Rounded.CheckCircle, stringResource(R.string.plan_done), Modifier.size(22.dp), tint = MaterialTheme.vbColors.success)
        else if (onOpen != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun PlanGoals(plan: AthletePlan) {
    if (plan.analysis.weeklyGoals.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.plan_goals), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        plan.analysis.weeklyGoals.forEach { Text("• ${it.text}", style = MaterialTheme.typography.bodyMedium) }
    }
}
