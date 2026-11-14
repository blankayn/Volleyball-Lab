package com.volleyballlab.player.feature.progress

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.R
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.feature.session.RecentSessionSummaryCard
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.model.WeeklyProgress
import com.volleyballlab.player.domain.model.DailyProgress
import com.volleyballlab.player.domain.repository.SessionRepository
import com.volleyballlab.player.ui.component.StudentCard
import com.volleyballlab.player.ui.component.StudentPageHeading
import com.volleyballlab.player.ui.component.studentBackground
import com.volleyballlab.player.ui.component.EntranceAnimation
import com.volleyballlab.player.ui.component.GhostLayout
import com.volleyballlab.player.ui.component.GhostLoadingScreen
import com.volleyballlab.player.ui.component.VbSectionHeader
import com.volleyballlab.player.ui.component.scoreColor
import com.volleyballlab.player.ui.component.SkillAccentRule
import com.volleyballlab.player.ui.theme.VbTabularNumerals
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import com.volleyballlab.player.ui.component.calendarDayFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Everything the Progress tab shows, derived from stored sessions.
 *
 * Computed in one place rather than in the composables so the screen renders a
 * value it was handed instead of recomputing five aggregates over the session
 * list on every recomposition - and so the arithmetic can be read without
 * reading layout code around it.
 *
 * @param trend scores oldest-first, which is the opposite of how sessions are
 *   stored. A chart that reads right-to-left would be a lie about which way time
 *   runs.
 */
data class ProgressSummary(
    val sessionCount: Int,
    val repCount: Int,
    val meanScore: Int?,
    val bestScore: Int?,
    val trend: List<Int>,
    val bySkill: List<SkillProgress>,
    val history: List<SessionResult>,
    val loaded: Boolean = false,
    val week: List<DailyProgress> = emptyList(),
) {
    val hasSessions: Boolean get() = sessionCount > 0

    companion object {
        /** Nothing recorded yet. */
        val Empty = ProgressSummary(0, 0, null, null, emptyList(), emptyList(), emptyList())

        /** How many sessions the trend chart shows. Beyond this the bars stop being readable. */
        const val TREND_LENGTH = 10

        fun from(sessions: List<SessionResult>, today: LocalDate = LocalDate.now()): ProgressSummary {
            if (sessions.isEmpty()) return Empty.copy(loaded = true, week = WeeklyProgress.from(sessions, today))

            val scores = sessions.mapNotNull { it.formScore() }
            // Oldest first for the chart, and only the tail of the history.
            val trend = sessions
                .sortedBy { it.startedAtEpochMs }
                .mapNotNull { it.formScore() }
                .takeLast(TREND_LENGTH)

            val bySkill = sessions
                .groupBy { it.skill }
                .map { (skill, forSkill) ->
                    val skillScores = forSkill.mapNotNull { it.formScore() }
                    SkillProgress(
                        skill = skill,
                        sessionCount = forSkill.size,
                        repCount = forSkill.sumOf { it.repCount },
                        meanScore = skillScores.takeIf { it.isNotEmpty() }
                            ?.average()?.roundToInt(),
                        bestScore = skillScores.maxOrNull(),
                        lastPracticedEpochMs = forSkill.maxOfOrNull { it.startedAtEpochMs },
                    )
                }
                // Most-trained first: the ordering answers "what have I actually
                // been working on", which alphabetical order does not.
                .sortedByDescending { it.sessionCount }

            return ProgressSummary(
                sessionCount = sessions.size,
                repCount = sessions.sumOf { it.repCount },
                meanScore = scores.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                bestScore = scores.maxOrNull(),
                trend = trend,
                bySkill = bySkill,
                history = sessions,
                loaded = true,
                week = WeeklyProgress.from(sessions, today),
            )
        }
    }
}

data class SkillProgress(
    val skill: Skill,
    val sessionCount: Int,
    val repCount: Int,
    val meanScore: Int?,
    val bestScore: Int?,
    val lastPracticedEpochMs: Long?,
)

class ProgressViewModel(sessionRepository: SessionRepository) : ViewModel() {

    val summary: StateFlow<ProgressSummary> = combine(sessionRepository.sessions, calendarDayFlow()) { sessions, today -> ProgressSummary.from(sessions, today) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ProgressSummary.Empty,
        )

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { ProgressViewModel(appContainer.sessionRepository) }
        }
    }
}

/**
 * The Progress tab.
 *
 * ## What is real here
 *
 * Every number on this screen is computed from sessions the athlete actually
 * recorded on this device. There is no sample data and no placeholder chart: with
 * no sessions stored the screen says so and offers the way to fix that, because a
 * chart of invented numbers is indistinguishable from a chart of real ones and
 * the difference matters most in exactly the setting this app gets shown in.
 *
 * Sessions come from the repository so this screen reflects persisted local
 * history and any sessions synchronized to the current account.
 */
