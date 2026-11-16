package com.volleyballlab.player.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.runtime.saveable.rememberSaveable
import com.volleyballlab.player.ui.component.ScoreArc
import com.volleyballlab.player.ui.theme.VbMotion
import com.volleyballlab.player.ui.theme.VbTabularNumerals
import com.volleyballlab.player.ui.theme.vbTween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SportsVolleyball
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.isValidFor
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.model.SkillRecommendation
import com.volleyballlab.player.feature.session.RecentSessionSummaryCard
import com.volleyballlab.player.ui.component.EntranceAnimation
import com.volleyballlab.player.ui.component.StudentCard
import com.volleyballlab.player.ui.component.TrainingHeroCard
import com.volleyballlab.player.ui.component.studentBackground
import com.volleyballlab.player.ui.component.GhostLayout
import com.volleyballlab.player.ui.component.GhostLoadingScreen
import java.time.LocalTime

private val CourtOrange = Color(0xFFCC5500)
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
                EntranceAnimation(index = 2) {
                    DashboardOverview(state.sessions, onViewAllSessions, horizontal)
                }
            }
            item(key = "agenda") {
                EntranceAnimation(index = 3) { TrainingAgendaCard(onSkillSelected, onViewAssignments, horizontal) }
            }
            item(key = "focus") {
                val recommendation = state.recommendation
                EntranceAnimation(index = 4) {
                    FocusCard(recommendation, state.profile.goalSkill != null,
                        onClick = { if (recommendation == null) onTakeAssessment() else onSkillSelected(recommendation.skill) },
                        modifier = horizontal)
                }
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
                itemsIndexed(state.sessions.take(3), key = { _, session -> session.id }) { index, session ->
                    EntranceAnimation(index = 5 + index) {
                        RecentSessionSummaryCard(session, { onOpenSession(session.id) }, horizontal)
                    }
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

/** Runs once when the dashboard appears, so numbers count up and bars grow from zero. */
@Composable
private fun rememberRevealed(): Boolean {
    var revealed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }
    return revealed
}

@Composable
private fun CountUp(target: Int, revealed: Boolean, style: androidx.compose.ui.text.TextStyle, suffix: String = "") {
    val value by animateIntAsState(
        targetValue = if (revealed) target else 0,
        animationSpec = vbTween(durationMillis = VbMotion.ExtraLong, easing = VbMotion.EmphasizedDecelerate),
        label = "countUp",
    )
    Text("$value$suffix", style = style.merge(VbTabularNumerals), fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun DashboardOverview(
    sessions: List<SessionResult>,
    onViewProgress: () -> Unit,
    modifier: Modifier,
) {
    val revealed = rememberRevealed()
    val latest = sessions.maxByOrNull { it.startedAtEpochMs }
    val latestScore = latest?.formScore()
    // Overall progress only: no daily or weekly breakdown on the dashboard.
    val scored = remember(sessions) { sessions.sortedBy { it.startedAtEpochMs }.mapNotNull { it.formScore() } }
    val firstScore = scored.firstOrNull()
    val bestScore = scored.maxOrNull()
    val learning = remember(sessions) { com.volleyballlab.player.domain.model.LearningProgress.from(sessions) }
    val validReps = sessions.sumOf { session -> session.reps.count { it.isValidFor(session.skill) } }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Progress", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onViewProgress) {
                Text("View progress")
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp))
            }
        }

        StudentCard(Modifier.fillMaxWidth(), shape = DashboardShape) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (latestScore != null) ScoreArc(score = latestScore, diameter = 104.dp)
                else IconTile(Icons.Rounded.TrackChanges)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("LATEST FORM", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = when {
                            latestScore != null -> latest?.let { stringResource(it.skill.displayName) }.orEmpty()
                            sessions.isEmpty() -> "No sessions yet"
                            else -> "No measurable score"
                        },
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = when {
                            sessions.isEmpty() -> "Record a session to see your form here."
                            latestScore == null -> "Your latest session had no measurable form score."
                            firstScore != null && bestScore != null && scored.size >= 2 ->
                                "First session $firstScore · Best $bestScore"
                            else -> "Record another session to compare your form."
                        },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DashboardStat(sessions.size, "Sessions", Icons.Rounded.Schedule, revealed, Modifier.weight(1f))
            DashboardStat(validReps, "Valid reps", Icons.Rounded.SportsVolleyball, revealed, Modifier.weight(1f))
            DashboardStat(bestScore ?: 0, "Best form", Icons.Rounded.TrackChanges, revealed, Modifier.weight(1f),
                available = bestScore != null)
        }

        StudentCard(Modifier.fillMaxWidth(), shape = DashboardShape) {
            Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconTile(Icons.AutoMirrored.Rounded.TrendingUp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val change = learning.change
                    val cue = change?.cueId?.let(com.volleyballlab.player.domain.model.FormCueCatalog::byId)
                    if (change != null && cue != null) {
                        Text(stringResource(cue.label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("${change.currentInRange} of ${change.currentCount} measured reps met the practice range; previously ${change.previousInRange} of ${change.previousCount}.",
                            style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("Your progress", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("Complete more sessions with the same drill and camera views to see your progress.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("Valid reps meet the form target with enough measured technique. Practice ranges are provisional guidance.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DashboardStat(value: Int, label: String, icon: ImageVector, revealed: Boolean, modifier: Modifier,
    available: Boolean = true) {
    StudentCard(modifier, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(34.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .08f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            if (available) CountUp(value, revealed, MaterialTheme.typography.headlineSmall)
            else Text("—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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
                Text(if (recommendation == null) "Build your starting plan"
                    else stringResource(recommendation.skill.displayName),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(if (recommendation == null) "A few short cards to find a useful starting practice."
                    else stringResource(recommendation.skill.description),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                if (recommendation != null) {
                    if (recommendation.isMeasured) Text(stringResource(R.string.home_skill_level, recommendation.level.number, stringResource(recommendation.level.label)),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if (savedGoal) Text("From your saved training goal", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f))
                    Text(if (recommendation.isMeasured) stringResource(R.string.home_focus_source_measured) else "Based on your answers",
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
