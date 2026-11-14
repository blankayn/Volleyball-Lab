package com.volleyballlab.player.feature.assessment

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.ui.component.StudentCard

@Composable
fun AssessmentScreen(onFinished: () -> Unit, contentPadding: PaddingValues, modifier: Modifier = Modifier, model: AssessmentViewModel = viewModel(factory = AssessmentViewModel.Factory)) {
    val state by model.state.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    LaunchedEffect(state.step) { scroll.scrollTo(0) }
    Column(modifier.fillMaxSize().padding(contentPadding).imePadding()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.aiEnabled) "AI-guided setup" else "Quick setup", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { model.onSkip(onFinished) }, enabled = !state.busy) { Text("Skip") }
            }
            Text("Let's build your starting plan", style = MaterialTheme.typography.headlineSmall)
            Text("Card ${state.step.ordinal + 1} of 5", style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (state.step) {
                AssessmentStep.Goal -> {
                    Heading("What would you like to work on?", "Choose a skill or tell us what feels difficult.")
                    SkillChoices(state.goal, { model.onGoalSelected(it) }, !state.busy, state.goalChosen)
                    FilterChip(selected = state.goalChosen && state.goal == null, onClick = { model.onGoalSelected(null) }, enabled = !state.busy, label = { Text("I'm not sure yet") })
                    OutlinedTextField(value = state.note, onValueChange = model::onNoteChanged, enabled = !state.busy,
                        label = { Text("Describe your goal (optional)") }, placeholder = { Text("My serves keep going out") },
                        supportingText = { Text("English, Filipino, or Taglish · ${state.note.length}/160") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)
                    StudentCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("How personalization works", style = MaterialTheme.typography.titleSmall)
                            Text("Continue with AI to send these setup answers to our AI service and Groq for a relevant question and starting suggestion. We don't add your account details, scores, or recordings. Please leave personal and contact details out of your answer.", style = MaterialTheme.typography.bodySmall)
                            Text("Only the plan you confirm is saved to your account. You can choose quick setup instead.", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { model.useQuickSetup(); model.onNext(onFinished) }, enabled = !state.busy) { Text("Use quick setup") }
                        }
                    }
                }
                AssessmentStep.Experience -> {
                    Heading("How much have you played?", "This is your own experience, not a test of your ability.")
                    ExperienceLevel.entries.forEach { level ->
                        Choice(stringResource(level.label), state.experience == level, !state.busy) { model.onExperienceSelected(level) }
                    }
                    Text("You can add your position and dominant hand later in Profile.", style = MaterialTheme.typography.bodySmall)
                }
                AssessmentStep.FollowUp -> {
                    val follow = state.followUp
                    val question = TrainingSetupQuestions.question(follow?.questionId.orEmpty())
                    if (!follow?.acknowledgment.isNullOrBlank()) Text(follow!!.acknowledgment, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Heading(question?.prompt ?: "What would help you get started?", "Pick the answer that sounds most like you.")
                    question?.options?.forEach { option -> Choice(option.label, state.optionId == option.id, !state.busy) { model.onOptionSelected(option.id) } }
                }
                AssessmentStep.Schedule -> {
                    Heading("How often would you like to practice?", "Choose a realistic weekly target. You can change it later.")
                    WeeklyChoices(state.weeklyGoal, model::onWeeklyGoalSelected, !state.busy)
                }
                AssessmentStep.Review -> {
                    val suggestion = state.suggestion
                    if (suggestion != null) {
                        Heading("Your starting plan", "Based on your answers")
                        Text(suggestion.summary, style = MaterialTheme.typography.titleMedium)
                        Text(suggestion.reason, style = MaterialTheme.typography.bodyMedium)
                        Text("Your focus", style = MaterialTheme.typography.titleSmall)
                        SkillChoices(suggestion.focusSkill, model::onReviewGoalSelected, !state.busy)
                        Text("Practices per week", style = MaterialTheme.typography.titleSmall)
                        WeeklyChoices(state.weeklyGoal, model::onReviewWeeklyGoalSelected, !state.busy)
                        val graph = (LocalContext.current.applicationContext as VolleyballLabApplication).container
                        val drill by produceState<Drill?>(null, suggestion.focusSkill) { value = graph.drillRepository.byId(TrainingSetupQuestions.starterDrill(suggestion.focusSkill)) }
                        StudentCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Your first drill · Free", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text(drill?.name ?: "Loading starter drill…", style = MaterialTheme.typography.titleMedium)
                                drill?.let { Text("${it.estimatedMinutes} minutes · ${it.targetReps} repetitions", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        Text("This is a starting suggestion, not a measured skill level. Your recorded sessions will show your form feedback.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (state.busy) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text(if (state.step == AssessmentStep.Review) "Saving your plan…" else "Getting your next card ready…", style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.step != AssessmentStep.Goal) OutlinedButton(onClick = model::onBack, enabled = !state.busy) { Text("Back") }
            Button(onClick = { model.onNext(onFinished) }, enabled = state.canAdvance, modifier = Modifier.weight(1f)) {
                Text(when { state.step == AssessmentStep.Review -> "Save my plan"; state.step == AssessmentStep.Goal && state.aiEnabled -> "Continue with AI"; else -> "Next" })
            }
        }
    }
}

@Composable
private fun Heading(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable
private fun SkillChoices(selected: Skill?, onSelected: (Skill) -> Unit, enabled: Boolean, hasSelection: Boolean = true) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Skill.All.forEach { skill -> FilterChip(selected = hasSelection && selected == skill, enabled = enabled, onClick = { onSelected(skill) }, label = { Text(stringResource(skill.displayName)) }) }
    }
}
@Composable
private fun WeeklyChoices(selected: Int, onSelected: (Int) -> Unit, enabled: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (listOf(1, 2, 3, 4, 5, 6, 7) + if (selected > 7) listOf(selected) else emptyList()).forEach { count ->
            FilterChip(selected = selected == count, enabled = enabled, onClick = { onSelected(count) }, label = { Text("$count / week") })
        }
    }
}
@Composable
private fun Choice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, enabled = enabled, onClick = onClick, label = { Text(label, Modifier.padding(vertical = 8.dp)) }, modifier = Modifier.fillMaxWidth())
}
