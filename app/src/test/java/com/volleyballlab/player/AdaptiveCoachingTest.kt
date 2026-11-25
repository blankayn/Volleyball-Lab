package com.volleyballlab.player

import com.volleyballlab.player.data.coaching.RepMarks
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.pose.analysis.CoachDirector
import com.volleyballlab.player.pose.analysis.CoachPhrasebook
import com.volleyballlab.player.pose.analysis.CoachingEventType
import com.volleyballlab.player.pose.analysis.CueLiveStatus
import com.volleyballlab.player.pose.analysis.LiveCoachingPlan
import com.volleyballlab.player.pose.analysis.LiveRepAssessment
import org.junit.Assert.*
import org.junit.Test

class AdaptiveCoachingTest {
    private val knee = CueId.PassKneeFlexion
    private val arms = CueId.PassPlatformStraight

    /** One session; each entry is a rep's scores for the knee and the arms. */
    private fun session(id: String, at: Long, vararg reps: Pair<Float?, Float?>) = SessionResult(
        id = id, skill = Skill.Receiving, drillId = "receive-short-court", startedAtEpochMs = at, durationMs = 1000,
        reps = reps.mapIndexed { i, (k, a) -> RepResult(i, i * 100L, i * 100L + 10, i * 100L + 50,
            listOf(CueScore(knee, k?.let { 100f }, k), CueScore(arms, a?.let { 100f }, a))) })

    private fun learned(sessions: List<SessionResult>, memory: AdaptiveMemory = AdaptiveMemory(), marks: List<RepMark> = emptyList()) =
        AdaptiveCoaching.learn(Skill.Receiving, sessions, memory, marks).associateBy { it.cue.id }

    private fun reps(n: Int, k: Float?, a: Float? = 1f) = Array(n) { k to a }

    @Test fun trendsFollowRightAndWrongRepsWithoutAnyoneSettingThem() {
        assertEquals(CueTrend.Learning, learned(listOf(session("a", 1, *reps(5, 1f))))[knee]!!.trend)
        assertEquals(CueTrend.Solid, learned(listOf(session("a", 1, *reps(10, 1f))))[knee]!!.trend)
        assertEquals(CueTrend.WorkOn, learned(listOf(session("a", 1, *reps(10, .2f))))[knee]!!.trend)
        val improving = learned(listOf(session("a", 1, *reps(20, .2f)), session("b", 2000, *reps(10, 1f), *reps(10, .2f))))[knee]!!
        assertEquals(CueTrend.Improving, improving.trend)
        assertEquals(listOf(10, 20, 0, 20), listOf(improving.recentRight, improving.recentTotal, improving.earlierRight, improving.earlierTotal))
        assertEquals(CueTrend.Slipping, learned(listOf(session("a", 1, *reps(20, 1f)), session("b", 2000, *reps(20, .2f))))[knee]!!.trend)
        assertEquals("unmeasured is not wrong", CueTrend.Learning, learned(listOf(session("a", 1, *reps(20, null))))[knee]!!.trend)
        assertEquals("other skills do not count", 0, AdaptiveCoaching.learn(Skill.Serving, listOf(session("a", 1, *reps(20, 1f)))).sumOf { it.recentTotal })
    }

    @Test fun refusedAttemptsCountAsWrongAndOldHabitsAreForgotten() {
        val good = listOf(session("a", 1, *reps(8, 1f)))
        assertEquals(CueTrend.Solid, learned(good)[knee]!!.trend)
        val missed = AdaptiveMemory().remember(Skill.Receiving, emptyList(), List(8) { knee to 9_000L + it })
        assertEquals(CueTrend.WorkOn, learned(good, missed)[knee]!!.trend)
        assertEquals(CueTrend.Solid, learned(good, missed)[arms]!!.trend)
        val recovered = learned(listOf(session("a", 1, *reps(40, .1f)), session("b", 90_000, *reps(40, 1f))))[knee]!!
        assertEquals(CueTrend.Solid, recovered.trend)
    }

