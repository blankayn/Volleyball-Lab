package com.volleyballlab.player.feature.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.domain.model.DailyProgress
import com.volleyballlab.player.ui.component.StudentCard
import java.time.format.DateTimeFormatter

@Composable
fun WeeklyProgressCard(days: List<DailyProgress>, modifier: Modifier = Modifier) {
    StudentCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Last seven days", style = MaterialTheme.typography.titleMedium)
            Text("${days.sumOf { it.validReps }} valid reps · ${days.sumOf { it.durationMs } / 60_000} min · ${days.count { it.sessions > 0 }}/7 active days",
                style = MaterialTheme.typography.bodyMedium)
            Text("Final form score by day", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                days.forEach { day ->
                    val label = day.date.format(DateTimeFormatter.ofPattern("EEE"))
                    Column(Modifier.weight(1f).semantics {
                        contentDescription = "$label, ${day.meanScore?.let { "$it out of 100" } ?: "no measurable score"}, ${day.validReps} valid reps, ${day.durationMs / 60_000} minutes"
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(day.meanScore?.toString() ?: "—", style = MaterialTheme.typography.labelMedium)
                        Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.fillMaxWidth(.65f).height(day.meanScore?.let { (it.coerceAtLeast(2) * .9f).dp } ?: 2.dp)
                                .background(if (day.meanScore == null) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)))
                        }
                        Text(label, style = MaterialTheme.typography.labelSmall)
                        Text(day.date.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text("Weekly rhythm", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                days.forEach { day ->
                    Surface(Modifier.weight(1f), shape = RoundedCornerShape(8.dp),
                        color = if (day.sessions > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${day.sessions}", style = MaterialTheme.typography.labelMedium)
                            Text("${day.durationMs / 60_000}m", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Text("Valid reps require at least 80/100 and 75% measurable cue coverage. A dash means no measurable score.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
