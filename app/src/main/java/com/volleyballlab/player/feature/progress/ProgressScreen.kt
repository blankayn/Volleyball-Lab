package com.volleyballlab.player.feature.progress

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.repository.SessionRepository
import com.volleyballlab.player.ui.component.EntranceAnimation
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
import kotlinx.coroutines.flow.map
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
) {
    val hasSessions: Boolean get() = sessionCount > 0

    companion object {
        /** Nothing recorded yet. */
        val Empty = ProgressSummary(0, 0, null, null, emptyList(), emptyList(), emptyList())

        /** How many sessions the trend chart shows. Beyond this the bars stop being readable. */
        const val TREND_LENGTH = 10

        fun from(sessions: List<SessionResult>): ProgressSummary {
            if (sessions.isEmpty()) return Empty

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
            )
        }
    }
}

data class SkillProgress(
    val skill: Skill,
    val sessionCount: Int,
    val repCount: Int,
    val meanScore: Int?,
)

class ProgressViewModel(sessionRepository: SessionRepository) : ViewModel() {

    val summary: StateFlow<ProgressSummary> = sessionRepository.sessions
        .map(ProgressSummary::from)
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
 * ## What it cannot show yet
 *
 * Sessions live in `InMemorySessionRepository` pending conflict 4 in the capstone
 * document (Firestore versus the relational data dictionary), so history resets
 * when the process dies. Everything here reads the repository interface, so
 * whichever store wins, this screen is unaffected.
 */
@Composable
fun ProgressScreen(
    onStartTraining: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val viewModel: ProgressViewModel = viewModel(factory = ProgressViewModel.Factory)
    val summary by viewModel.summary.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            EntranceAnimation(index = 0) {
                ProgressHeader(summary)
            }
        }

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
        itemsIndexed(summary.bySkill) { index, skill ->
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
        itemsIndexed(summary.history) { index, session ->
            EntranceAnimation(index = index) {
                HistoryRow(session, Modifier.padding(horizontal = 20.dp))
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ProgressHeader(summary: ProgressSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.nav_progress),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = if (summary.hasSessions) {
                stringResource(
                    R.string.progress_summary_line,
                    summary.sessionCount,
                    summary.repCount,
                )
            } else {
                stringResource(R.string.progress_summary_none)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
    Surface(
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
    val barColors = trend.map { scoreColor(it) }
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(18.dp)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            ) {
                val count = trend.size
                val gap = size.width * BAR_GAP_FRACTION / count
                val barWidth = (size.width - gap * (count - 1)) / count
                val radius = CornerRadius(barWidth / 3f, barWidth / 3f)

                trend.forEachIndexed { index, score ->
                    val left = index * (barWidth + gap)
                    // The track is drawn first at full height so a low score
                    // reads as "short bar in a tall slot" rather than as a
                    // missing bar.
                    drawRoundRect(
                        color = trackColor,
                        topLeft = Offset(left, 0f),
                        size = Size(barWidth, size.height),
                        cornerRadius = radius,
                    )
                    val barHeight = size.height * (score / 100f)
                    drawRoundRect(
                        color = barColors[index],
                        topLeft = Offset(left, size.height - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = radius,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
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
        }
    }
}

@Composable
private fun SkillProgressRow(progress: SkillProgress, modifier: Modifier = Modifier) {
    Surface(
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
private fun HistoryRow(session: SessionResult, modifier: Modifier = Modifier) {
    val score = session.formScore()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(session.skill.displayName),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = formatHistorySubtitle(session),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (score != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .border(2.dp, scoreColor(score), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = score.toString(),
                        style = MaterialTheme.typography.bodyLarge.merge(VbTabularNumerals),
                        color = scoreColor(score),
                    )
                }
            } else {
                // An unscored session is not a failure and is not hidden: it is
                // a session where no cue could be measured, which is a camera
                // problem the athlete can act on.
                Text(
                    text = stringResource(R.string.progress_unscored),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NoDataCard(onStartTraining: () -> Unit, modifier: Modifier = Modifier) {
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

/** Total horizontal space given to gaps, as a fraction of the chart width. */
private const val BAR_GAP_FRACTION = 0.35f

private const val NO_VALUE = "—"

private val historyDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")

private fun formatHistorySubtitle(session: SessionResult): String {
    val time = Instant.ofEpochMilli(session.startedAtEpochMs)
        .atZone(ZoneId.systemDefault())
        .format(historyDateFormat)
    return "${session.repCount} reps  ·  $time"
}