    @Test fun aCoachMarkOutranksTheCameraAndTheLatestMarkWins() {
        val sessions = listOf(session("a", 1, *reps(10, .2f)))
        val right = (1..10).map { RepMark("a", it, true, emptySet(), 5) }
        assertEquals(CueTrend.Solid, learned(sessions, marks = right)[knee]!!.trend)
        val changed = right + (1..10).map { RepMark("a", it, false, setOf(arms), 9) }
        val after = learned(sessions, marks = changed)
        assertEquals("only the named check", 0, after[arms]!!.recentRight)
        assertEquals(CueTrend.WorkOn, after[knee]!!.trend)
        assertEquals("a mark on another session changes nothing", 0, learned(sessions, marks = listOf(RepMark("x", 1, true, emptySet(), 1)))[knee]!!.recentRight)
    }

    @Test fun unreadableMarksAreIgnored() {
        assertNull(RepMarks.of(mapOf("verdict" to "Maybe", "repNumber" to 1L, "sessionId" to "a"), 1))
        assertNull(RepMarks.of(mapOf("verdict" to "Right", "repNumber" to 0L, "sessionId" to "a"), 1))
        assertNull(RepMarks.of(mapOf("verdict" to "Right", "repNumber" to 1L), 1))
        assertEquals(RepMark("a", 2, false, setOf(knee), 7),
            RepMarks.of(mapOf("verdict" to "Wrong", "repNumber" to 2L, "sessionId" to "a", "cueIds" to listOf("PassKneeFlexion", "Future")), 7))
    }

    @Test fun theSessionCoachesWhatThisPlayerNeedsAndDropsWhatIsLearned() {
        val drill = linkedSetOf(CueId.PassPlatformStraight, CueId.PassKneeFlexion)
        assertEquals("nothing learned, nothing changed", drill.toList(), AdaptiveCoaching.focus(drill, learned(emptyList()).values.toList()).toList())
        val needKnee = learned(listOf(session("a", 1, *reps(10, .2f, 1f)))).values.toList()
        assertEquals(listOf(knee), AdaptiveCoaching.focus(drill, needKnee).toList())
        assertEquals("a finished drill moves on to the next weakest check", listOf(knee),
            AdaptiveCoaching.focus(setOf(arms), needKnee).toList())
        val allSolid = learned(listOf(session("a", 1, *reps(10, 1f, 1f)))).values.toList()
        assertEquals(drill, AdaptiveCoaching.focus(drill, allSolid))
    }

    @Test fun theOpeningSaysWhatChangedAndStaysQuietWhenNothingIsKnown() {
        val plan = LiveCoachingPlan(athleteName = "Maya").withDrill("receive-short-court")
        val blank = plan.withLearned(learned(emptyList()).values.toList())
        assertEquals(plan.opening(Skill.Receiving), blank.opening(Skill.Receiving))
        val slipping = plan.withLearned(learned(listOf(session("a", 1, *reps(20, 1f)), session("b", 2000, *reps(20, .2f)))).values.toList())
        assertTrue(slipping.opening(Skill.Receiving).any { it == "Your knee bend has slipped lately, so we'll start there." })
        val improving = plan.withLearned(learned(listOf(session("a", 1, *reps(20, .2f)), session("b", 2000, *reps(10, 1f), *reps(10, .2f)))).values.toList())
        assertTrue(improving.opening(Skill.Receiving).any { it == "Your knee bend is getting better, so let's keep building it." })
        assertTrue(improving.opening(Skill.Receiving).none { "Last time" in it })
    }

    private fun status(value: Float): CueLiveStatus = requireNotNull(FormCueCatalog.byId(arms)).let { CueLiveStatus(it, value, it.score(value)) }
    private fun CoachDirector.rep(value: Float, at: Long) =
        onRep(LiveRepAssessment(null, 1f, false, false, listOf(status(value))), at, CameraAngle.Front)

