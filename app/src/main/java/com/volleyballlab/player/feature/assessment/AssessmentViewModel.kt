package com.volleyballlab.player.feature.assessment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.data.ai.CloudTrainingSetupRepository
import com.volleyballlab.player.data.ai.TrainingSetupPersonalizer
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.domain.repository.AssessmentRepository
import com.volleyballlab.player.domain.repository.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class AssessmentStep { Goal, Experience, FollowUp, Schedule, Review;
    companion object { val All = entries }
}

data class AssessmentUiState(
    val step: AssessmentStep = AssessmentStep.Goal,
    val goal: Skill? = null,
    val goalChosen: Boolean = false,
    val note: String = "",
    val experience: ExperienceLevel? = null,
    val weeklyGoal: Int = 3,
    val followUp: SetupFollowUp? = null,
    val optionId: String? = null,
    val suggestion: SetupSuggestion? = null,
    val aiEnabled: Boolean = true,
    val busy: Boolean = true,
    val error: String? = null,
) {
    val progress get() = (step.ordinal + 1) / 5f
    val canAdvance get() = !busy && when(step) {
        AssessmentStep.Goal -> goalChosen || note.isNotBlank()
        AssessmentStep.Experience -> experience != null
        AssessmentStep.FollowUp -> optionId != null
        AssessmentStep.Schedule -> weeklyGoal in 1..14
        AssessmentStep.Review -> suggestion != null
    }
    fun answers() = SetupAnswers(goal, experience ?: ExperienceLevel.New, note.trim(), followUp?.questionId, optionId, weeklyGoal)
}

