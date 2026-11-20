package com.volleyballlab.player.feature.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.SportsVolleyball
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.ui.component.StudentCard
import com.volleyballlab.player.ui.component.skillVisual
import com.volleyballlab.player.ui.theme.VbTabularNumerals
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val CardShape = RoundedCornerShape(26.dp)
private val ScoreGreen = Color(0xFF16A06A)
private val GainWash = Color(0xFFE5F6EE)
private val DropInk = Color(0xFFC2410C)
private val DropWash = Color(0xFFFFEDE3)

/**
 * The home screen's Progress section: the latest form score, then a toggle between
 * each skill's latest score and the last seven days of training.
 */
@Composable
internal fun HomeProgressSection(sessions: List<SessionResult>, onViewDetails: () -> Unit, modifier: Modifier = Modifier) {
    val summary = remember(sessions) { HomeProgressSummary.from(sessions) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Progress", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onViewDetails) {
                Text("View details")
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp))
            }
        }
        LatestFormCard(summary, sessions.isEmpty())
        ProgressTabs(tab) { tab = it }
        if (tab == 0) SkillPerformanceCard(summary) else WeeklyActivityCard(summary.week)
    }
}

@Composable
private fun LatestFormCard(summary: HomeProgressSummary, noSessions: Boolean) {
    val colors = MaterialTheme.colorScheme
    val skill = summary.latestSkill
    val score = summary.latestScore
    StudentCard(Modifier.fillMaxWidth(), shape = CardShape) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (skill == null) "Latest form" else "Latest form · ${stringResource(skill.displayName)}",
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    )
                    if (score != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("$score%", style = MaterialTheme.typography.displaySmall.merge(VbTabularNumerals),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.semantics { contentDescription = "$score percent" })
                            summary.change?.let { ChangeChip(it) }
                        }
                    } else {
                        Text(if (noSessions) "No sessions yet" else "No measurable score",
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                    }
                }
                SkillIconTile(skill)
            }
            if (score != null) ScoreBar(score / 100f, ScoreGreen, 10.dp)
            Text(
                text = when {
                    noSessions -> "Record a session to see your form here."
                    score == null -> "Your latest session had no measurable form score."
                    summary.change != null -> "First session ${summary.firstScore}% · Personal best ${summary.bestScore}%"
                    else -> "Record another session to compare your form."
                },
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChangeChip(change: Int) {
    val up = change >= 0
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(if (up) GainWash else DropWash)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        val ink = if (up) ScoreGreen else DropInk
        Icon(if (up) Icons.AutoMirrored.Rounded.TrendingUp else Icons.AutoMirrored.Rounded.TrendingDown, null,
            Modifier.size(16.dp), tint = ink)
        Text("${if (up) "+" else "−"}${kotlin.math.abs(change)} pts", color = ink, fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SkillIconTile(skill: Skill?) {
    val accent = skill?.let { skillVisual(it).accent } ?: MaterialTheme.colorScheme.primary
    val wash = skill?.let { skillVisual(it).wash } ?: MaterialTheme.colorScheme.primary.copy(alpha = .09f)
    Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(wash), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.SportsVolleyball, null, Modifier.size(34.dp), tint = accent)
    }
}

/** A two-option segmented toggle, styled as a pill with a raised selected half. */
@Composable
private fun ProgressTabs(selected: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.onSurface.copy(alpha = .06f)).padding(4.dp)) {
        listOf("Skill performance", "Weekly activity").forEachIndexed { index, label ->
            val active = index == selected
            Box(
                Modifier.weight(1f)
                    .then(if (active) Modifier.shadow(2.dp, RoundedCornerShape(16.dp)) else Modifier)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (active) colors.surfaceContainerLowest else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(index) }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal, style = MaterialTheme.typography.labelLarge,
                    color = if (active) colors.onSurface else colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SkillPerformanceCard(summary: HomeProgressSummary) {
    val colors = MaterialTheme.colorScheme
    StudentCard(Modifier.fillMaxWidth(), shape = CardShape) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Your skills", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                Text("Latest form score", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            summary.skills.forEach { (skill, score) ->
                val accent = skillVisual(skill).accent
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
                        Text(stringResource(skill.displayName), style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f))
                        Text("$score%", style = MaterialTheme.typography.bodyLarge.merge(VbTabularNumerals),
                            fontWeight = FontWeight.Medium)
                    }
                    ScoreBar(score / 100f, accent, 10.dp)
                }
            }
            if (summary.unscored.isNotEmpty()) {
                val names = summary.unscored.map { stringResource(it.displayName).lowercase() }
                val list = if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " and " + names.last()
                Text(
                    if (summary.skills.isEmpty()) "Your skills will appear after their first scored sessions."
                    else "${list.replaceFirstChar { it.uppercase() }} will appear after ${if (names.size == 1) "its" else "their"} first scored session${if (names.size == 1) "" else "s"}.",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WeeklyActivityCard(week: List<DayActivity>) {
    val colors = MaterialTheme.colorScheme
    val total = week.sumOf { it.sessions }
    val most = week.maxOfOrNull { it.sessions }?.coerceAtLeast(1) ?: 1
    val today = LocalDate.now()
    StudentCard(Modifier.fillMaxWidth(), shape = CardShape) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Last 7 days", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                Text(if (total == 1) "1 session" else "$total sessions", style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom) {
                week.forEach { day ->
                    val isToday = day.date == today
                    val name = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                    Column(Modifier.weight(1f).fillMaxHeight().semantics(mergeDescendants = true) {
                        contentDescription = "$name, ${day.sessions} session${if (day.sessions == 1) "" else "s"}"
                    }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
                        if (day.sessions > 0) Text("${day.sessions}", style = MaterialTheme.typography.labelMedium)
                        val fraction by animateFloatAsState(day.sessions / most.toFloat(), label = "dayBar")
                        Box(Modifier.fillMaxWidth().weight(1f, fill = false)
                            .fillMaxHeight(if (day.sessions == 0) .04f else fraction * .78f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (day.sessions == 0) colors.onSurface.copy(alpha = .08f) else colors.primary))
                        Text(name.take(3), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                            fontWeight = if (isToday) FontWeight.Medium else FontWeight.Normal,
                            color = if (isToday) colors.onSurface else colors.onSurfaceVariant)
                    }
                }
            }
            if (total == 0) Text("No sessions in the last 7 days. Record one to start your streak.",
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
}

/** A rounded progress bar that grows in from zero the first time it is shown. */
@Composable
private fun ScoreBar(fraction: Float, color: Color, height: Dp) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val animated by animateFloatAsState(if (shown) fraction.coerceIn(0f, 1f) else 0f, label = "scoreBar")
    Box(Modifier.fillMaxWidth().height(height).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .07f))) {
        Box(Modifier.fillMaxWidth(animated).fillMaxHeight().clip(CircleShape).background(color))
    }
}