    @Test fun theDirectorLearnsWhetherACorrectionHelped() {
        val helped = CoachDirector()
        repeat(3) { helped.rep(130f, it * 5_000L) }
        assertTrue(helped.teachingOutcomes().isEmpty())
        helped.rep(175f, 15_000); helped.rep(175f, 20_000)
        assertEquals(listOf(TeachingOutcome(arms, TeachingStyle.Direct, true)), helped.teachingOutcomes())

        val ignored = CoachDirector()
        repeat(5) { ignored.rep(130f, it * 5_000L) }
        assertEquals(listOf(TeachingOutcome(arms, TeachingStyle.Direct, false)), ignored.teachingOutcomes())
        ignored.reset()
        assertTrue(ignored.teachingOutcomes().isEmpty())
    }

    @Test fun aCorrectionNobodyHeardOrAGapIsNotCountedAsTeaching() {
        val silent = CoachDirector()
        var event = silent.rep(130f, 0)
        repeat(2) { event = silent.rep(130f, (it + 1) * 5_000L) }
        assertEquals(CoachingEventType.Correction, event?.type)
        silent.onSpeechDropped(event!!.id)
        silent.rep(175f, 15_000); silent.rep(175f, 20_000)
        assertTrue(silent.teachingOutcomes().isEmpty())

        val interrupted = CoachDirector()
        repeat(3) { interrupted.rep(130f, it * 5_000L) }
        interrupted.interruptObservation()
        interrupted.rep(175f, 15_000); interrupted.rep(175f, 20_000)
        assertTrue(interrupted.teachingOutcomes().isEmpty())
    }

    @Test fun theNextSessionStartsWithTheExplanationThatWorksForThisPlayer() {
        val texts = CoachPhrasebook.instructions(arms, 130f)
        fun firstWords(memory: AdaptiveMemory): String? {
            val coach = CoachDirector().apply { teaching = memory }
            var event = coach.rep(130f, 0)
            repeat(2) { event = coach.rep(130f, (it + 1) * 5_000L) }
            return event?.text
        }
        assertEquals(texts[0], firstWords(AdaptiveMemory()))
        val directFails = AdaptiveMemory().remember(Skill.Receiving, List(2) { TeachingOutcome(arms, TeachingStyle.Direct, false) }, emptyList())
        assertEquals(texts[1], firstWords(directFails))
        val resetWorks = directFails.remember(Skill.Receiving, List(3) { TeachingOutcome(arms, TeachingStyle.Reset, true) }, emptyList())
        assertEquals(texts[2], firstWords(resetWorks))
        assertEquals(TeachingStyle.Reset, resetWorks.bestStyle(arms)!!.first)
        assertNull(directFails.bestStyle(arms))
        TeachingStyle.entries.forEachIndexed { i, style -> assertEquals(texts[i], CoachPhrasebook.inStyle(arms, 130f, style)) }
        assertEquals(TeachingStyle.entries.toSet(), CoachPhrasebook.styles(ExperienceLevel.New).toSet())
        CoachPhrasebook.styles(ExperienceLevel.New).forEachIndexed { i, style ->
            assertEquals(CoachPhrasebook.instructions(arms, 130f, experience = ExperienceLevel.New)[i], CoachPhrasebook.inStyle(arms, 130f, style))
        }
    }

    @Test fun whatWorkedLongAgoFades() {
        var tally = TeachingTally()
        repeat(21) { tally = tally.plus(true) }
        assertEquals(TeachingTally(10, 10), tally)
    }

    private fun drill(id: String, skill: Skill = Skill.Receiving) = Drill(id, skill, id, "", 10, 5, DrillLevel.Beginner)
    private val open = listOf(drill("receive-wall-platform"), drill("receive-short-court"), drill("receive-serve-to-target"),
        drill("serve-standing-float", Skill.Serving), drill("set-wall-reps", Skill.Setting))

