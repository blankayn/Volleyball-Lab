package com.volleyballlab.player.feature.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SportsVolleyball
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.model.SkillRecommendation
import com.volleyballlab.player.domain.model.TrainingTotals
import com.volleyballlab.player.ui.component.AnimatedCounter
import com.volleyballlab.player.ui.component.EntranceAnimation
import com.volleyballlab.player.ui.component.RiveMascot
import com.volleyballlab.player.ui.component.SkillAccentRule
import com.volleyballlab.player.ui.component.VbSectionHeader
import com.volleyballlab.player.ui.component.pressScale
import com.volleyballlab.player.ui.component.scoreColor
import com.volleyballlab.player.ui.component.skillVisual
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    onStartTraining: () -> Unit,
    onSkillSelected: (Skill) -> Unit,
    onTakeAssessment: () -> Unit,
    onViewAllSessions: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var period by remember { mutableStateOf(TrainingPeriod.ThisWeek) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            EntranceAnimation(index = 0) {
                HeroHeader(name = state.profile.name, sessions = state.sessions)
            }
        }

        item {
            EntranceAnimation(index = 1) {
                StartTrainingCard(
                    onClick = onStartTraining,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }

        // ── Focus suggestion ─────────────────────────────────────────────
        item {
            EntranceAnimation(index = 2) {
                val recommendation = state.recommendation
                if (recommendation == null) {
                    TakeAssessmentCard(
                        onClick = onTakeAssessment,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                } else {
                    FocusSuggestionCard(
                        recommendation = recommendation,
                        onClick = { onSkillSelected(recommendation.skill) },
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
        }

        // ── Skill focus ──────────────────────────────────────────────────
        state.recommendation?.let { recommendation ->
            item {
                EntranceAnimation(index = 3) {
                    VbSectionHeader(
                        text = stringResource(R.string.home_section_skill_focus),
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            item {
                EntranceAnimation(index = 4) {
                    SkillFocusCard(
                        recommendation = recommendation,
                        onClick = { onSkillSelected(recommendation.skill) },
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
        }

        // ── Recent sessions ──────────────────────────────────────────────
        item {
            EntranceAnimation(index = 5) {
                SectionHeaderWithAction(
                    title = R.string.home_section_recent,
                    action = R.string.home_view_all,
                    onAction = onViewAllSessions.takeIf { state.sessions.isNotEmpty() },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }

        if (state.sessions.isEmpty()) {
            item {
                EntranceAnimation(index = 6) {
                    EmptySessionsCard(modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
        } else {
            itemsIndexed(
                state.sessions.take(RECENT_SESSION_LIMIT),
                key = { _, session -> session.id },
            ) { index, session ->
                EntranceAnimation(index = index + 6) {
                    RecentSessionRow(
                        session = session,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
        }

        // ── Daily progress ───────────────────────────────────────────────
        item {
            EntranceAnimation(index = 7) {
                DailyProgressCard(
                    totals = TrainingTotals.from(period.filter(state.sessions)),
                    period = period,
                    onPeriodChange = { period = it },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

// ─── Hero Header ────────────────────────────────────────────────────────────

@Composable
private fun HeroHeader(name: String, sessions: List<SessionResult>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                // The greeting takes the athlete's first name only. "Good
                // afternoon, Kirt Deniel Magno" reads like a summons.
                text = name.trim().substringBefore(' ')
                    .takeIf { it.isNotBlank() }
                    ?.let { stringResource(greetingRes(), it) }
                    ?: stringResource(greetingPlainRes()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = summaryLine(sessions),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        RiveMascot(modifier = Modifier.size(112.dp))
    }
}

// ─── Start Training Card ────────────────────────────────────────────────────

@Composable
private fun StartTrainingCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val infiniteTransition = rememberInfiniteTransition(label = "ctaPulse")
    val arrowOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "arrowPulse",
    )

    val primary = MaterialTheme.colorScheme.primary
    val gradientBrush = Brush.horizontalGradient(
        colors = listOf(primary, primary.copy(alpha = 0.75f)),
    )

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interactionSource),
        shape = MaterialTheme.shapes.large,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(brush = gradientBrush)
                .padding(22.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_start_training),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.home_start_training_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(32.dp)
                        .graphicsLayer { translationX = arrowOffset * density },
                )
            }
        }
    }
}

// ─── Focus suggestion ───────────────────────────────────────────────────────

/**
 * What to work on next, and where the claim came from.
 *
 * ## The source line is not decoration
 *
 * The card carries either "From your assessment" or "From your recorded sets", and
 * that distinction is the honest part of this feature. Conflict 8 in the capstone
 * document is unresolved - Objective 4 calls Coach Mode AI personalisation, every
 * other section calls it a human coach - and the Coach tab already refuses to dress
 * its output up as a person for that reason. This card is held to the same standard.
 * A recommendation drawn from a questionnaire the athlete filled in during
 * onboarding is a restatement of their own opinion, and presenting it in the same
 * voice as a measurement would be the app taking credit for knowing something it
 * does not.
 */
@Composable
private fun FocusSuggestionCard(
    recommendation: SkillRecommendation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val visual = skillVisual(recommendation.skill)

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interactionSource),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_focus_suggestion),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.home_focus_improve),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = stringResource(recommendation.skill.displayName),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = visual.accent,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(recommendation.skill.description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        if (recommendation.isMeasured) {
                            R.string.home_focus_source_measured
                        } else {
                            R.string.home_focus_source_assessment
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(14.dp))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(visual.wash),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.TrackChanges,
                    contentDescription = null,
                    tint = visual.accent,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

/** Shown instead of a suggestion when onboarding was skipped. */
@Composable
private fun TakeAssessmentCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interactionSource),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_assessment_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.home_assessment_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ─── Skill focus ────────────────────────────────────────────────────────────

@Composable
private fun SkillFocusCard(
    recommendation: SkillRecommendation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val visual = skillVisual(recommendation.skill)

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interactionSource),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(visual.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.SportsVolleyball,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(recommendation.skill.displayName),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(recommendation.skill.description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.home_skill_level,
                        recommendation.level.number,
                        stringResource(recommendation.level.label),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = visual.accent,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── Daily progress ─────────────────────────────────────────────────────────

/**
 * Minutes trained, sets captured and accuracy, over a period the athlete picks.
 *
 * ## On "accuracy"
 *
 * This is the mean form score across the period's sessions - the same number the
 * results screen and the session rows show, averaged. It is worth being deliberate
 * about the word: the capstone document uses "accuracy" for MPJPE, which is the
 * *pose model's* accuracy against ground-truth joint positions. That is a different
 * quantity with a different unit, and the two should not be confused in the write-up
 * even though both appear in the same project.
 */
@Composable
private fun DailyProgressCard(
    totals: TrainingTotals,
    period: TrainingPeriod,
    onPeriodChange: (TrainingPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_section_daily_progress),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            PeriodSelector(period = period, onPeriodChange = onPeriodChange)
        }
        Spacer(Modifier.height(10.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(
                modifier = Modifier.padding(vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatTile(
                    icon = Icons.Rounded.Schedule,
                    tint = MinutesColor,
                    value = totals.totalMinutes,
                    label = R.string.home_stat_minutes,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    icon = Icons.Rounded.SportsVolleyball,
                    tint = MaterialTheme.colorScheme.primary,
                    value = totals.sessionCount,
                    label = R.string.home_stat_sets,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    icon = Icons.Rounded.TrackChanges,
                    tint = AccuracyColor,
                    // Null accuracy is a dash, not a zero. Nothing measurable in the
                    // period is not the same as having been measured at nought.
                    value = totals.averageScore,
                    label = R.string.home_stat_accuracy,
                    suffix = "%",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StatTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    value: Int?,
    label: Int,
    modifier: Modifier = Modifier,
    suffix: String = "",
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.height(8.dp))
        if (value == null) {
            Text(
                text = stringResource(R.string.home_stat_unavailable),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                AnimatedCounter(
                    value = value,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                )
                if (suffix.isNotEmpty()) {
                    Text(
                        text = suffix,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PeriodSelector(period: TrainingPeriod, onPeriodChange: (TrainingPeriod) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .pressScale(remember { MutableInteractionSource() })
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = { expanded = true },
                color = Color.Transparent,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(period.label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        imageVector = Icons.Rounded.ArrowDropDown,
                        contentDescription = stringResource(R.string.home_change_period),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TrainingPeriod.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.label)) },
                    onClick = {
                        onPeriodChange(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

// ─── Recent sessions ────────────────────────────────────────────────────────

@Composable
private fun SectionHeaderWithAction(
    title: Int,
    action: Int,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VbSectionHeader(text = stringResource(title))
        if (onAction != null) {
            Surface(onClick = onAction, color = Color.Transparent) {
                Text(
                    text = stringResource(action),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun RecentSessionRow(session: SessionResult, modifier: Modifier = Modifier) {
    val score = session.formScore()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkillAccentRule(skill = session.skill, height = 40)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(session.skill.displayName),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = formatSessionSubtitle(session),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (score != null) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .border(
                            width = 2.5.dp,
                            color = scoreColor(score),
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = score.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = scoreColor(score),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptySessionsCard(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.home_no_sessions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ─── Helpers ────────────────────────────────────────────────────────────────

private fun greetingRes(): Int {
    val hour = LocalTime.now().hour
    return when {
        hour < 12 -> R.string.home_greeting_morning_named
        hour < 18 -> R.string.home_greeting_afternoon_named
        else -> R.string.home_greeting_evening_named
    }
}

private fun greetingPlainRes(): Int {
    val hour = LocalTime.now().hour
    return when {
        hour < 12 -> R.string.home_greeting_morning
        hour < 18 -> R.string.home_greeting_afternoon
        else -> R.string.home_greeting_evening
    }
}

@Composable
private fun summaryLine(sessions: List<SessionResult>): String {
    if (sessions.isEmpty()) return stringResource(R.string.home_no_streak)
    val scores = sessions.mapNotNull { it.formScore() }
    return if (scores.isEmpty()) {
        pluralStringResource(R.plurals.home_sessions_count, sessions.size, sessions.size)
    } else {
        stringResource(R.string.home_avg_score, scores.average().toInt())
    }
}

private val sessionDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")

private fun formatSessionSubtitle(session: SessionResult): String {
    val time = Instant.ofEpochMilli(session.startedAtEpochMs)
        .atZone(ZoneId.systemDefault())
        .format(sessionDateFormat)
    return "${session.repCount} reps  ·  $time"
}

/** Long enough to be a summary, short enough that Progress still has a job. */
private const val RECENT_SESSION_LIMIT = 3

private val MinutesColor = Color(0xFF2563EB)
private val AccuracyColor = Color(0xFF0FA06B)
