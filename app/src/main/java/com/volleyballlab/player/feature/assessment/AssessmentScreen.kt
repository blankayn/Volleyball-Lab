package com.volleyballlab.player.feature.assessment

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.CourtPosition
import com.volleyballlab.player.domain.model.DominantHand
import com.volleyballlab.player.domain.model.ExperienceLevel
import com.volleyballlab.player.domain.model.SelfRating
import com.volleyballlab.player.ui.component.skillVisual
import com.volleyballlab.player.ui.theme.VbButtonShape
import com.volleyballlab.player.ui.theme.VbMotion
import com.volleyballlab.player.ui.theme.vbTween

/**
 * The onboarding questionnaire.
 *
 * ## What this is, and what it deliberately is not
 *
 * It is three screens of self-report, and everything it produces is an opinion the
 * athlete offered before the app had seen them play. It is not a measurement, and
 * nothing downstream should present it as one - the dashboard card it feeds names
 * its source on its face for exactly that reason.
 *
 * Its job is narrow: give a brand-new install something better than nothing to
 * recommend. The app's real analysis needs recorded reps, and on the first launch
 * there are none.
 *
 * ## Why Skip is on every step
 *
 * `AthleteProfile` states that "the app is fully usable with all of them empty", and
 * gating the entire app behind a form the first time it opens would make that false.
 * Skipping is recorded as having been onboarded, so it is asked once, not forever.
 */
@Composable
fun AssessmentScreen(
    onFinished: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val viewModel: AssessmentViewModel = viewModel(factory = AssessmentViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Read here, in composable scope: AnimatedContent's transitionSpec is a plain
    // lambda and cannot call vbTween, which reads the reduce-motion CompositionLocal.
    val stepFade = vbTween<Float>(VbMotion.Short)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.assessment_step_counter,
                        state.step.ordinal + 1,
                        AssessmentStep.All.size,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { viewModel.onSkip(onFinished) }) {
                    Text(stringResource(R.string.assessment_skip))
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
            )
        }

        AnimatedContent(
            targetState = state.step,
            transitionSpec = { fadeIn(stepFade) togetherWith fadeOut(stepFade) },
            label = "assessmentStep",
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) { step ->
            Column(Modifier.padding(horizontal = 20.dp)) {
                when (step) {
                    AssessmentStep.Position -> PositionStep(state, viewModel)
                    AssessmentStep.Experience -> ExperienceStep(state, viewModel)
                    AssessmentStep.Ratings -> RatingsStep(state, viewModel)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.step != AssessmentStep.Position) {
                TextButton(
                    onClick = viewModel::onBack,
                    shape = VbButtonShape,
                ) {
                    Text(stringResource(R.string.assessment_back))
                }
            }
            Button(
                onClick = { viewModel.onNext(onFinished) },
                enabled = state.canAdvance,
                shape = VbButtonShape,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(
                        if (state.isLastStep) R.string.assessment_finish else R.string.assessment_next,
                    ),
                )
            }
        }
    }
}

@Composable
private fun StepHeading(title: Int, body: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun PositionStep(state: AssessmentUiState, viewModel: AssessmentViewModel) {
    StepHeading(R.string.assessment_position_title, R.string.assessment_position_body)

    Text(
        text = stringResource(R.string.profile_position_label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CourtPosition.entries.forEach { position ->
            FilterChip(
                selected = state.position == position,
                onClick = { viewModel.onPositionSelected(position) },
                label = { Text(stringResource(position.displayName)) },
            )
        }
    }

    Spacer(Modifier.height(22.dp))
    Text(
        text = stringResource(R.string.profile_hand_label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DominantHand.entries.forEach { hand ->
            FilterChip(
                selected = state.hand == hand,
                onClick = { viewModel.onHandSelected(hand) },
                label = { Text(stringResource(hand.displayName)) },
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.assessment_hand_note),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ExperienceStep(state: AssessmentUiState, viewModel: AssessmentViewModel) {
    StepHeading(R.string.assessment_experience_title, R.string.assessment_experience_body)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ExperienceLevel.entries.forEach { level ->
            val selected = state.experience == level
            Surface(
                onClick = { viewModel.onExperienceSelected(level) },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            ) {
                Text(
                    text = stringResource(level.label),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                )
            }
        }
    }
}

/**
 * One row per skill, each a three-way choice.
 *
 * Only the skills the athlete's position actually uses appear here - see
 * [AssessmentUiState.skillsToRate]. A libero is asked three questions, and is never
 * put in the position of rating themselves at blocking, which the rules do not let
 * them do.
 */
@Composable
private fun RatingsStep(state: AssessmentUiState, viewModel: AssessmentViewModel) {
    StepHeading(R.string.assessment_ratings_title, R.string.assessment_ratings_body)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        state.skillsToRate.forEach { skill ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(skillVisual(skill).accent)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(skill.displayName),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelfRating.entries.forEach { rating ->
                        FilterChip(
                            selected = state.ratings[skill] == rating,
                            onClick = { viewModel.onRatingSelected(skill, rating) },
                            label = { Text(stringResource(rating.label)) },
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.assessment_ratings_note),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
}