/** Five bounded cards. One follow-up call and one summary call at most per opened setup. */
class AssessmentViewModel(
    private val assessmentRepository: AssessmentRepository,
    private val profileRepository: ProfileRepository,
    private val personalizer: TrainingSetupPersonalizer,
    private val currentUid: () -> String?,
) : ViewModel() {
    private val uid = currentUid()
    private val _state = MutableStateFlow(AssessmentUiState())
    val state = _state.asStateFlow()
    private var nextRequested = false
    private var summaryRequested = false
    init {
        viewModelScope.launch {
            try {
                val profile = profileRepository.profile.first()
                val existing = assessmentRepository.assessment.first()
                checkAccount()
                _state.update { it.copy(goal = profile.goalSkill, goalChosen = profile.goalSkill != null,
                    note = profile.goalText.take(160), experience = existing.experience,
                    weeklyGoal = profile.weeklySessionGoal.coerceIn(1, 14), busy = false) }
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { _state.update { it.copy(busy = false, error = "Could not load your saved choices. You can still use quick setup.", aiEnabled = false) } }
        }
    }
    private fun checkAccount() { check(uid != null && currentUid() == uid) { "Your account changed. Reopen setup to continue." } }
    private fun changeAnswers(change: (AssessmentUiState) -> AssessmentUiState) = _state.update {
        if (it.busy) it else change(it).copy(followUp = null, optionId = null, suggestion = null,
            aiEnabled = it.aiEnabled && !nextRequested && !summaryRequested, error = null)
    }
    fun onGoalSelected(skill: Skill?) = changeAnswers { it.copy(goal = skill, goalChosen = true) }
    fun onNoteChanged(text: String) = changeAnswers { it.copy(note = text.take(160)) }
    fun onExperienceSelected(level: ExperienceLevel) = changeAnswers { it.copy(experience = level) }
    fun onOptionSelected(id: String) { _state.update { if (it.busy) it else it.copy(optionId = id, suggestion = null, error = null) } }
    fun onWeeklyGoalSelected(value: Int) { _state.update { if (it.busy) it else it.copy(weeklyGoal = value.coerceIn(1, 14), suggestion = null) } }
    fun onReviewGoalSelected(skill: Skill) { _state.update {
        if (it.busy) it else it.copy(goal = skill, goalChosen = true, aiEnabled = false,
            followUp = SetupFollowUp(skill, TrainingSetupQuestions.forSkill(skill).id, "", "local"), optionId = "not_sure",
            suggestion = TrainingSetupQuestions.localSuggestion(it.answers().copy(goalSkill = skill)))
    } }
    fun onReviewWeeklyGoalSelected(value: Int) { _state.update {
        if (it.busy) it else it.copy(weeklyGoal = value.coerceIn(1, 14), suggestion = TrainingSetupQuestions.localSuggestion(it.answers().copy(goalSkill = it.suggestion?.focusSkill, weeklySessionGoal = value.coerceIn(1, 14))))
    } }
    fun useQuickSetup() { _state.update { if (it.busy) it else it.copy(aiEnabled = false, goalChosen = true) } }
    fun onBack() { _state.update { if (it.busy) it else it.copy(step = AssessmentStep.All[(it.step.ordinal - 1).coerceAtLeast(0)], error = null) } }
    fun onNext(onFinished: () -> Unit) {
        val state = _state.value
        if (!state.canAdvance) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                checkAccount()
                when (state.step) {
                    AssessmentStep.Goal -> _state.update { it.copy(step = AssessmentStep.Experience) }
                    AssessmentStep.Experience -> {
                        val answers = state.answers().copy(followUpQuestionId = null, followUpOptionId = null, weeklySessionGoal = null)
                        val follow = if (state.followUp != null) state.followUp else if (state.aiEnabled && !nextRequested) {
                            nextRequested = true
                            try { personalizer.nextCard(answers) }
                            catch (_: TimeoutCancellationException) { TrainingSetupQuestions.localFollowUp(answers) }
                            catch (failure: CancellationException) { throw failure }
                            catch (_: Exception) { TrainingSetupQuestions.localFollowUp(answers) }
                        } else TrainingSetupQuestions.localFollowUp(answers)
                        checkAccount()
                        _state.update { it.copy(step = AssessmentStep.FollowUp, followUp = follow, aiEnabled = it.aiEnabled && follow.source == "groq") }
                    }
                    AssessmentStep.FollowUp -> _state.update { it.copy(step = AssessmentStep.Schedule) }
                    AssessmentStep.Schedule -> {
                        val suggestion = state.suggestion ?: if (state.aiEnabled && !summaryRequested) {
                            summaryRequested = true
                            try { personalizer.summary(state.answers()) }
                            catch (_: TimeoutCancellationException) { TrainingSetupQuestions.localSuggestion(state.answers()) }
                            catch (failure: CancellationException) { throw failure }
                            catch (_: Exception) { TrainingSetupQuestions.localSuggestion(state.answers()) }
                        } else TrainingSetupQuestions.localSuggestion(state.answers())
                        checkAccount()
                        _state.update { it.copy(step = AssessmentStep.Review, suggestion = suggestion, aiEnabled = it.aiEnabled && suggestion.source == "groq") }
                    }
                    AssessmentStep.Review -> {
                        val suggestion = requireNotNull(state.suggestion)
                        assessmentRepository.saveTrainingSetup(TrainingSetup(
                            focusSkill = suggestion.focusSkill, weeklySessionGoal = state.weeklyGoal,
                            goalText = state.note.trim(), experience = state.experience,
                            concern = TrainingSetupQuestions.optionLabel(state.answers()), summary = suggestion.summary,
                            source = suggestion.source, starterDrillId = TrainingSetupQuestions.starterDrill(suggestion.focusSkill),
                            completedAtEpochMs = System.currentTimeMillis(), reason = suggestion.reason,
                            questionId = state.followUp?.questionId.orEmpty(), optionId = state.optionId.orEmpty(),
                        ))
                        checkAccount()
                        onFinished()
                    }
                }
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { _state.update { it.copy(error = if (currentUid() != uid) "Your account changed. Reopen setup to continue." else "Could not save your plan. Your choices are still here; please try again.") } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
    fun onSkip(onFinished: () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try { checkAccount(); assessmentRepository.skip(); checkAccount(); onFinished() }
            catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { _state.update { it.copy(error = "Could not finish setup. Check your account and try again.") } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory { initializer {
            AssessmentViewModel(appContainer.assessmentRepository, appContainer.profileRepository,
                CloudTrainingSetupRepository(), { appContainer.authRepository.currentUid })
        } }
    }
}
