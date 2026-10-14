package com.volleyballlab.player.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.ui.component.StudentCard
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.launch

@Composable
fun TrainingAgendaCard(onStart: (Skill) -> Unit, onAssignments: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graph = remember { (context.applicationContext as VolleyballLabApplication).container }
    val workspace = graph.trainingWorkspaceRepository
    val schedule by workspace.schedule.collectAsStateWithLifecycle(emptyList())
    val assignments by graph.coachRepository.assignmentsForMe.collectAsStateWithLifecycle(emptyList())
    val reports by workspace.reports.collectAsStateWithLifecycle(emptyList())
    val plan by graph.planRepository.plan.collectAsStateWithLifecycle(UserPlan.Free)
    val profile by graph.profileRepository.profile.collectAsStateWithLifecycle(AthleteProfile.Anonymous)
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ScheduledTraining?>(null) }
    var adding by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val dayFlow = remember { com.volleyballlab.player.ui.component.calendarDayFlow() }
    val today by dayFlow.collectAsStateWithLifecycle(LocalDate.now())
    val items = TrainingAgenda.today(schedule, assignments, today)
    val usage = ReportAllowance.usage(reports, plan, today)
    fun change(block: suspend () -> Unit) { scope.launch { runCatching { block() }.onFailure { message = "The plan could not be saved. Please retry." } } }
    StudentCard(modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Today's plan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Your practices and coach assignments.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalIconButton(onClick = { adding = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = "Schedule training")
                }
            }
            if (items.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.EventNote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("A clear court, a fresh start.", style = MaterialTheme.typography.titleSmall)
                        Text("Tap + to schedule your next practice.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (item.coachAssigned) item.title else stringResource(item.skill.displayName),
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("${item.time.orEmpty()} · ${item.targetReps} reps${if (item.overdue) " · Overdue" else ""}${if (item.completed) " · Completed" else ""}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (item.completed) Icon(Icons.Rounded.CheckCircle, "Completed", tint = MaterialTheme.colorScheme.primary)
                        if (!item.coachAssigned) IconButton({ editing = schedule.first { it.id == item.id } }) {
                            Icon(Icons.Rounded.Edit, "Edit training", Modifier.size(20.dp))
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (item.coachAssigned) FilledTonalButton(onAssignments) { Text("View assignment") }
                        else {
                            if (!item.completed) FilledTonalButton({ onStart(item.skill) }) { Text("Train now") }
                            TextButton({ change { workspace.completeSchedule(item.id, !item.completed) } }) {
                                Text(if (item.completed) "Undo" else "Mark done")
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                }
            }
            val upcoming = schedule.filter { it.date > today.toString() }.sortedWith(compareBy({ it.date }, { it.time })).take(3)
            if (upcoming.isNotEmpty()) Text("Up next", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            upcoming.forEach { item ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(item.skill.displayName), style = MaterialTheme.typography.titleSmall)
                        Text("${item.date} · ${item.time}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton({ editing = item }) { Icon(Icons.Rounded.Edit, "Edit upcoming training", Modifier.size(20.dp)) }
                }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = Color.Transparent,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))) {
                Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (usage.unlimited) "Pro · Unlimited detailed reports" else "${usage.remaining} of ${usage.limit} reports available this week",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text("Resets ${usage.resetDate}. Saved reports use no extra credit.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (adding || editing != null) ScheduleDialog(editing, profile.goalSkill, onDismiss = { adding = false; editing = null }, onSave = { item ->
        change { workspace.saveSchedule(item); adding = false; editing = null }
    }, onDelete = editing?.let { item -> { change { workspace.deleteSchedule(item.id); editing = null } } })
}

@Composable
private fun ScheduleDialog(initial: ScheduledTraining?, preferred: Skill?, onDismiss: () -> Unit, onSave: (ScheduledTraining) -> Unit, onDelete: (() -> Unit)?) {
    var date by remember { mutableStateOf(initial?.date ?: LocalDate.now().toString()) }
    var time by remember { mutableStateOf(initial?.time ?: "17:00") }
    var skill by remember { mutableStateOf(initial?.skill ?: preferred ?: Skill.Receiving) }
    var reps by remember { mutableStateOf((initial?.targetReps ?: 10).toString()) }
    val valid = runCatching { LocalDate.parse(date); LocalTime.parse(time); require(reps.toInt() in 1..100) }.isSuccess
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Schedule training") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(date, { date = it.take(10) }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
            OutlinedTextField(time, { time = it.take(5) }, label = { Text("Time (HH:MM)") }, singleLine = true)
            Skill.All.forEach { s -> FilterChip(skill == s, { skill = s }, label = { Text(stringResource(s.displayName)) }) }
            OutlinedTextField(reps, { reps = it.filter(Char::isDigit).take(3) }, label = { Text("Target reps") }, singleLine = true)
            onDelete?.let { TextButton(it) { Text("Delete schedule") } }
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = {
        onSave(ScheduledTraining(initial?.id ?: UUID.randomUUID().toString(), date, time, skill, reps.toInt(), initial?.completed ?: false))
    }) { Text("Save") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
