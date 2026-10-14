package com.volleyballlab.player.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SportsVolleyball
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.model.SkillRecommendation
import com.volleyballlab.player.domain.model.TrainingTotals
import com.volleyballlab.player.domain.model.ReportAllowance
import com.volleyballlab.player.feature.session.RecentSessionSummaryCard
import com.volleyballlab.player.ui.component.EntranceAnimation
import com.volleyballlab.player.ui.component.StudentCard
import com.volleyballlab.player.ui.component.TrainingHeroCard
import com.volleyballlab.player.ui.component.studentBackground
import com.volleyballlab.player.ui.component.GhostLayout
import com.volleyballlab.player.ui.component.GhostLoadingScreen
import com.volleyballlab.player.ui.component.calendarDayFlow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private val CourtOrange = Color(0xFFCC5500)
private val CourtApricot = Color(0xFFE9944A)
private val DashboardShape = RoundedCornerShape(26.dp)

@Composable
fun HomeScreen(
    onStartTraining: () -> Unit,
    onSkillSelected: (Skill) -> Unit,
    onTakeAssessment: () -> Unit,
    onViewAllSessions: () -> Unit,
    onOpenSession: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onViewAssignments: () -> Unit = onStartTraining,
) {
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dark = MaterialTheme.colorScheme.surface.luminance() < .3f
    val scheme = MaterialTheme.colorScheme.copy(
        primary = if (dark) Color(0xFFFFB680) else CourtOrange,
        onPrimary = if (dark) Color(0xFF492000) else Color.White,
        primaryContainer = if (dark) Color(0xFF542A11) else Color(0xFFFFD9BF),
        onPrimaryContainer = if (dark) Color(0xFFFFDCC0) else Color(0xFF331500),
    )
    MaterialTheme(colorScheme = scheme) {
        if (!state.loaded) {
            GhostLoadingScreen(GhostLayout.Dashboard, contentPadding, modifier)
            return@MaterialTheme
        }
        var period by remember { mutableStateOf(TrainingPeriod.ThisWeek) }
        val dayFlow = remember { calendarDayFlow() }
        val today by dayFlow.collectAsStateWithLifecycle(LocalDate.now())
        val horizontal = Modifier.padding(horizontal = 20.dp)
        LazyColumn(
            modifier = modifier.fillMaxSize().studentBackground(),
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(key = "greeting") {
                EntranceAnimation(index = 0) { DashboardGreeting(state.profile.name, horizontal) }
            }
            item(key = "start") {
                EntranceAnimation(index = 1) { TrainingHero(onStartTraining, horizontal) }
            }
            item(key = "overview") {
                EntranceAnimation(index = 2) { DashboardOverview(state.sessions, period, { period = it }, today, horizontal) }
            }
            item(key = "agenda") {
                TrainingAgendaCard(onSkillSelected, onViewAssignments, horizontal)
            }
            item(key = "focus") {
                val recommendation = state.recommendation
                FocusCard(recommendation, state.profile.goalSkill != null,
                    onClick = { if (recommendation == null) onTakeAssessment() else onSkillSelected(recommendation.skill) },
                    modifier = horizontal)
            }
            item(key = "recent_header") {
                Row(horizontal.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.home_section_recent), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (state.sessions.isNotEmpty()) TextButton(onViewAllSessions) {
                        Text(stringResource(R.string.home_view_all))
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp))
                    }
                }
            }
            if (state.sessions.isEmpty()) {
                item(key = "empty") {
                    StudentCard(horizontal.fillMaxWidth(), shape = DashboardShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            IconTile(Icons.Rounded.SportsVolleyball)
                            Text(stringResource(R.string.home_no_sessions), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                items(state.sessions.take(3), key = { it.id }) { session ->
                    RecentSessionSummaryCard(session, { onOpenSession(session.id) }, horizontal)
                }
            }
        }
    }
}

