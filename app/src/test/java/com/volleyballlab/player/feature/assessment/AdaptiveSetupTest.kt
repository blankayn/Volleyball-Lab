package com.volleyballlab.player.feature.assessment

import androidx.lifecycle.ViewModelStore
import com.volleyballlab.player.data.ai.*
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdaptiveSetupTest {
    private class Assessments : AssessmentRepository {
        override val assessment = MutableStateFlow(SkillAssessment(ratings = mapOf(Skill.Setting to SelfRating.Okay)))
        override val hasOnboarded = MutableStateFlow(false)
        var saved: TrainingSetup? = null
        override suspend fun save(assessment: SkillAssessment) { this.assessment.value = assessment }
        override suspend fun skip() { hasOnboarded.value = true }
        override suspend fun saveTrainingSetup(setup: TrainingSetup) { saved = setup }
    }
    private class Profiles : ProfileRepository {
        override val profile = MutableStateFlow(AthleteProfile(name = "Player", position = CourtPosition.Setter))
        override suspend fun save(profile: AthleteProfile) { this.profile.value = profile }
    }
    private class Personalizer : TrainingSetupPersonalizer {
        var calls = mutableListOf<String>()
        var failure = false
        var timeout = false
        var gate: CompletableDeferred<Unit>? = null
        var received: SetupAnswers? = null
        override suspend fun nextCard(answers: SetupAnswers): SetupFollowUp {
            calls += "next"; received = answers; gate?.await()
            if (timeout) withTimeout(1) { delay(2) }
            if (failure) error("offline")
            return SetupFollowUp(Skill.Serving, "serving_focus", "Let's find your serving focus.", "groq")
        }
        override suspend fun summary(answers: SetupAnswers): SetupSuggestion {
            calls += "summary"
            return SetupSuggestion(Skill.Serving, "Start with serving practice.", "You chose serving and contact.", "groq")
        }
    }
    private fun checkSetup(block: suspend TestScope.(AssessmentViewModel, Assessments, Personalizer, (String) -> Unit) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val assessments = Assessments(); val ai = Personalizer(); var uid = "player-a"
        val model = AssessmentViewModel(assessments, Profiles(), ai, { uid })
        store.put("setup", model)
        try { advanceUntilIdle(); block(model, assessments, ai) { uid = it } }
        finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    private suspend fun TestScope.followUp(model: AssessmentViewModel) {
        model.onGoalSelected(Skill.Serving); model.onNoteChanged("Laging lumalabas ang serve ko")
        model.onNext {}; advanceUntilIdle()
        model.onExperienceSelected(ExperienceLevel.New); model.onNext {}; advanceUntilIdle()
    }
    private suspend fun TestScope.review(model: AssessmentViewModel) {
        followUp(model); model.onOptionSelected("contact"); model.onNext {}; advanceUntilIdle()
        model.onWeeklyGoalSelected(3); model.onNext {}; advanceUntilIdle()
    }
    @Test fun fiveCardsSaveOnlyAfterConfirmationAndKeepExplicitRatings() = checkSetup { model, assessments, ai, _ ->
        review(model)
        assertEquals(AssessmentStep.Review, model.state.value.step)
        assertNull(assessments.saved)
        assertEquals(listOf("next", "summary"), ai.calls)
        assertEquals("Laging lumalabas ang serve ko", ai.received?.note)
        var finished = false; model.onNext { finished = true }; advanceUntilIdle()
        assertTrue(finished)
        assertEquals("serve-standing-float", assessments.saved?.starterDrillId)
        assertEquals(mapOf(Skill.Setting to SelfRating.Okay), assessments.assessment.value.ratings)
    }
    @Test fun offlineFollowUpUsesQuickSetupAndDoesNotRequestSummary() = checkSetup { model, _, ai, _ ->
        ai.failure = true; review(model)
        assertFalse(model.state.value.aiEnabled)
        assertEquals(listOf("next"), ai.calls)
        assertEquals("local", model.state.value.suggestion?.source)
    }
    @Test fun timedOutRequestFallsBackWithoutHoldingTheCard() = checkSetup { model, _, ai, _ ->
        ai.timeout = true; followUp(model)
        assertEquals(AssessmentStep.FollowUp, model.state.value.step)
        assertFalse(model.state.value.aiEnabled)
        assertFalse(model.state.value.busy)
    }
    @Test fun changingAnswersDoesNotSpendAnotherAiRequest() = checkSetup { model, _, ai, _ ->
        followUp(model); model.onBack(); model.onExperienceSelected(ExperienceLevel.Developing)
        model.onNext {}; advanceUntilIdle()
        assertEquals(listOf("next"), ai.calls)
        assertFalse(model.state.value.aiEnabled)
    }
    @Test fun editedPlanMatchesTheSelectedFocusAndWeeklyTarget() = checkSetup { model, assessments, _, _ ->
        review(model); model.onReviewGoalSelected(Skill.Setting); model.onReviewWeeklyGoalSelected(5)
        model.onNext {}; advanceUntilIdle()
        assertEquals(Skill.Setting, assessments.saved?.focusSkill)
        assertEquals(5, assessments.saved?.weeklySessionGoal)
        assertEquals("set-wall-reps", assessments.saved?.starterDrillId)
        assertTrue(assessments.saved!!.summary.contains("5"))
    }
    @Test fun skipKeepsExistingAnswersAndDoesNotSaveDraft() = checkSetup { model, assessments, _, _ ->
        model.onGoalSelected(Skill.Blocking); model.onSkip {}; advanceUntilIdle()
        assertTrue(assessments.hasOnboarded.value)
        assertNull(assessments.saved)
        assertEquals(SelfRating.Okay, assessments.assessment.value.ratings[Skill.Setting])
    }
    @Test fun accountChangeWhileAiIsRunningCannotApplyOrSaveItsAnswer() = checkSetup { model, assessments, ai, switch ->
        ai.gate = CompletableDeferred()
        model.onGoalSelected(Skill.Serving); model.onNext {}; advanceUntilIdle()
        model.onExperienceSelected(ExperienceLevel.New); model.onNext {}; runCurrent()
        switch("player-b"); ai.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(AssessmentStep.Experience, model.state.value.step)
        assertTrue(model.state.value.error!!.contains("account changed"))
        assertNull(assessments.saved)
    }
    @Test fun generalQuestionAndUnknownAnswerRemainAnEditableSuggestion() {
        val answers = SetupAnswers(followUpQuestionId = "general_focus", followUpOptionId = "not_sure", weeklySessionGoal = 2)
        val suggestion = TrainingSetupQuestions.localSuggestion(answers)
        assertEquals("local", suggestion.source)
        assertTrue(TrainingSetupQuestions.starterDrill(suggestion.focusSkill) in DrillAccessPolicy.starterIds)
    }
}
