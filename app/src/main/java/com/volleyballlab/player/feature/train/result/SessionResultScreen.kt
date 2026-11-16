package com.volleyballlab.player.feature.train.result

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import com.volleyballlab.player.ui.theme.VbTabularNumerals
import com.volleyballlab.player.ui.theme.vbColors
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.R
import com.volleyballlab.player.feature.coach.AssignmentResultActions
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.domain.model.CueUnit
import com.volleyballlab.player.domain.model.CoachAssessmentEngine
import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.FormCue
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.SessionAngleStage
import com.volleyballlab.player.domain.repository.SessionRepository
import com.volleyballlab.player.ui.component.EntranceAnimation
import com.volleyballlab.player.ui.component.ScoreArc
import com.volleyballlab.player.ui.component.scoreColor
import com.volleyballlab.player.ui.theme.VbButtonShape
import com.volleyballlab.player.ui.theme.VbMotion
import com.volleyballlab.player.ui.theme.vbTween
import java.util.Locale
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * One cue, aggregated across every rep in the session.
 *
 * [meanMeasured] is kept alongside [meanScore] so the UI can say what the
 * athlete's elbow angle actually was, not merely that the cue scored 62%. A
 * percentage tells them they were wrong; a number and a target tells them what to
 * change.
 */
data class CueSummary(
    val cue: FormCue,
    val meanScore: Float?,
    val meanMeasured: Float?,
    val measuredReps: Int,
)

sealed interface ResultUiState {
    data object Loading : ResultUiState
    data object NotFound : ResultUiState
    data class Ready(
        val session: SessionResult,
        val summaries: List<CueSummary>,
        val history: List<SessionResult> = emptyList(),
    ) : ResultUiState
}

class SessionResultViewModel(
    private val sessionRepository: SessionRepository,
    private val sessionId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ResultUiState>(ResultUiState.Loading)
    val uiState: StateFlow<ResultUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val session = sessionRepository.byId(sessionId)
            _uiState.value = if (session == null) {
                ResultUiState.NotFound
            } else {
                ResultUiState.Ready(session, summarise(session), sessionRepository.sessions.first())
            }
        }
    }

    /**
     * Averages each cue over the reps where it was actually measurable.
     *
     * Reps where a cue could not be read are excluded from both averages rather
     * than counted as zero, matching how
     * [com.volleyballlab.player.domain.model.RepResult.formScore] renormalises.
     * [CueSummary.measuredReps] carries how many reps contributed, so the UI can
     * be honest about a cue that was only readable twice out of ten.
     */
    private fun summarise(session: SessionResult): List<CueSummary> =
        FormCueCatalog.poseCues(session.skill).map { cue ->
            val scores = session.reps
                .flatMap { it.cueScores }
                .filter { it.cueId == cue.id && it.score != null }

            CueSummary(
                cue = cue,
                meanScore = scores.mapNotNull { it.score }
                    .takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                meanMeasured = scores.mapNotNull { it.measured }
                    .takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                measuredReps = scores.size,
            )
        }

    fun deleteVideo() {
        val ready = _uiState.value as? ResultUiState.Ready ?: return
        if (ready.session.videoPath == null) return
        viewModelScope.launch {
            try {
                sessionRepository.deleteVideo(sessionId)
                _uiState.value = ready.copy(session = ready.session.copy(videoPath = null))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the recording visible when local deletion fails.
            }
        }
    }

    companion object {
        fun factory(sessionId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SessionResultViewModel(appContainer.sessionRepository, sessionId)
            }
        }
    }
}

