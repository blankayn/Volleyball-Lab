package com.volleyballlab.player

import com.volleyballlab.player.data.ai.AnalystResult
import com.volleyballlab.player.data.ai.AssessmentRequest
import com.volleyballlab.player.data.ai.AssessmentWire
import com.volleyballlab.player.data.ai.AthleteAnalyst
import com.volleyballlab.player.data.assessment.AthleteAssessmentRepository
import com.volleyballlab.player.data.assessment.AthleteAssessmentSchema
import com.volleyballlab.player.data.assessment.AthletePlanner
import com.volleyballlab.player.data.assessment.PlannerSources
import com.volleyballlab.player.data.assessment.PlannerStatus
import com.volleyballlab.player.domain.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AthleteAssessmentTest {
    private val answers = AssessmentAnswers(ExperienceLevel.New, null, listOf(Skill.Setting), MainGoal.BeConsistent, "Makapasok sa varsity", 3, 30,
        listOf(WeakPoint.KneeBend), "", SkillCheck.statements.associate { it.id to if (it.skill == Skill.Receiving) CanDo.NotYet else CanDo.Yes }, 1_000)
    private fun drill(id: String, skill: Skill, level: DrillLevel = DrillLevel.Beginner, reps: Int = 15, minutes: Int = 5) = Drill(id, skill, id, "", reps, minutes, level)
    private val receive = drill("receive-wall-platform", Skill.Receiving)
    private val set = drill("set-wall-reps", Skill.Setting, reps = 20)
    private val none = Skill.All.map { MeasuredSkill(it, null, 0) }
    private fun request(measured: List<MeasuredSkill> = none, checks: List<CheckTrend> = emptyList(), given: AssessmentAnswers = answers) =
        AssessmentRequest(given, measured, checks, listOf(receive, set))

    private fun point(skill: String, text: String, vararg evidence: String) = mapOf("skill" to skill, "text" to text, "evidence" to evidence.toList())
    private fun planned(id: String, sets: Int = 2, reps: Int = 15, why: String = "It fits your goal.") = mapOf("drillId" to id, "sets" to sets, "reps" to reps, "why" to why)
    private fun reply(edit: Map<String, Any?> = emptyMap()) = mapOf("status" to "ready", "source" to "groq", "analysis" to mapOf(
        "summary" to "Serving is steady and receiving has the most room to grow.",
        "strengths" to listOf(point("Serving", "You start balanced.", "say:serving_ready")),
        "weaknesses" to listOf(point("Receiving", "Getting low is new for you.", "say:receiving_ready", "weak:KneeBend")),
        "improvements" to emptyList<Any>(), "focus" to listOf("Receiving"),
        "practices" to listOf(mapOf("title" to "Low and ready", "drills" to listOf(planned("receive-wall-platform")))),
        "weeklyGoals" to listOf(mapOf("skill" to "Receiving", "text" to "Bend before every pass.")),
    ) + edit)
    private fun decode(edit: Map<String, Any?> = emptyMap(), from: AssessmentRequest = request()) = (AssessmentWire.decode(reply(edit), from) as AnalystResult.Ready).analysis

    @Test fun aPointWithoutSupportingFactsNeverReachesTheAthlete() {
        assertEquals(1, decode().strengths.size)
        fun strengths(vararg items: Map<String, Any?>) = decode(mapOf("strengths" to items.toList())).strengths.size
        assertEquals(0, strengths(point("Serving", "You swing high.")))
        assertEquals("no camera score exists", 0, strengths(point("Serving", "You swing high.", "score:Serving")))
        assertEquals("another skill", 0, strengths(point("Serving", "You swing high.", "say:setting_ready")))
        assertEquals("the answer says the opposite", 0, strengths(point("Receiving", "You get low.", "say:receiving_ready")))
        assertEquals("a general weak point alone", 0, decode(mapOf("weaknesses" to listOf(point("Serving", "Hard.", "weak:KneeBend")))).weaknesses.size)
        val fresh = request(given = answers.copy(statements = SkillCheck.statements.associate { it.id to CanDo.NotTried }))
        val first = point("Blocking", "Try blocking for the first time.", "say:blocking_ready")
        assertEquals("someone brand new still gets a result", 1, decode(mapOf("improvements" to listOf(first)), fresh).improvements.size)
        assertTrue("not trying is not a weakness", runCatching { decode(mapOf("weaknesses" to listOf(first)), fresh) }.isFailure)
        val measured = none.map { if (it.skill == Skill.Serving) MeasuredSkill(Skill.Serving, 40, 3) else it }
        assertEquals(1, decode(mapOf("weaknesses" to listOf(point("Serving", "Your recorded serves need work.", "score:Serving"))), request(measured)).weaknesses.size)
        val checks = listOf(CheckTrend(CueId.PassKneeFlexion, CueTrend.Slipping))
        assertEquals(1, decode(mapOf("weaknesses" to listOf(point("Receiving", "Your knee bend slipped.", "check:PassKneeFlexion"))), request(none, checks)).weaknesses.size)
    }

    @Test fun numbersLinksAndUnknownDrillsAreRefused() {
        assertTrue(runCatching { decode(mapOf("summary" to "You scored 88.")) }.isFailure)
        assertEquals(0, decode(mapOf("strengths" to listOf(point("Serving", "See http://x", "say:serving_ready")))).strengths.size)
        assertTrue(runCatching { decode(mapOf("practices" to listOf(mapOf("title" to "x", "drills" to listOf(planned("spike-standing-swing")))))) }.isFailure)
        assertTrue(runCatching { decode(mapOf("strengths" to emptyList<Any>(), "weaknesses" to emptyList<Any>())) }.isFailure)
        assertTrue(runCatching { AssessmentWire.decode(reply() + ("source" to "local"), request()) }.isFailure)
        assertEquals(AnalystResult.Unavailable(true), AssessmentWire.decode(mapOf("status" to "unavailable", "reason" to "limit"), request()))
    }

    @Test fun thePlanFitsTheTimeAndDaysGiven() {
        val long = listOf(mapOf("title" to "Long", "drills" to listOf(planned("receive-wall-platform", 4, 15), planned("set-wall-reps", 6, 30))))
        val practice = decode(mapOf("practices" to long)).practices.single()
        assertTrue(PlanRules.practiceMinutes(practice, listOf(receive, set).associateBy { it.id }) <= 30)
        val many = List(3) { mapOf("title" to "Practice", "drills" to listOf(planned("receive-wall-platform"))) }
        assertEquals(1, decode(mapOf("practices" to many), request(given = answers.copy(days = 1))).practices.size)
    }

    @Test fun whatIsSentNamesNobodyAndOnlyTheChosenSkills() {
        val fields = AssessmentWire.fields(request())
        assertEquals(setOf("version", "profile", "statements", "details", "measured", "checks", "drills", "finished", "marks", "previousFocus"), fields.keys)
        assertEquals(20, (fields["statements"] as Map<*, *>).size)
        assertEquals(listOf("receive-wall-platform", "set-wall-reps"), fields["drills"])
        assertFalse(fields.toString().contains("uid", ignoreCase = true))
        val one = answers.copy(statements = answers.statements.filterKeys { it.startsWith("receiving") } + ("setting_ready" to CanDo.Yes),
            details = mapOf("receiving_control" to listOf("receiving_control_2", "made_up"), "setting_ready" to listOf("setting_ready_1")))
        val sent = AssessmentWire.fields(request(given = one))
        assertEquals("a skill half answered is not sent", setOf("receiving_ready", "receiving_movement", "receiving_control", "receiving_moving"), (sent["statements"] as Map<*, *>).keys)
        assertEquals(mapOf("receiving_control" to listOf("receiving_control_2")), sent["details"])
    }

    @Test fun onlyChosenSkillsAreAssessedAndPlannedAndOthersAreUnknown() {
        val setting = answers.copy(statements = answers.statements.filterKeys { it.startsWith("setting") })
        assertEquals(listOf(Skill.Setting), setting.assessed)
        assertTrue(setting.isComplete)
        assertFalse("a chosen skill must be answered", setting.copy(preferred = listOf(Skill.Setting, Skill.Serving)).isComplete)
        assertFalse(setting.copy(preferred = emptyList()).isComplete)
        val facts = AssessmentFacts.of(setting, none, emptyList())
        assertEquals(setOf("say:setting_ready", "say:setting_movement", "say:setting_control", "say:setting_moving", "weak:KneeBend"), facts.keys)
        assertEquals(listOf("set-wall-reps"), PlanRules.drillsFor(setting, none, listOf(receive, set)).map { it.id })
        assertTrue("nothing can be said about a skill that was not asked about", runCatching { decode(from = request(given = setting)) }.isFailure)
    }

    @Test fun whatThePlayerSaysHappensIsShownWithTheFact() {
        val said = answers.copy(details = mapOf("receiving_control" to listOf("receiving_control_2"), "serving_ready" to listOf("serving_ready_1")))
        val facts = AssessmentFacts.of(said, none, emptyList())
        assertEquals(listOf("It falls short"), facts.getValue("say:receiving_control").details.map { it.text })
        assertTrue("it was answered Yes", facts.getValue("say:serving_ready").details.isEmpty())
        assertEquals(AthleteState(said, null), AthleteAssessmentSchema.read(AthleteAssessmentSchema.fields(said, null)))
    }

    @Test fun difficultyFollowsExperienceThenWhatWasMeasured() {
        assertEquals(DrillLevel.Beginner, PlanRules.levelFor(ExperienceLevel.New, null))
        assertEquals(DrillLevel.Advanced, PlanRules.levelFor(ExperienceLevel.Experienced, null))
        assertEquals("a measured score outranks experience", DrillLevel.Beginner, PlanRules.levelFor(ExperienceLevel.Experienced, 45))
        assertEquals(DrillLevel.Advanced, PlanRules.levelFor(ExperienceLevel.New, 80))
        val open = listOf(drill("b", Skill.Serving), drill("i", Skill.Serving, DrillLevel.Intermediate), drill("a", Skill.Serving, DrillLevel.Advanced), drill("r", Skill.Receiving))
        assertEquals(listOf("b", "r"), PlanRules.drillsFor(answers, none, open).map { it.id })
        val expert = answers.copy(experience = ExperienceLevel.Experienced)
        assertEquals("the closest level when there is no other", listOf("a", "r"), PlanRules.drillsFor(expert, none, open).map { it.id })
        assertEquals("position never hides a skill", PlanRules.drillsFor(expert, none, open),
            PlanRules.drillsFor(expert.copy(position = CourtPosition.Libero), none, open))
    }

    private fun plan(id: String = "p", at: Long = 5_000, analysis: AthleteAnalysis = decode()) = AthletePlan(id, at, analysis, none)
    private fun scored(id: String, at: Long, drillId: String = "receive-wall-platform") = SessionResult(id, Skill.Receiving, drillId, at, 1_000,
        listOf(RepResult(0, 0, 10, 50, listOf(CueScore(CueId.PassKneeFlexion, 120f, 1f)))))

    @Test fun aNewPlanNeedsNewSessionsAndTime() {
        val week = PlanRules.MIN_AGE_MS
        val state = AthleteState(answers, plan())
        val three = List(3) { scored("s$it", 6_000L + it) }
        assertTrue(PlanRules.isDue(state, three, 5_000 + week))
        assertFalse("too soon", PlanRules.isDue(state, three, 5_000 + week - 1))
        assertFalse("too few", PlanRules.isDue(state, three.drop(1), 5_000 + week))
        assertFalse("older sessions do not count", PlanRules.isDue(state, List(3) { scored("s$it", 100) }, 5_000 + week))
        assertTrue(AthleteState(answers, null).isWaiting)
        assertTrue("a retake waits for its own plan", AthleteState(answers.copy(savedAtEpochMs = 9_000), plan()).isWaiting)
        assertFalse(state.isWaiting)
    }

    @Test fun whatChangedComesFromTheFacts() {
        val before = plan().copy(measured = none.map { if (it.skill == Skill.Receiving) MeasuredSkill(it.skill, 52, 3) else it })
        val now = none.map { when (it.skill) { Skill.Receiving -> MeasuredSkill(it.skill, 68, 6); Skill.Serving -> MeasuredSkill(it.skill, 70, 1); else -> it } }
        val next = decode(mapOf("focus" to listOf("Setting"), "practices" to listOf(mapOf("title" to "Hands", "drills" to listOf(planned("set-wall-reps", 1))))))
        val changes = PlanRules.changes(before, next, now, listOf(receive, set).associateBy { it.id })
        assertEquals(listOf("Serving: first camera score 70.", "Receiving: camera score 52 to 68.", "Focus is now Setting.", "Added set-wall-reps.", "Finished with receive-wall-platform."), changes)
        assertTrue(PlanRules.changes(null, next, now, emptyMap()).isEmpty())
    }

    @Test fun aDrillIsDoneBySessionOrWorkoutSinceThePlan() {
        assertEquals(setOf("receive-wall-platform"), PlanRules.done(plan(), listOf(scored("s", 6_000)), emptyList(), 0))
        assertTrue(PlanRules.done(plan(), listOf(scored("s", 4_000)), emptyList(), 0).isEmpty())
        assertTrue(PlanRules.done(plan(), listOf(scored("s", 6_000)), emptyList(), 7_000).isEmpty())
    }

    @Test fun savedAssessmentsReadBackExactlyAndDamagedPartsAreIgnored() {
        val saved = plan().copy(checks = listOf(CheckTrend(CueId.PassKneeFlexion, CueTrend.Improving)), changes = listOf("x"))
        val fields = AthleteAssessmentSchema.fields(answers, saved)
        assertEquals(AthleteState(answers, saved), AthleteAssessmentSchema.read(fields))
        assertEquals(AthleteState(answers, null), AthleteAssessmentSchema.read(fields + ("plan" to mapOf("id" to 3L))))
        assertEquals(AthleteState(), AthleteAssessmentSchema.read(emptyMap()))
        assertEquals(setOf("version", "answers", "plan", "updatedAt"), fields.keys)
    }

    private class Store(start: AthleteState?) : AthleteAssessmentRepository {
        override val state = MutableStateFlow(start)
        val plans = mutableListOf<AthletePlan>()
        override suspend fun saveAnswers(uid: String, answers: AssessmentAnswers) { state.value = AthleteState(answers, state.value?.plan) }
        override suspend fun savePlan(uid: String, answers: AssessmentAnswers, plan: AthletePlan) { plans += plan; state.value = AthleteState(answers, plan) }
    }
    private inner class Sources(var recorded: List<SessionResult> = emptyList()) : PlannerSources {
        override var uid: String? = "a"
        var focus: List<Skill> = emptyList()
        override suspend fun sessions() = recorded
        override suspend fun memory() = AdaptiveMemory()
        override suspend fun marks() = emptyList<RepMark>()
        override suspend fun workouts() = emptyList<DrillPracticeRecord>()
        override suspend fun openDrills() = listOf(receive, set)
        override suspend fun onPlanned(uid: String, focus: List<Skill>) { this.focus = focus }
    }

    @Test fun theFirstPlanIsWrittenFromAnswersAndAFailureMakesNothingUp() = runTest {
        val store = Store(AthleteState(answers, null)); val sources = Sources(); var time = 10_000L
        var reply: AnalystResult = AnalystResult.Unavailable(false); var calls = 0
        val planner = AthletePlanner(store, { calls++; reply }, sources) { time }
        planner.refreshIfDue()
        assertEquals(PlannerStatus.Unavailable, planner.status.value)
        assertTrue(store.plans.isEmpty())
        planner.refreshIfDue()
        assertEquals("not again straight away", 1, calls)
        reply = AnalystResult.Unavailable(true); assertFalse(planner.write()); assertEquals(PlannerStatus.Limited, planner.status.value)
        reply = AnalystResult.Ready(decode()); time += 7L * 60 * 60 * 1000
        planner.refreshIfDue()
        assertEquals(1, store.plans.size)
        assertEquals(PlannerStatus.Idle, planner.status.value)
        assertEquals(listOf(Skill.Receiving), sources.focus)
        planner.refreshIfDue()
        assertEquals("up to date", 1, store.plans.size)
    }

    @Test fun newPerformanceWritesANewPlanWithMeasuredFacts() = runTest {
        val store = Store(AthleteState(answers, plan(at = 2_000))); var seen: AssessmentRequest? = null
        val sources = Sources(List(8) { scored("s$it", 3_000L + it) })
        val planner = AthletePlanner(store, AthleteAnalyst { seen = it; AnalystResult.Ready(decode(from = request())) }, sources) { 3_000 + PlanRules.MIN_AGE_MS }
        planner.refreshIfDue()
        assertEquals(1, store.plans.size)
        assertEquals(100, seen!!.measured.first { it.skill == Skill.Receiving }.score)
        assertEquals(mapOf("receive-wall-platform" to 8), seen!!.finished)
        assertEquals(CueTrend.Solid, seen!!.checks.first { it.cueId == CueId.PassKneeFlexion }.trend)
        assertTrue(store.plans.single().changes.first().startsWith("Receiving: first camera score 100"))
    }

    @Test fun anAccountChangeWhileTheAiWorksSavesNothing() = runTest {
        val store = Store(AthleteState(answers, null)); val sources = Sources()
        val planner = AthletePlanner(store, { sources.uid = "b"; AnalystResult.Ready(decode()) }, sources) { 10_000 }
        assertFalse(planner.write())
        assertTrue(store.plans.isEmpty())
    }
}