    /** Every check is off by [miss] of its tolerance, so the score is about 100 × (1 - miss). */
    private fun scored(id: String, at: Long, miss: Float, drillId: String = "receive-wall-platform", only: Set<CueId>? = null) =
        SessionResult(id, Skill.Receiving, drillId, at, 1000, List(6) { i -> RepResult(i, i * 100L, i * 100L + 10, i * 100L + 50,
            FormCueCatalog.forSkill(Skill.Receiving).map { c ->
                val v = c.target.start - c.warnTolerance * (if (only == null || c.id in only) miss else 0f)
                CueScore(c.id, v, c.score(v))
            }) })

    private fun next(history: List<SessionResult>, evidence: List<SkillEvidence> = SkillEvidence.from(emptyMap(), history)): NextStep? {
        val session = history.last()
        return NextStepAdvisor.suggest(session, history, CoachAssessmentEngine.assess(session),
            AdaptiveCoaching.learn(Skill.Receiving, history, drillId = session.drillId), evidence, open)
    }

    @Test fun aWeakCheckSuggestsADrillOfTheSameSkillThatTrainsIt() {
        val step = next(listOf(scored("a", 1, .9f, only = setOf(knee))))!!
        assertEquals(NextStepReason.WorkOn, step.reason)
        assertEquals(knee, step.cue!!.id)
        assertEquals("receive-short-court", step.drill!!.id)
        val same = next(listOf(scored("a", 1, .9f, "receive-short-court", setOf(knee))))!!
        assertNull("already on a drill that trains it", same.drill)
    }

    @Test fun aGoodSessionSuggestsAnotherDrillOfTheSkill() {
        val step = next(listOf(scored("a", 1, 0f)))!!
        assertEquals(NextStepReason.MoveUp, step.reason)
        assertEquals(Skill.Receiving, step.drill!!.skill)
        assertNotEquals("receive-wall-platform", step.drill!!.id)
    }

    @Test fun aScoreThatStaysLowSuggestsAnotherSkillAndOneThatRisesDoesNot() {
        val low = listOf(scored("a", 1, .6f), scored("b", 10_000, .6f))
        assertEquals("two sessions are not enough", NextStepReason.WorkOn, next(low)!!.reason)
        val stuck = low + scored("c", 20_000, .6f)
        val answers = mapOf("serving_ready" to CanDo.Yes, "serving_control" to CanDo.Yes, "setting_ready" to CanDo.NotYet, "setting_control" to CanDo.NotYet)
        val step = next(stuck, SkillEvidence.from(answers, stuck))!!
        assertEquals(NextStepReason.OtherSkill, step.reason)
        assertEquals("the other skill with most to gain", Skill.Setting, step.skill)
        assertEquals("set-wall-reps", step.drill!!.id)
        assertNotEquals("still a skill when nothing is known", Skill.Receiving, next(stuck)!!.skill)
        assertEquals(NextStepReason.WorkOn, next(listOf(scored("a", 1, .75f), scored("b", 10_000, .6f), scored("c", 20_000, .5f)))!!.reason)
        assertEquals("one good session is enough to stay", NextStepReason.WorkOn, next(low + scored("c", 20_000, .3f))!!.reason)
    }

    @Test fun anOldResultKeepsItsOwnSuggestionAndNoRepsSuggestNothing() {
        val all = listOf(scored("a", 1, .6f), scored("b", 10_000, .6f), scored("c", 20_000, .6f))
        val old = all.first()
        val step = NextStepAdvisor.suggest(old, all, CoachAssessmentEngine.assess(old), emptyList(), emptyList(), open)!!
        assertEquals(NextStepReason.WorkOn, step.reason)
        val empty = old.copy(reps = emptyList())
        assertNull(NextStepAdvisor.suggest(empty, all, CoachAssessmentEngine.assess(empty), emptyList(), emptyList(), open))
    }
}