@Composable
fun SessionResultScreen(
    sessionId: String,
    onDone: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    assignmentId: String? = null,
    doneLabel: String? = null,
) {
    val viewModel: SessionResultViewModel = viewModel(
        key = "result-$sessionId",
        factory = SessionResultViewModel.factory(sessionId),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    when (val current = state) {
        ResultUiState.Loading -> Box(
            modifier = modifier.fillMaxWidth().padding(48.dp),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        ResultUiState.NotFound -> Box(
            modifier = modifier.fillMaxWidth().padding(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.result_no_reps_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }

        is ResultUiState.Ready -> ResultContent(
            state = current,
            onDone = onDone,
            onDeleteVideo = viewModel::deleteVideo,
            contentPadding = contentPadding,
            modifier = modifier,
            assignmentId = assignmentId ?: current.session.assignmentId,
            doneLabel = doneLabel,
        )
    }
}

@Composable
private fun ResultContent(
    state: ResultUiState.Ready,
    onDone: () -> Unit,
    onDeleteVideo: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    assignmentId: String? = null,
    doneLabel: String? = null,
) {
    val session = state.session
    val assessment = remember(session, state.history) { CoachAssessmentEngine.assess(session, state.history) }
    val score = assessment.overallScore
    val playableVideo = session.videoPath?.takeIf { File(it).isFile }
    var confirmDeleteVideo by remember { mutableStateOf(false) }
    val stages = session.angleStages.sortedBy { it.stageIndex }
    val missingViews = if (stages.isEmpty()) emptyList() else {
        val captured = stages.filter { it.liveRepCount > 0 }.map { it.angle }.toSet()
        listOf(CameraAngle.Front, CameraAngle.Side).filter { it !in captured }
    }
    val side = Modifier.padding(horizontal = 20.dp)

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            EntranceAnimation(index = 0) {
                ResultHero(session, score, stages, missingViews, side.padding(top = 12.dp))
            }
        }

        item { EntranceAnimation(index = 1) { SessionReportCard(session.repCount, assessment, side) } }

        if (stages.isNotEmpty()) item {
            ResultSection(
                title = stringResource(R.string.result_angle_summary_title),
                caption = stringResource(R.string.result_angle_summary_body),
                modifier = side,
            ) {
                ResultPanel {
                    stages.forEachIndexed { index, stage ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        AngleStageRow(session, stage)
                    }
                }
            }
        }

        item {
            ResultSection(title = stringResource(R.string.result_review_video), modifier = side) {
                if (playableVideo != null) {
                    AnnotatedSessionVideo(session, playableVideo)
                    TextButton(onClick = { confirmDeleteVideo = true }, modifier = Modifier.align(Alignment.End)) {
                        Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.session_delete_video), color = MaterialTheme.colorScheme.error)
                    }
                } else ResultPanel {
                    Text(
                        text = stringResource(R.string.result_video_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        if (score != null && state.summaries.isNotEmpty()) item {
            EntranceAnimation(index = 2) {
                ResultSection(title = stringResource(R.string.result_breakdown), modifier = side) {
                    ResultPanel {
                        state.summaries.forEachIndexed { index, summary ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            CueRow(summary)
                        }
                    }
                }
            }
        }

        item { SessionCoachFeedback(session.id, side) }

        if (assignmentId != null) {
            item { AssignmentResultActions(assignmentId, session, side) }
        }

        item {
            EntranceAnimation(index = 3) {
                Column(side.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = onDone,
                        shape = VbButtonShape,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Text(doneLabel ?: if (assignmentId != null) "Back to assignment" else stringResource(R.string.result_save))
                    }
                    PoseBenchmarkDisclosure()
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }

    if (confirmDeleteVideo) {
        AlertDialog(
            onDismissRequest = { confirmDeleteVideo = false },
            title = { Text(stringResource(R.string.session_delete_video_title)) },
            text = { Text(stringResource(R.string.session_delete_video_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteVideo = false
                        onDeleteVideo()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.session_delete_video_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteVideo = false }) {
                    Text(stringResource(R.string.session_delete_video_cancel))
                }
            },
        )
    }
}

/** The one thing to read first: the score, then the three counts that explain it. */
@Composable
private fun ResultHero(
    session: SessionResult,
    score: Int?,
    stages: List<SessionAngleStage>,
    missingViews: List<CameraAngle>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                if (score != null) ScoreArc(score = score, diameter = 124.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.coach_assessment_score).uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(session.skill.displayName),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = pluralStringResource(R.plurals.result_reps_detected, session.repCount, session.repCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (score == null) Text(
                        text = stringResource(R.string.result_score_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (stages.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(stages.sumOf { it.liveRepCount }.toString(), stringResource(R.string.result_stat_completed), Modifier.weight(1f))
                StatTile(stages.sumOf { it.validRepCount }.toString(), stringResource(R.string.capture_good_reps), Modifier.weight(1f))
                StatTile(stages.maxOf { it.bestStreak }.toString(), stringResource(R.string.result_stat_best_run), Modifier.weight(1f))
            }
            if (missingViews.isNotEmpty()) Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.vbColors.warningContainer,
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = "Session incomplete: ${missingViews.joinToString { it.name.lowercase() }} view not captured",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge.merge(VbTabularNumerals), fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

@Composable
private fun ResultSection(
    title: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            caption?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        content()
    }
}

@Composable
private fun ResultPanel(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) { Column(content = content) }
}

@Composable
private fun AngleStageRow(session: SessionResult, stage: SessionAngleStage) {
    // Do not attribute a repetition that crosses a turning/countdown boundary.
    val finalReps = session.reps.filter {
        it.startMs >= stage.startMs && it.endMs <= stage.endMs
    }
    val scores = finalReps.mapNotNull { it.formScore(session.skill) }
    val finalScore = scores.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
    val angleLabel = stringResource(
        when (stage.angle) {
            CameraAngle.Front -> R.string.result_angle_front
            CameraAngle.Side -> R.string.result_angle_side
            CameraAngle.Rear -> R.string.result_angle_rear
            CameraAngle.Unknown -> R.string.result_angle_unknown
        }
    )

    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(angleLabel, style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.result_view_reps, stage.validRepCount, stage.liveRepCount, stage.bestStreak),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stage.estimatedFormScore?.let { stringResource(R.string.result_angle_live_score, it) }
                    ?: stringResource(R.string.result_angle_live_unassessed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = finalScore?.toString() ?: "—",
                style = MaterialTheme.typography.headlineSmall.merge(VbTabularNumerals),
                fontWeight = FontWeight.Bold,
                color = finalScore?.let { scoreColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = pluralStringResource(R.plurals.result_view_final, finalReps.size, finalReps.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CueRow(summary: CueSummary, modifier: Modifier = Modifier) {
    val percent = ((summary.meanScore ?: 0f) * 100).roundToInt()
    val animatedProgress by animateFloatAsState(
        targetValue = summary.meanScore ?: 0f,
        animationSpec = vbTween(
            durationMillis = VbMotion.Long,
            easing = VbMotion.EmphasizedDecelerate,
        ),
        label = "cueProgress",
    )

    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(summary.cue.label),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (summary.meanScore != null) "$percent" else "—",
                style = MaterialTheme.typography.titleMedium.merge(VbTabularNumerals),
                fontWeight = FontWeight.SemiBold,
                color = if (summary.meanScore != null) scoreColor(percent) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (summary.meanScore != null) LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = scoreColor(percent),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Text(
            text = measurementLine(summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * "132 deg, target 155-180 deg", or an honest note when nothing was measurable.
 */
@Composable
private fun measurementLine(summary: CueSummary): String {
    val measured = summary.meanMeasured
        ?: return stringResource(R.string.result_cue_unmeasured)
    return stringResource(
        R.string.result_measured_vs_target,
        formatValue(measured, summary.cue.unit),
        formatRange(summary.cue),
    )
}

private fun formatValue(value: Float, unit: CueUnit): String = when (unit) {
    CueUnit.Degrees -> String.format(Locale.US, "%.0f°", value)
    CueUnit.Metres -> String.format(Locale.US, "%.2f m", value)
    CueUnit.Ratio -> String.format(Locale.US, "%.0f%%", value * 100)
}

private fun formatRange(cue: FormCue): String {
    val from = formatValue(cue.target.start, cue.unit)
    val to = formatValue(cue.target.endInclusive, cue.unit)
    return "$from - $to"
}
