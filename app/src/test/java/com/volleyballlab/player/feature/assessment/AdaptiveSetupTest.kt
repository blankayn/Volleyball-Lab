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

    private fun check(saved: AssessmentAnswers? = null, block: suspend TestScope.(AssessmentViewModel, Assessments, Store) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore(); val assessments = Assessments(); val store = Store().also { it.state.value = AthleteState(saved) }
        val model = AssessmentViewModel(assessments, Profiles(), store, AthletePlanner(store, { reply() }, sources) { 2_000 }, drills, { account }, { 1_000 })
        owner.put("setup", model)
        try { advanceUntilIdle(); block(model, assessments, store) }
        finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    private fun TestScope.next(model: AssessmentViewModel) { model.onNext {}; advanceUntilIdle() }
    private fun TestScope.start(model: AssessmentViewModel, vararg skills: Skill) {
        model.onExperience(ExperienceLevel.Developing); next(model)
        skills.forEach(model::onPreferred); model.onGoal(MainGoal.MakeTheTeam); model.onGoalNote("Varsity"); next(model)
        model.onDays(4); model.onMinutes(45); next(model)
    }
    private fun TestScope.cards(model: AssessmentViewModel, answer: CanDo = CanDo.Sometimes) = repeat(SkillCheck.PER_SKILL) {
        model.onAnswer(requireNotNull(model.state.value.statement).id, answer); next(model)
    }
    private fun TestScope.fill(model: AssessmentViewModel) { start(model, Skill.Setting); cards(model) }

    @Test fun onlyTheChosenSkillsAreAskedAboutOneCardAtATime() = check { model, _, _ ->
        assertEquals("a known position is kept", CourtPosition.Libero, model.state.value.position)
        assertFalse(model.state.value.canAdvance)
        model.onExperience(ExperienceLevel.New); assertTrue(model.state.value.canAdvance)
        model.onPosition(null); next(model)
        model.onGoal(MainGoal.StayActive)
        assertFalse("a skill is needed", model.state.value.canAdvance)
        model.onPreferred(Skill.Blocking); model.onPreferred(Skill.Serving); assertTrue(model.state.value.canAdvance)
        model.onGoal(MainGoal.StayActive); next(model); next(model)
        val seen = List(2 * SkillCheck.PER_SKILL) {
            assertFalse("a card needs an answer", model.state.value.canAdvance)
            requireNotNull(model.state.value.statement).also { model.onAnswer(it.id, CanDo.NotTried); assertTrue("not having tried is an answer", model.state.value.canAdvance); next(model) }
        }
        assertEquals("in the order they were chosen", SkillCheck.forSkill(Skill.Blocking) + SkillCheck.forSkill(Skill.Serving), seen)
        assertEquals(AssessmentPage.Finish, model.state.value.kind)
        assertEquals(setOf(Skill.Receiving, Skill.Setting, Skill.Spiking), model.state.value.others.toSet())
        assertEquals(1f, model.state.value.progress, 0f)
    }

    @Test fun whatIsAskedNextFollowsTheAnswer() = check { model, assessments, _ ->
        start(model, Skill.Setting)
        val first = requireNotNull(model.state.value.statement)
        model.onDetail(first.id, first.details[0].id)
        assertTrue("nothing is asked before an answer", model.state.value.details.isEmpty())
        model.onAnswer(first.id, CanDo.NotYet); model.onDetail(first.id, first.details[0].id); model.onDetail(first.id, first.details[2].id); model.onDetail(first.id, first.details[0].id)
        assertEquals(listOf(first.details[2].id), model.state.value.details[first.id])
        model.onAnswer(first.id, CanDo.Yes)
        assertNull("it no longer applies", model.state.value.details[first.id])
        model.onAnswer(first.id, CanDo.Sometimes); model.onDetail(first.id, first.details[1].id); next(model)
        cardsLeft(model); next(model)
        assertEquals(mapOf(first.id to listOf(first.details[1].id)), requireNotNull(assessments.completed).details)
    }
    private fun TestScope.cardsLeft(model: AssessmentViewModel) {
        while (model.state.value.kind == AssessmentPage.Card) { model.onAnswer(requireNotNull(model.state.value.statement).id, CanDo.Yes); next(model) }
    }

    @Test fun aSkillNeverTriedIsNotAskedAboutCardByCard() = check { model, assessments, _ ->
        start(model, Skill.Blocking, Skill.Setting)
        model.onAnswer(requireNotNull(model.state.value.statement).id, CanDo.NotTried)
        model.onNeverTried()
        assertEquals("on to the next skill", Skill.Setting, model.state.value.skill)
        assertEquals(0, model.state.value.card)
        cards(model); next(model)
        assertEquals(setOf(CanDo.NotTried), requireNotNull(assessments.completed).statements.filterKeys { it.startsWith("blocking") }.values.toSet())
    }

    @Test fun anotherSkillCanBeAddedAtTheEndAndTakenOutAgain() = check { model, assessments, _ ->
        fill(model)
        model.onAddSkill(Skill.Setting)
        assertEquals("already there", AssessmentPage.Finish, model.state.value.kind)
        model.onAddSkill(Skill.Receiving); advanceUntilIdle()
        assertEquals("its first card opens", SkillCheck.forSkill(Skill.Receiving).first(), model.state.value.statement)
        cards(model, CanDo.Yes)
        assertEquals(AssessmentPage.Finish, model.state.value.kind)
        model.onRemoveSkill(Skill.Setting)
        assertEquals("a chosen skill stays", listOf(Skill.Setting, Skill.Receiving), model.state.value.assessed)
        model.onRemoveSkill(Skill.Receiving)
        assertEquals(AssessmentPage.Finish, model.state.value.kind)
        assertEquals(listOf(Skill.Setting), model.state.value.assessed)
        model.onAddSkill(Skill.Receiving)
        assertTrue("its answers are still there", model.state.value.canAdvance)
        repeat(SkillCheck.PER_SKILL) { next(model) }
        next(model)
        val saved = requireNotNull(assessments.completed)
        assertEquals(listOf(Skill.Setting), saved.preferred)
        assertEquals(listOf(Skill.Receiving, Skill.Setting), saved.assessed)
    }

    @Test fun aSkillOpenedFromTheEndLeadsStraightBackThere() = check { model, _, _ ->
        start(model, Skill.Setting, Skill.Serving); cards(model); cards(model)
        model.onEditSkill(Skill.Setting)
        assertEquals(SkillCheck.forSkill(Skill.Setting).first(), model.state.value.statement)
        model.onBack(); assertEquals(AssessmentPage.Finish, model.state.value.kind)
        model.onEditSkill(Skill.Setting); cards(model, CanDo.Yes)
        assertEquals(AssessmentPage.Finish, model.state.value.kind)
        model.onBack()
        assertEquals("then back is back again", SkillCheck.forSkill(Skill.Serving).last(), model.state.value.statement)
        repeat(SkillCheck.PER_SKILL) { model.onBack() }
        assertEquals(SkillCheck.forSkill(Skill.Setting).last(), model.state.value.statement)
    }

    @Test fun earlierAnswersAboutOtherSkillsAreOfferedButNotSent() = check(saved = AssessmentAnswers(ExperienceLevel.New, null, listOf(Skill.Setting), MainGoal.StayActive,
        statements = SkillCheck.statements.associate { it.id to CanDo.Yes })) { model, assessments, _ ->
        next(model); next(model); next(model); cards(model); next(model)
        assertEquals(listOf(Skill.Setting), requireNotNull(assessments.completed).assessed)
        assertTrue(SkillCheck.isAnswered(Skill.Receiving, model.state.value.statements))
    }

    @Test fun answersAreSavedThenTheAiWritesTheResult() = check { model, assessments, store ->
        fill(model)
        assertNull("nothing is saved before it is sent", assessments.completed)
        model.onNext {}; advanceUntilIdle()
        val saved = requireNotNull(assessments.completed)
        assertEquals(listOf(ExperienceLevel.Developing, CourtPosition.Libero, listOf(Skill.Setting), MainGoal.MakeTheTeam, "Varsity", 4, 45, SkillCheck.forSkill(Skill.Setting).map { it.id }.toSet()),
            listOf(saved.experience, saved.position, saved.preferred, saved.goal, saved.goalNote, saved.days, saved.minutes, saved.statements.keys))
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