@Composable
fun ProgressScreen(
    onStartTraining: () -> Unit,
    onOpenSession: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val viewModel: ProgressViewModel = viewModel(factory = ProgressViewModel.Factory)
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    var selectedSkillName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedSkill = selectedSkillName?.let { name -> Skill.entries.firstOrNull { it.name == name } }
        ?: SkillScoreProgress.defaultSkill(summary.history)
    val skillProgress = remember(summary.history, selectedSkill) {
        SkillScoreProgress.from(selectedSkill, summary.history)
    }

    if (!summary.loaded) {
        GhostLoadingScreen(GhostLayout.Progress, contentPadding, modifier)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().studentBackground(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            EntranceAnimation(index = 0) {
                ProgressHeader(summary)
            }
        }

        item { ProgressCalendarCard(summary.history, onOpenSession, onStartTraining, Modifier.padding(horizontal = 20.dp)) }

        if (!summary.hasSessions) {
            item {
                EntranceAnimation(index = 1) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        VbSectionHeader(
                            text = stringResource(R.string.personal_progress_title),
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                        PersonalProgressCard(
                            progress = skillProgress,
                            onSelectSkill = { selectedSkillName = it.name },
                            onOpenSession = onOpenSession,
                            onStartTraining = onStartTraining,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }
            }
            return@LazyColumn
        }

        item {
            EntranceAnimation(index = 1) {
                StatTiles(summary, Modifier.padding(horizontal = 20.dp))
            }
        }

        item {
            EntranceAnimation(index = 2) {
                VbSectionHeader(
                    text = stringResource(R.string.personal_progress_title),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        item {
            PersonalProgressCard(
                progress = skillProgress,
                onSelectSkill = { selectedSkillName = it.name },
                onOpenSession = onOpenSession,
                onStartTraining = onStartTraining,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        item {
            EntranceAnimation(index = 4) {
                VbSectionHeader(
                    text = stringResource(R.string.progress_section_by_skill),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        itemsIndexed(summary.bySkill, key = { _, skill -> skill.skill.name }) { index, skill ->
            EntranceAnimation(index = index + 5) {
                SkillProgressRow(skill, Modifier.padding(horizontal = 20.dp))
            }
        }

        item {
            EntranceAnimation(index = 0) {
                VbSectionHeader(
                    text = stringResource(R.string.progress_section_history),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        itemsIndexed(summary.history, key = { _, session -> session.id }) { index, session ->
            EntranceAnimation(index = index) {
                RecentSessionSummaryCard(session, { onOpenSession(session.id) }, Modifier.padding(horizontal = 20.dp))
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ProgressHeader(summary: ProgressSummary) {
    StudentPageHeading(
        eyebrow = "YOUR TRAINING RECORD",
        title = stringResource(R.string.nav_progress),
        subtitle = if (summary.hasSessions) {
                stringResource(
                    R.string.progress_summary_line,
                    summary.sessionCount,
                    summary.repCount,
                )
            } else {
                stringResource(R.string.progress_summary_none)
            },
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun StatTiles(summary: ProgressSummary, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatTile(
            label = stringResource(R.string.progress_stat_sessions),
            value = summary.sessionCount.toString(),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = stringResource(R.string.progress_stat_reps),
            value = summary.repCount.toString(),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = stringResource(R.string.progress_stat_average),
            value = summary.meanScore?.toString() ?: NO_VALUE,
            valueColor = summary.meanScore?.let { scoreColor(it) },
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = stringResource(R.string.progress_stat_best),
            value = summary.bestScore?.toString() ?: NO_VALUE,
            valueColor = summary.bestScore?.let { scoreColor(it) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
) {
    StudentCard(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.merge(VbTabularNumerals),
                color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Plain score comparison above a compact, directly reviewable session chart. */
@Composable
internal fun PersonalProgressCard(
    progress: SkillScoreProgress,
    onSelectSkill: (Skill) -> Unit,
    onOpenSession: (String) -> Unit,
    onStartTraining: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val skillLabel = stringResource(progress.skill.displayName)
    StudentCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Skill.All.forEach { skill ->
                    FilterChip(
                        selected = progress.skill == skill,
                        onClick = { onSelectSkill(skill) },
                        label = { Text(stringResource(skill.displayName)) },
                    )
                }
            }
            val latest = progress.latest
            if (latest == null) {
                Text(stringResource(R.string.personal_progress_no_score, skillLabel),
                    style = MaterialTheme.typography.titleMedium)
                Button(onClick = onStartTraining) {
                    Text(stringResource(R.string.personal_progress_first_session))
                }
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ScoreSummaryValue(stringResource(R.string.personal_progress_latest), latest.score)
                    progress.previous?.let {
                        ScoreSummaryValue(stringResource(R.string.personal_progress_previous), it.score)
                    }
                }
                val change = progress.change
                Text(
                    text = when {
                        change == null -> stringResource(R.string.personal_progress_starting)
                        change > 0 -> stringResource(R.string.personal_progress_higher, change, skillLabel)
                        change < 0 -> stringResource(R.string.personal_progress_lower, -change, skillLabel)
                        else -> stringResource(R.string.personal_progress_same, skillLabel)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = if (progress.points.size == 1) {
                        stringResource(R.string.personal_progress_recent_one, skillLabel)
                    } else stringResource(R.string.personal_progress_recent, progress.points.size, skillLabel),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SessionScoreBars(progress, onOpenSession)
                Text(stringResource(R.string.personal_progress_chart_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.personal_progress_focus), style = MaterialTheme.typography.titleSmall)
                Text(
                    progress.focusCue?.let { stringResource(it.coachingTip) }
                        ?: stringResource(R.string.personal_progress_more_evidence),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.personal_progress_score_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ScoreSummaryValue(label: String, score: Int) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.personal_progress_points, score),
            style = MaterialTheme.typography.headlineMedium.merge(VbTabularNumerals))
    }
}

/** Each complete column is a real button with an accessible date and score. */
@Composable
private fun SessionScoreBars(progress: SkillScoreProgress, onOpenSession: (String) -> Unit) {
    val scroll = rememberScrollState()
    val chartColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val skillLabel = stringResource(progress.skill.displayName)
    LaunchedEffect(progress.skill, progress.points, scroll.maxValue) {
        scroll.scrollTo(scroll.maxValue)
    }
    // Horizontal scrolling keeps every session target at least 48dp wide when
    // the device is narrow or the athlete uses larger text.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(IntrinsicSize.Min).height(CHART_HEIGHT)) {
            listOf("100" to Alignment.TopStart, "50" to Alignment.CenterStart, "0" to Alignment.BottomStart)
                .forEach { (label, alignment) ->
                    Text(label, modifier = Modifier.align(alignment),
                        style = MaterialTheme.typography.labelSmall.merge(VbTabularNumerals),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
        }
        Row(
            Modifier.weight(1f).horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            progress.points.forEachIndexed { index, point ->
                val date = Instant.ofEpochMilli(point.startedAtEpochMs).atZone(ZoneId.systemDefault())
                val fullDate = date.format(SessionDateTime)
                val description = stringResource(R.string.personal_progress_bar, skillLabel, fullDate, point.score)
                Column(
                    modifier = Modifier.width(48.dp)
                        .clickable(role = Role.Button) { onOpenSession(point.sessionId) }
                        .clearAndSetSemantics {
                            contentDescription = description
                            role = Role.Button
                            onClick { onOpenSession(point.sessionId); true }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Canvas(Modifier.fillMaxWidth().height(CHART_HEIGHT)) {
                        listOf(0f, 0.5f, 1f).forEach { fraction ->
                            val y = size.height * fraction
                            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                        }
                        // A thin baseline marker makes a real zero visible;
                        // missing measurements never enter this chart.
                        val height = (size.height * point.score / 100f).coerceAtLeast(2.dp.toPx())
                        val width = 24.dp.toPx()
                        drawRoundRect(
                            color = chartColor.copy(alpha = if (index == progress.points.lastIndex) 1f else 0.55f),
                            topLeft = Offset((size.width - width) / 2, size.height - height),
                            size = Size(width, height),
                            cornerRadius = CornerRadius(minOf(6.dp.toPx(), height / 2)),
                        )
                    }
                    Text(point.score.toString(), style = MaterialTheme.typography.labelLarge.merge(VbTabularNumerals),
                        modifier = Modifier.padding(top = 5.dp))
                    Text(date.format(ChartDate), style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SkillProgressRow(progress: SkillProgress, modifier: Modifier = Modifier) {
    val best = progress.bestScore?.let { stringResource(R.string.progress_skill_best, it) }
    val last = progress.lastPracticedEpochMs?.let { epochMs ->
        stringResource(
            R.string.progress_skill_last,
            Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
                .format(SkillDate),
        )
    }
    val details = listOfNotNull(best, last).joinToString(" · ")
    StudentCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkillAccentRule(skill = progress.skill, height = 44)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(progress.skill.displayName),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.home_sessions_count,
                        progress.sessionCount,
                        progress.sessionCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (details.isNotEmpty()) {
                    Text(
                        text = details,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (progress.meanScore != null) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress.meanScore / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = scoreColor(progress.meanScore),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = progress.meanScore?.toString() ?: NO_VALUE,
                style = MaterialTheme.typography.titleMedium.merge(VbTabularNumerals),
                color = progress.meanScore?.let { scoreColor(it) }
                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val CHART_HEIGHT = 96.dp
private val ChartDate = DateTimeFormatter.ofPattern("d/M")
private val SessionDateTime = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")

private val SkillDate = DateTimeFormatter.ofPattern("d MMM")

private const val NO_VALUE = "—"
