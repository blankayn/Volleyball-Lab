package com.volleyballlab.player.feature.assessment

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.ui.component.StudentCard

@Composable
fun AssessmentScreen(onFinished: () -> Unit, contentPadding: PaddingValues, modifier: Modifier = Modifier, model: AssessmentViewModel = viewModel(factory = AssessmentViewModel.Factory)) {
    val state by model.state.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    LaunchedEffect(state.page) { scroll.scrollTo(0) }
    val result = state.kind == AssessmentPage.Result
    Column(modifier.fillMaxSize().padding(contentPadding).imePadding()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.assessment_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                if (!result) TextButton(onClick = { model.onSkip(onFinished) }, enabled = !state.busy) { Text(stringResource(R.string.skill_check_skip)) }
            }
            if (!result) {
                Text(stringResource(R.string.skill_check_step, state.page + 1, state.pages - 1), style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            when (state.kind) {
                AssessmentPage.About -> AboutPage(state, model)
                AssessmentPage.Goals -> GoalsPage(state, model)
                AssessmentPage.Time -> TimePage(state, model)
                AssessmentPage.Skill -> SkillPage(state, model)
                AssessmentPage.Weak -> WeakPage(state, model)
                AssessmentPage.Result -> AssessmentResult(state.result, state.answers, state.drills, state.planner, model::retryPlan)
            }
            if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.page > 0 && !result) OutlinedButton(onClick = model::onBack, enabled = !state.busy) { Text(stringResource(R.string.skill_check_back)) }
            Button(onClick = { model.onNext(onFinished) }, enabled = state.canAdvance, modifier = Modifier.weight(1f)) {
                Text(stringResource(when (state.kind) {
                    AssessmentPage.Result -> R.string.assessment_done
                    AssessmentPage.Weak -> R.string.assessment_submit
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
        ExperienceLevel.entries.forEach { Choice(stringResource(it.label), state.experience == it, !state.busy) { model.onExperience(it) } }
    }
    Question(stringResource(R.string.assessment_position)) {
        Choice(stringResource(R.string.assessment_position_none), state.position == null, !state.busy) { model.onPosition(null) }
        CourtPosition.entries.forEach { Choice(stringResource(it.displayName), state.position == it, !state.busy) { model.onPosition(it) } }
    }
}

@Composable
private fun GoalsPage(state: AssessmentUiState, model: AssessmentViewModel) {
    Heading(stringResource(R.string.assessment_goals), stringResource(R.string.assessment_goals_body))
    Question(stringResource(R.string.assessment_preferred)) {
        Skill.All.forEach { Choice(stringResource(it.displayName), it in state.preferred, !state.busy) { model.onPreferred(it) } }
    }
    Question(stringResource(R.string.assessment_goal)) {
        MainGoal.entries.forEach { Choice(it.label, state.goal == it, !state.busy) { model.onGoal(it) } }
    }
    Note(state.goalNote, stringResource(R.string.skill_check_goal), !state.busy, model::onGoalNote)
}

@Composable
private fun TimePage(state: AssessmentUiState, model: AssessmentViewModel) {
    Heading(stringResource(R.string.skill_check_schedule), stringResource(R.string.skill_check_schedule_body))
    Question(stringResource(R.string.assessment_days)) {
        (1..7).forEach { Choice("$it", state.days == it, !state.busy) { model.onDays(it) } }
    }
    Question(stringResource(R.string.assessment_minutes)) {
        AssessmentAnswers.MINUTES.forEach { Choice(stringResource(R.string.assessment_minutes_value, it), state.minutes == it, !state.busy) { model.onMinutes(it) } }
    }
}

@Composable
private fun SkillPage(state: AssessmentUiState, model: AssessmentViewModel) {
    val skill = state.skill ?: return
    Heading(stringResource(skill.displayName), stringResource(R.string.skill_check_body))
    SkillCheck.forSkill(skill).forEach { statement ->
        StudentCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(statement.text, style = MaterialTheme.typography.bodyLarge)
                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CanDo.entries.forEach { Choice(it.label, state.statements[statement.id] == it, !state.busy) { model.onAnswer(statement.id, it) } }
                }
            }
        }
    }
}

@Composable
private fun WeakPage(state: AssessmentUiState, model: AssessmentViewModel) {
    Heading(stringResource(R.string.assessment_weak), stringResource(R.string.assessment_weak_body))
    Question(stringResource(R.string.assessment_weak_choose)) {
        WeakPoint.entries.forEach { Choice(it.label, it in state.weakPoints, !state.busy) { model.onWeakPoint(it) } }
    }
    Note(state.weakNote, stringResource(R.string.assessment_weak_note), !state.busy, model::onWeakNote)
    Text(stringResource(R.string.assessment_ai_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Question(title: String, choices: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { choices() }
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
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
