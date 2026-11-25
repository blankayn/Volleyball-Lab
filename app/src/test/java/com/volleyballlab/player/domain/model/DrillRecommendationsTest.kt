package com.volleyballlab.player.domain.model

import com.volleyballlab.player.data.skill.StaticDrillRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrillRecommendationsTest {
    private val drills = runBlocking { StaticDrillRepository().let { repo -> Skill.All.flatMap { repo.drillsFor(it) } } }
    private val pro: (Drill) -> Boolean = { DrillAccessPolicy.canStart(it, UserPlan.Pro) }
    private val free: (Drill) -> Boolean = { DrillAccessPolicy.canStart(it, UserPlan.Free) }

    private fun pick(
        canStart: (Drill) -> Boolean = pro,
        focus: SkillRecommendation? = null,
        position: CourtPosition? = null,
        sessions: List<SessionResult> = emptyList(),
        workouts: List<DrillPracticeRecord> = emptyList(),
    ) = DrillRecommendations.pick(drills, canStart, focus, position, sessions, workouts)

    private fun session(drillId: String) = drills.first { it.id == drillId }.let {
        SessionResult("s-$drillId-${it.hashCode()}", it.skill, it.id, 0, 0, emptyList())
    }

    @Test fun proBeginnerGetsTheFocusStarterThenTheNextStepUp() {
        val picks = pick(focus = SkillRecommendation(Skill.Serving, SkillLevel.Beginner, null))
        assertEquals("serve-standing-float", picks[0].drill.id)
        assertEquals(DrillPickReason.Focus(Skill.Serving, SkillLevel.Beginner, false), picks[0].reason)
        assertEquals("serve-jump-float", picks[1].drill.id)
        assertEquals(DrillPickReason.NextStep(picks[0].drill), picks[1].reason)
        assertEquals(DrillRecommendations.MAX_PICKS, picks.size)
    }

    @Test fun measuredAdvancedLevelGetsTheAdvancedDrill() {
        val picks = pick(focus = SkillRecommendation(Skill.Spiking, SkillLevel.Advanced, 86))
        assertEquals("spike-four-step-lines", picks[0].drill.id)
        assertEquals(DrillPickReason.Focus(Skill.Spiking, SkillLevel.Advanced, true), picks[0].reason)
        // Nothing sits above advanced, so the other two come from other skills.
        assertTrue(picks.drop(1).none { it.drill.skill == Skill.Spiking })
    }

    @Test fun freeAccountsOnlySeeStartersOnePerSkill() {
        val picks = pick(canStart = free, focus = SkillRecommendation(Skill.Blocking, SkillLevel.Advanced, 90))
        assertTrue(picks.all { it.drill.id in DrillAccessPolicy.starterIds })
        assertEquals("block-ready-jumps", picks[0].drill.id)
        assertEquals(picks.size, picks.map { it.drill.skill }.toSet().size)
    }

    @Test fun otherSkillsComeLeastTrainedFirstAndSayWhetherTheyAreUntrained() {
        val sessions = listOf(session("receive-wall-platform"), session("receive-wall-platform"), session("set-wall-reps"))
        val picks = pick(focus = SkillRecommendation(Skill.Serving, SkillLevel.Advanced, 88), sessions = sessions)
        // Serving advanced has no next step, so spiking and blocking (never trained) fill in.
        assertEquals(listOf(Skill.Serving, Skill.Spiking, Skill.Blocking), picks.map { it.drill.skill })
        assertEquals(DrillPickReason.Untrained(Skill.Spiking), picks[1].reason)
    }

    @Test fun withNoFocusTheLeastTrainedSkillLeadsWhateverThePosition() {
        val sessions = listOf(session("serve-standing-float"), session("spike-standing-swing"))
        val picks = pick(position = CourtPosition.Libero, sessions = sessions)
        // Serving and spiking are trained, so receiving leads.
        assertEquals("receive-wall-platform", picks[0].drill.id)
        assertEquals(DrillPickReason.Untrained(Skill.Receiving), picks[0].reason)
        assertEquals("position never hides a skill", pick(sessions = sessions), picks)
    }

    @Test fun withinALevelTheLessPractisedDrillComesFirst() {
        // Receiving has two beginner drills; the one already practised waits.
        val picks = pick(focus = SkillRecommendation(Skill.Receiving, SkillLevel.Beginner, null),
            sessions = listOf(session("receive-short-court")))
        assertEquals("receive-wall-platform", picks[0].drill.id)
    }

    @Test fun nothingOpenMeansNoPicks() {
        assertTrue(pick(canStart = { false }).isEmpty())
    }
}
