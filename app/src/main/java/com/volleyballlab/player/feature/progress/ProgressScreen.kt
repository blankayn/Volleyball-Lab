package com.volleyballlab.player.feature.progress

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.volleyballlab.player.ui.theme.vbColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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
                    NoDataCard(
                        onStartTraining = onStartTraining,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            return@LazyColumn
        }

        item {
            EntranceAnimation(index = 1) {
                StatTiles(summary, Modifier.padding(horizontal = 20.dp))
            }
        }

        if (summary.trend.size >= MIN_TREND_POINTS) {
            item {
                EntranceAnimation(index = 2) {
                    VbSectionHeader(
                        text = stringResource(R.string.progress_section_trend),
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            item {
                EntranceAnimation(index = 3) {
                    TrendCard(summary.trend, Modifier.padding(horizontal = 20.dp))
                }
            }
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

/**
 * Form score over the last few sessions, as bars.
 *
 * Bars rather than a line, because the sessions are discrete events at uneven
 * intervals; a line implies the score moved continuously between two Tuesdays,
 * and its slope invites reading a rate of improvement out of what is really two
 * data points. Each bar is scored on the full 0-100 axis rather than a zoomed one
 * so that a run of 70s does not look like wild swings.
 */
@Composable
private fun TrendCard(trend: List<Int>, modifier: Modifier = Modifier) {
    val latest = trend.last()
    val previous = trend[trend.lastIndex - 1]
    val delta = latest - previous
    val average = trend.average().roundToInt()
    val chartColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val averageColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val chartDescription = stringResource(
        R.string.progress_chart_description,
        trend.size,
        trend.joinToString(", "),
        latest,
    )

    StudentCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
            Text(
                text = stringResource(R.string.progress_chart_recent, trend.size).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.progress_chart_latest_score),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = latest.toString(),
                            style = MaterialTheme.typography.displaySmall.merge(VbTabularNumerals),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.progress_chart_out_of),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 3.dp, bottom = 5.dp),
                        )
                    }
                }
                val deltaColor = when {
                    delta > 0 -> MaterialTheme.vbColors.onSuccessContainer
                    delta < 0 -> MaterialTheme.colorScheme.onErrorContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                StudentCard(
                    color = when {
                        delta > 0 -> MaterialTheme.vbColors.successContainer
                        delta < 0 -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    shape = CircleShape,
                ) {
                    Text(
                        text = when {
                            delta > 0 -> stringResource(R.string.progress_chart_change_up, delta)
                            delta < 0 -> stringResource(R.string.progress_chart_change_down, delta)
                            else -> stringResource(R.string.progress_chart_change_flat)
                        },
                        style = MaterialTheme.typography.labelSmall.merge(VbTabularNumerals),
                        color = deltaColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.height(CHART_HEIGHT).width(28.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    listOf("100", "50", "0").forEach { tick ->
                        Text(
                            text = tick,
                            style = MaterialTheme.typography.labelSmall.merge(VbTabularNumerals),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(CHART_HEIGHT)
                        .semantics { contentDescription = chartDescription },
                ) {
                    listOf(0f, 0.5f, 1f).forEach { fraction ->
                        val y = size.height * fraction
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    val averageY = size.height * (1f - average / 100f)
                    drawLine(
                        color = averageColor,
                        start = Offset(0f, averageY),
                        end = Offset(size.width, averageY),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(5.dp.toPx(), 5.dp.toPx())
                        ),
                    )

                    val slotWidth = size.width / trend.size
                    val barWidth = minOf(slotWidth * 0.56f, 36.dp.toPx())
                    trend.forEachIndexed { index, score ->
                        if (score == 0) return@forEachIndexed
                        val barHeight = size.height * score.coerceIn(0, 100) / 100f
                        val left = slotWidth * (index + 0.5f) - barWidth / 2f
                        drawRoundRect(
                            color = chartColor.copy(alpha = if (index == trend.lastIndex) 1f else 0.50f),
                            topLeft = Offset(left, size.height - barHeight),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(
                                minOf(barWidth / 3f, barHeight / 2f)
                            ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().padding(start = 28.dp)) {
                Text(
                    text = stringResource(R.string.progress_trend_oldest),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.progress_trend_newest),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(width = 20.dp, height = 2.dp)) {
                    drawLine(
                        color = averageColor,
                        start = Offset.Zero,
                        end = Offset(size.width, 0f),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(5.dp.toPx(), 5.dp.toPx())
                        ),
                    )
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    text = stringResource(R.string.progress_chart_average, average),
                    style = MaterialTheme.typography.labelSmall.merge(VbTabularNumerals),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

@Composable
private fun NoDataCard(onStartTraining: () -> Unit, modifier: Modifier = Modifier) {
    StudentCard(
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
                text = stringResource(R.string.progress_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.progress_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            androidx.compose.material3.Button(
                onClick = onStartTraining,
                shape = com.volleyballlab.player.ui.theme.VbButtonShape,
            ) {
                Text(stringResource(R.string.progress_empty_cta))
            }
        }
    }
}

/** Fewer bars than this is not a trend, it is a number with a rectangle around it. */
private const val MIN_TREND_POINTS = 2

private val CHART_HEIGHT = 154.dp

private val SkillDate = DateTimeFormatter.ofPattern("d MMM")

private const val NO_VALUE = "—"
