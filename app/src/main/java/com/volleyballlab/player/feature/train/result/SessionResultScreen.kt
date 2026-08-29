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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.domain.model.CueUnit
import com.volleyballlab.player.domain.model.FormCue
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.repository.SessionRepository
import com.volleyballlab.player.ui.component.EntranceAnimation
import com.volleyballlab.player.ui.component.ScoreArc
import com.volleyballlab.player.ui.component.scoreColor
import com.volleyballlab.player.ui.theme.VbButtonShape
import com.volleyballlab.player.ui.theme.VbMotion
import com.volleyballlab.player.ui.theme.vbTween
import java.util.Locale
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
            contentPadding = contentPadding,
            modifier = modifier,
        )
    }
}

@Composable
private fun ResultContent(
    state: ResultUiState.Ready,
    onDone: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val session = state.session
    val score = session.formScore()

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

            itemsIndexed(state.summaries) { index, summary ->
                EntranceAnimation(index = index + 3) {
                    CueRow(
                        summary = summary,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
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
                    Text(stringResource(R.string.result_save))
                }
            }
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
