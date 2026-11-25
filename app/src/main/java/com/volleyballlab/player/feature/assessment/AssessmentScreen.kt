package com.volleyballlab.player.feature.assessment

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.feature.home.ScoreBar
import com.volleyballlab.player.feature.home.ThinBar
import com.volleyballlab.player.ui.component.SkillAccentRule
import com.volleyballlab.player.ui.component.skillVisual
import com.volleyballlab.player.ui.component.studentBackground
import com.volleyballlab.player.ui.theme.LocalReduceMotion
import com.volleyballlab.player.ui.theme.VbMotion

/** What is on screen. The page alone is not enough: adding a skill puts a new card where the end was. */
private data class Shown(val page: Int, val step: AssessmentStep)

@Composable
fun AssessmentScreen(onFinished: () -> Unit, contentPadding: PaddingValues, modifier: Modifier = Modifier, model: AssessmentViewModel = viewModel(factory = AssessmentViewModel.Factory)) {
    val state by model.state.collectAsStateWithLifecycle()
    val result = state.kind == AssessmentPage.Result
    val still = LocalReduceMotion.current
    Column(modifier.fillMaxSize().studentBackground().padding(contentPadding).imePadding()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.assessment_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                if (!result) TextButton(onClick = { model.onSkip(onFinished) }, enabled = !state.busy) { Text(stringResource(R.string.skill_check_skip)) }
            }
            if (!result) ScoreBar(state.progress, state.skill?.let { skillVisual(it).accent } ?: MaterialTheme.colorScheme.primary, ThinBar)
        }
        AnimatedContent(
            targetState = Shown(state.page, AssessmentStep(state.kind, state.statement)),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                val forward = if (targetState.page < initialState.page) -1 else 1
                (slideInHorizontally(VbMotion.tween(reduceMotion = still)) { it / 4 * forward } + fadeIn(VbMotion.tween(reduceMotion = still))) togetherWith
                    (slideOutHorizontally(VbMotion.tween(reduceMotion = still)) { -it / 4 * forward } + fadeOut(VbMotion.tween(VbMotion.Short, reduceMotion = still)))
            },
            contentKey = { it.step },
            label = "assessmentPage",
        ) { shown ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                when (shown.step.kind) {
                    AssessmentPage.About -> AboutPage(state, model)
                    AssessmentPage.Goals -> GoalsPage(state, model)
                    AssessmentPage.Time -> TimePage(state, model)
                    AssessmentPage.Card -> shown.step.statement?.let { CharacteristicCard(state, it, model) }
                    AssessmentPage.Finish -> FinishPage(state, model)
                    AssessmentPage.Result -> AssessmentResult(state.result, state.answers, state.drills, state.planner, model::retryPlan)
                }
                if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.page > 0 && !result) OutlinedButton(onClick = model::onBack, enabled = !state.busy) { Text(stringResource(R.string.skill_check_back)) }
            Button(onClick = { model.onNext(onFinished) }, enabled = state.canAdvance, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(when (state.kind) {
                    AssessmentPage.Result -> R.string.assessment_done
                    AssessmentPage.Finish -> R.string.assessment_submit
                    else -> R.string.skill_check_next
                }))
            }
        }
    }
}

@Composable
private fun AboutPage(state: AssessmentUiState, model: AssessmentViewModel) {
    Heading(stringResource(R.string.assessment_about), stringResource(R.string.assessment_about_body))
    Question(stringResource(R.string.assessment_experience)) {
        ExperienceLevel.entries.forEach { ChoiceCard(stringResource(it.label), state.experience == it, !state.busy, { model.onExperience(it) }) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.assessment_position), style = MaterialTheme.typography.titleMedium)
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(stringResource(R.string.assessment_position_none), state.position == null, !state.busy) { model.onPosition(null) }
            CourtPosition.entries.forEach { Choice(stringResource(it.displayName), state.position == it, !state.busy) { model.onPosition(it) } }
        }
    }
}

@Composable
private fun GoalsPage(state: AssessmentUiState, model: AssessmentViewModel) {
    Heading(stringResource(R.string.assessment_goals), stringResource(R.string.assessment_goals_body))
    Question(stringResource(R.string.assessment_preferred), stringResource(R.string.assessment_preferred_body), group = false) {
        state.order.forEach { skill ->
            ChoiceCard(stringResource(skill.displayName), skill in state.preferred, !state.busy, { model.onPreferred(skill) }, accent = skillVisual(skill).accent,
                body = stringResource(skill.description), multiple = true) { SkillAccentRule(skill, height = 36) }
        }
    }
    Question(stringResource(R.string.assessment_goal)) {
        MainGoal.entries.forEach { goal ->
            ChoiceCard(goal.label, state.goal == goal, !state.busy, { model.onGoal(goal) }) { IconDisc(goalIcon(goal), MaterialTheme.colorScheme.primary) }
        }
    }
    Note(state.goalNote, stringResource(R.string.skill_check_goal), !state.busy, model::onGoalNote)
}

@Composable
private fun TimePage(state: AssessmentUiState, model: AssessmentViewModel) {
    Heading(stringResource(R.string.skill_check_schedule), stringResource(R.string.skill_check_schedule_body))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.assessment_days), style = MaterialTheme.typography.titleMedium)
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..7).forEach { DayDot(it, state.days == it, it < state.days, !state.busy) { model.onDays(it) } }
        }
    }
    Question(stringResource(R.string.assessment_minutes)) {
        AssessmentAnswers.MINUTES.forEach { ChoiceCard(stringResource(R.string.assessment_minutes_value, it), state.minutes == it, !state.busy, { model.onMinutes(it) }) }
    }
    Text(stringResource(R.string.assessment_time_total, state.days * state.minutes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** What was said, and the chance to add any other skill. */
@Composable
private fun FinishPage(state: AssessmentUiState, model: AssessmentViewModel) {
    Heading(stringResource(R.string.assessment_cards), stringResource(R.string.assessment_cards_body))
    state.assessed.forEach { SkillProfileCard(it, state, model) }
    if (state.others.isNotEmpty()) Question(stringResource(R.string.assessment_add), stringResource(R.string.assessment_add_body), group = false) {
        state.others.forEach { skill -> AddSkillCard(skill, SkillCheck.isAnswered(skill, state.statements), !state.busy) { model.onAddSkill(skill) } }
    }
    Note(state.weakNote, stringResource(R.string.assessment_weak_note), !state.busy, model::onWeakNote)
    Text(stringResource(R.string.assessment_ai_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Question(title: String, body: String? = null, group: Boolean = true, choices: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(if (group) Modifier.selectableGroup() else Modifier, verticalArrangement = Arrangement.spacedBy(10.dp), content = choices)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) =
    FilterChip(selected = selected, enabled = enabled, onClick = onClick, label = { Text(label) })

@Composable
private fun Note(value: String, label: String, enabled: Boolean, onChange: (String) -> Unit) =
    OutlinedTextField(value = value, onValueChange = onChange, enabled = enabled, label = { Text(label) },
        supportingText = { Text("${value.length}/${AssessmentAnswers.MAX_NOTE}") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)

@Composable
private fun Heading(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