@Composable
private fun DashboardGreeting(name: String, modifier: Modifier) {
    val firstName = name.trim().substringBefore(' ').takeIf { it.isNotBlank() }
    val hour = LocalTime.now().hour
    val greeting = when {
        hour < 12 -> if (firstName == null) R.string.home_greeting_morning else R.string.home_greeting_morning_named
        hour < 18 -> if (firstName == null) R.string.home_greeting_afternoon else R.string.home_greeting_afternoon_named
        else -> if (firstName == null) R.string.home_greeting_evening else R.string.home_greeting_evening_named
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("TRAINING HUB", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(if (firstName == null) stringResource(greeting) else stringResource(greeting, firstName),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Make every rep count.", fontSize = 29.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface)
        Text("Practice your skills. Review your form. Track your progress.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrainingHero(onClick: () -> Unit, modifier: Modifier) {
    TrainingHeroCard(onClick, modifier)
}

@Composable
private fun DashboardOverview(sessions: List<SessionResult>, period: TrainingPeriod,
    onPeriodChange: (TrainingPeriod) -> Unit, today: LocalDate, modifier: Modifier) {
    // The weekly activity overview starts on Monday.
    val selectedSessions = if (period == TrainingPeriod.ThisWeek) {
        val week = ReportAllowance.week(today)
        sessions.filter { Instant.ofEpochMilli(it.startedAtEpochMs)
            .atZone(ZoneId.systemDefault()).toLocalDate() in week..today }
    } else period.filter(sessions, today = today)
    val totals = TrainingTotals.from(selectedSessions)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Your momentum", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            var expanded by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(4.dp)) {
                    Text(stringResource(period.label), style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(20.dp))
                }
                DropdownMenu(expanded, { expanded = false }) {
                    TrainingPeriod.entries.forEach { option -> DropdownMenuItem(
                        text = { Text(stringResource(option.label)) },
                        onClick = { onPeriodChange(option); expanded = false }) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DashboardStat(totals.sessionCount.toString(), "Sessions", Icons.Rounded.SportsVolleyball, Modifier.weight(1f))
            DashboardStat(totals.totalMinutes.toString(), "Minutes", Icons.Rounded.Schedule, Modifier.weight(1f))
            DashboardStat(totals.averageScore?.let { "$it%" } ?: "—", "Est. form", Icons.Rounded.TrackChanges, Modifier.weight(1f))
        }
        ActivityStrip(sessions, today)
    }
}

@Composable
private fun DashboardStat(value: String, label: String, icon: ImageVector, modifier: Modifier) {
    StudentCard(modifier, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(34.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .08f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(value, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActivityStrip(sessions: List<SessionResult>, today: LocalDate) {
    val zone = ZoneId.systemDefault()
    val daily = sessions.groupingBy { Instant.ofEpochMilli(it.startedAtEpochMs).atZone(zone).toLocalDate() }.eachCount()
    val dates = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val maxCount = dates.maxOf { daily[it] ?: 0 }.coerceAtLeast(1)
        val accent = MaterialTheme.colorScheme.primary
    val rail = MaterialTheme.colorScheme.primaryContainer
    StudentCard(Modifier.fillMaxWidth(), shape = DashboardShape) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Training activity", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Last 7 days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                dates.forEach { date ->
                    val count = daily[date] ?: 0
                    Column(Modifier.weight(1f).semantics { contentDescription = "$date: $count sessions" },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.height(42.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.width(13.dp).fillMaxHeight().background(rail, CircleShape))
                            if (count > 0) Box(Modifier.width(13.dp).height((12 + 30f * count / maxCount).dp)
                                .background(Brush.verticalGradient(listOf(CourtApricot, accent)), CircleShape))
                        }
                        Text(date.dayOfWeek.name.take(1), style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal,
                            color = if (date == today) accent else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusCard(recommendation: SkillRecommendation?, savedGoal: Boolean, onClick: () -> Unit, modifier: Modifier) {
    StudentCard(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = DashboardShape,
        color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(Icons.Rounded.TrackChanges)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (recommendation == null) "Find your starting point" else "Your training focus",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(if (recommendation == null) stringResource(R.string.home_assessment_title)
                    else stringResource(recommendation.skill.displayName),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(if (recommendation == null) stringResource(R.string.home_assessment_body)
                    else stringResource(recommendation.skill.description),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                if (recommendation != null) {
                    Text(stringResource(R.string.home_skill_level, recommendation.level.number, stringResource(recommendation.level.label)),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if (savedGoal) Text("From your saved training goal", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f))
                    Text(stringResource(
                        if (recommendation.isMeasured) R.string.home_focus_source_measured else R.string.home_focus_source_assessment),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f))
                }
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun IconTile(icon: ImageVector) {
    Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .09f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
    }
}
