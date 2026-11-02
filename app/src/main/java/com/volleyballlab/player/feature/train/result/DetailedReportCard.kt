package com.volleyballlab.player.feature.train.result

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
fun DetailedReportCard(session: SessionResult, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graph = remember { (context.applicationContext as VolleyballLabApplication).container }
    val workspace = graph.trainingWorkspaceRepository
    val reports by workspace.reports.collectAsStateWithLifecycle(emptyList())
    val plan by graph.planRepository.plan.collectAsStateWithLifecycle(UserPlan.Free)
    val scope = rememberCoroutineScope()
    var busy by remember(session.id) { mutableStateOf(false) }
    var message by remember(session.id) { mutableStateOf<String?>(null) }
    val saved = reports.firstOrNull { it.sessionId == session.id }
    val dayFlow = remember { com.volleyballlab.player.ui.component.calendarDayFlow() }
    val today by dayFlow.collectAsStateWithLifecycle(LocalDate.now())
    val usage = ReportAllowance.usage(reports, plan, today)
    val assessment = remember(session) { CoachAssessmentEngine.assess(session) }
    val focus = assessment.corrections.firstOrNull()
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Detailed AI report", style = MaterialTheme.typography.titleMedium)
            Text(if (usage.unlimited) "Pro · Unlimited reports" else "${usage.remaining}/${usage.limit} reports remaining · resets ${usage.resetDate}", style = MaterialTheme.typography.bodySmall)
            if (saved != null) {
                Text(saved.headline, style = MaterialTheme.typography.titleSmall)
                Text(saved.explanation)
                Text(saved.nextAction, color = MaterialTheme.colorScheme.primary)
                Text("Saved report · Scores come from final session analysis", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Get an explanation of your measured focus cue and one next action. Your final results remain available below.", style = MaterialTheme.typography.bodySmall)
                if (focus == null) Text("A report needs a repeated, measurable correction. Your visible measurements remain available below.", style = MaterialTheme.typography.bodySmall)
                Button(enabled = !busy && focus != null && (usage.unlimited || usage.remaining > 0), onClick = {
                    if (busy) return@Button
                    busy = true; message = null
                    scope.launch {
                        try {
                            val result = workspace.createReport(session.id, plan) {
                                val measured = requireNotNull(focus)
                                val generated = graph.aiCoachRepository.generate(AiCoachRequest(measured.evidence.cue.id, session.skill,
                                    measured.evidence.referenceScore, measured.evidence.measuredReps, 1,
                                    ((1 - assessment.evidenceCoverage) * 100).roundToInt(), measured.instruction))
                                    ?: return@createReport null
                                SavedDetailedReport(session.id, ReportAllowance.week(LocalDate.now()).toString(), generated.headline,
                                    generated.explanation, generated.nextAction, System.currentTimeMillis(), true)
                            }
                            if (result == null) message = "Report unavailable. No credit was used. Check your connection or weekly allowance."
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            message = "Report unavailable. No credit was used. Please retry later."
                        } finally { busy = false }
                    }
                }) { Text(if (busy) "Generating…" else "Generate report") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
