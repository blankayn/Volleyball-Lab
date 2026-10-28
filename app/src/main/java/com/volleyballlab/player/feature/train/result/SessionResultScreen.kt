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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.res.pluralStringResource
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
                ResultUiState.Ready(session, summarise(session))
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
        FormCueCatalog.forSkill(session.skill).map { cue ->
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
    val score = session.formScore()
    val playableVideo = session.videoPath?.takeIf { File(it).isFile }
    var confirmDeleteVideo by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            EntranceAnimation(index = 0) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Text(
                        text = stringResource(session.skill.displayName),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    if (score != null) {
                        ScoreArc(
                            score = score,
                            label = stringResource(R.string.result_form_score),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = pluralStringResource(
                                R.plurals.result_reps_detected,
                                session.repCount,
                                session.repCount,
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        NoRepsCard()
                    }
                }
            }
        }

        if (session.angleStages.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Text(
                        text = stringResource(R.string.result_angle_summary_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.result_angle_summary_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(
                session.angleStages.sortedBy { it.stageIndex },
                key = { _, stage -> stage.stageIndex },
            ) { _, stage ->
                AngleStageCard(session, stage, Modifier.padding(horizontal = 20.dp))
            }
        }

        item { DetailedReportCard(session, Modifier.padding(horizontal = 20.dp)) }

        if (playableVideo != null) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Text(
                        text = stringResource(R.string.result_review_video),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    AnnotatedSessionVideo(session, playableVideo)
                    TextButton(onClick = { confirmDeleteVideo = true }) {
                        Text(
                            text = stringResource(R.string.session_delete_video),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        } else {
            item {
                Text(
                    text = stringResource(R.string.result_video_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                )
            }
        }

        if (score != null) {
            session.weakestCue()?.let { weakest ->
                item {
                    EntranceAnimation(index = 1) {
                        FocusCard(
                            cue = FormCueCatalog.byId(weakest),
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }
            }

            item {
                EntranceAnimation(index = 2) {
                    Text(
                        text = stringResource(R.string.result_breakdown),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                    )
                }
            }

            itemsIndexed(state.summaries, key = { _, summary -> summary.cue.id }) { index, summary ->
                EntranceAnimation(index = index + 3) {
                    CueRow(
                        summary = summary,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
        }

        if (assignmentId != null) {
            item { AssignmentResultActions(assignmentId, session, Modifier.padding(horizontal = 20.dp)) }
        }

        item {
            EntranceAnimation(index = 8) {
                Button(
                    onClick = onDone,
                    shape = VbButtonShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(doneLabel ?: if (assignmentId != null) "Back to assignment" else stringResource(R.string.result_save))
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

@Composable
private fun AngleStageCard(
    session: SessionResult,
    stage: SessionAngleStage,
    modifier: Modifier = Modifier,
) {
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

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.result_angle_stage_title, stage.stageIndex + 1, angleLabel),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(
                    R.string.result_angle_live_reps, stage.validRepCount, stage.liveRepCount, stage.bestStreak,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stage.estimatedFormScore?.let {
                    stringResource(R.string.result_angle_live_score, it)
                } ?: stringResource(R.string.result_angle_live_unassessed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = finalScore?.let {
                    stringResource(R.string.result_angle_final_score, it, finalReps.size)
                } ?: stringResource(R.string.result_angle_final_unassessed, finalReps.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoRepsCard() {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.result_no_reps_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.result_no_reps_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FocusCard(cue: FormCue?, modifier: Modifier = Modifier) {
    if (cue == null) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.result_weakest_cue),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(cue.label),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(cue.coachingTip),
                style = MaterialTheme.typography.bodyMedium,
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

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(summary.cue.label),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (summary.meanScore != null) {
                    Text(
                        text = "$percent",
                        style = MaterialTheme.typography.titleMedium,
                        color = scoreColor(percent),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = scoreColor(percent),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = measurementLine(summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
