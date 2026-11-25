package com.volleyballlab.player.feature.assessment

import androidx.lifecycle.ViewModelStore
import com.volleyballlab.player.data.ai.AnalystResult
import com.volleyballlab.player.data.assessment.AthleteAssessmentRepository
import com.volleyballlab.player.data.assessment.AthletePlanner
import com.volleyballlab.player.data.assessment.PlannerSources
import com.volleyballlab.player.data.assessment.PlannerStatus
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
        var completed: AssessmentAnswers? = null
        override suspend fun save(assessment: SkillAssessment) { this.assessment.value = assessment }
        override suspend fun skip() { hasOnboarded.value = true }
        override suspend fun complete(answers: AssessmentAnswers) { completed = answers }
    }
    private class Profiles : ProfileRepository {
        override val profile = MutableStateFlow(AthleteProfile(name = "Player", position = CourtPosition.Libero))
        override suspend fun save(profile: AthleteProfile) { this.profile.value = profile }
    }
    private class Store : AthleteAssessmentRepository {
        override val state = MutableStateFlow<AthleteState?>(AthleteState())
        override suspend fun saveAnswers(uid: String, answers: AssessmentAnswers) { state.value = AthleteState(answers, state.value?.plan) }
        override suspend fun savePlan(uid: String, answers: AssessmentAnswers, plan: AthletePlan) { state.value = AthleteState(answers, plan) }
    }
    private val drill = Drill("set-wall-reps", Skill.Setting, "Setting Stance", "", 20, 5, DrillLevel.Beginner)
    private val drills = object : DrillRepository {
        override suspend fun drillsFor(skill: Skill) = if (skill == Skill.Setting) listOf(drill) else emptyList()
        override suspend fun byId(id: String) = drill.takeIf { it.id == id }
    }
    private val sources = object : PlannerSources {
        override val uid get() = account
        override suspend fun sessions() = emptyList<SessionResult>()
        override suspend fun memory() = AdaptiveMemory()
        override suspend fun marks() = emptyList<RepMark>()
        override suspend fun workouts() = emptyList<DrillPracticeRecord>()
        override suspend fun openDrills() = listOf(drill)
        override suspend fun onPlanned(uid: String, focus: List<Skill>) = Unit
    }
    private val analysis = AthleteAnalysis("A plan for you.", focus = listOf(Skill.Setting),
        practices = listOf(PlannedPractice("Hands up", listOf(PlannedDrill(drill.id, 2, 10, "You like setting.")))))
    private var account: String? = "player-a"
    private var reply: suspend () -> AnalystResult = { AnalystResult.Ready(analysis) }

    private fun check(block: suspend TestScope.(AssessmentViewModel, Assessments, Store) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore(); val assessments = Assessments(); val store = Store()
        val model = AssessmentViewModel(assessments, Profiles(), store, AthletePlanner(store, { reply() }, sources) { 2_000 }, drills, { account }, { 1_000 })
        owner.put("setup", model)
        try { advanceUntilIdle(); block(model, assessments, store) }
        finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    private fun TestScope.fill(model: AssessmentViewModel) {
        model.onExperience(ExperienceLevel.Developing); model.onNext {}; advanceUntilIdle()
        model.onPreferred(Skill.Setting); model.onGoal(MainGoal.MakeTheTeam); model.onGoalNote("Varsity"); model.onNext {}; advanceUntilIdle()
        model.onDays(4); model.onMinutes(45); model.onNext {}; advanceUntilIdle()
        repeat(Skill.All.size) {
            SkillCheck.forSkill(requireNotNull(model.state.value.skill)).forEach { model.onAnswer(it.id, CanDo.Sometimes) }
            model.onNext {}; advanceUntilIdle()
        }
        model.onWeakPoint(WeakPoint.Timing)
    }

    @Test fun experienceGoalAndEveryStatementAreNeededAndTheRestIsOptional() = check { model, _, _ ->
        assertEquals("a known position is kept", CourtPosition.Libero, model.state.value.position)
        assertFalse(model.state.value.canAdvance)
        model.onExperience(ExperienceLevel.New); assertTrue(model.state.value.canAdvance)
        model.onPosition(null); model.onNext {}; advanceUntilIdle()
        assertFalse("a goal is needed", model.state.value.canAdvance)
        model.onGoal(MainGoal.StayActive); model.onNext {}; advanceUntilIdle()
        assertTrue(model.state.value.canAdvance); model.onNext {}; advanceUntilIdle()
        val skill = requireNotNull(model.state.value.skill)
        SkillCheck.forSkill(skill).dropLast(1).forEach { model.onAnswer(it.id, CanDo.Yes) }
        assertFalse(model.state.value.canAdvance)
        model.onAnswer(SkillCheck.forSkill(skill).last().id, CanDo.NotTried)
        assertTrue("not having tried is an answer", model.state.value.canAdvance)
    }

    @Test fun answersAreSavedThenTheAiWritesTheResult() = check { model, assessments, store ->
        fill(model)
        assertNull("nothing is saved before it is sent", assessments.completed)
        model.onNext {}; advanceUntilIdle()
        val saved = requireNotNull(assessments.completed)
        assertEquals(listOf(ExperienceLevel.Developing, CourtPosition.Libero, listOf(Skill.Setting), MainGoal.MakeTheTeam, "Varsity", 4, 45, listOf(WeakPoint.Timing)),
            listOf(saved.experience, saved.position, saved.preferred, saved.goal, saved.goalNote, saved.days, saved.minutes, saved.weakPoints))
        assertEquals(20, saved.statements.size)
        assertEquals(AssessmentPage.Result, model.state.value.kind)
        assertEquals(analysis, model.state.value.result?.analysis)
        assertEquals(analysis, store.state.value?.plan?.analysis)
        var finished = false; model.onNext { finished = true }; assertTrue(finished)
    }

    @Test fun whenTheAiIsAwayAnswersStaySavedAndNoResultIsMadeUp() = check { model, assessments, store ->
        reply = { error("offline") }
        fill(model); model.onNext {}; advanceUntilIdle()
        assertNotNull(assessments.completed)
        assertTrue(store.state.value!!.isWaiting)
        assertNull(model.state.value.result)
        assertEquals(PlannerStatus.Unavailable, model.state.value.planner)
        assertTrue("the athlete is never stuck", model.state.value.canAdvance)
        reply = { AnalystResult.Ready(analysis) }
        model.retryPlan(); advanceUntilIdle()
        assertEquals(analysis, model.state.value.result?.analysis)
    }

    @Test fun anEarlierPlanIsNotShownAsTheResultOfNewAnswers() = check { model, _, store ->
        store.state.value = AthleteState(null, AthletePlan("old", 10, analysis)); advanceUntilIdle()
        reply = { AnalystResult.Unavailable(true) }
        fill(model); model.onNext {}; advanceUntilIdle()
        assertNull(model.state.value.result)
        assertEquals(PlannerStatus.Limited, model.state.value.planner)
    }

    @Test fun anAccountChangeCannotSave() = check { model, assessments, store ->
        fill(model); account = "player-b"
        model.onNext {}; advanceUntilIdle()
        assertNull(assessments.completed); assertNull(store.state.value?.answers)
        assertTrue(model.state.value.error!!.contains("account changed"))
    }

    @Test fun skipKeepsExistingAnswersAndSavesNothing() = check { model, assessments, _ ->
        model.onExperience(ExperienceLevel.New); model.onSkip {}; advanceUntilIdle()
        assertTrue(assessments.hasOnboarded.value)
        assertNull(assessments.completed)
        assertEquals(SelfRating.Okay, assessments.assessment.value.ratings[Skill.Setting])
    }
}
