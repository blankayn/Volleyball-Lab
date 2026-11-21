package com.volleyballlab.player.domain.model

import com.volleyballlab.player.data.skill.StaticDrillRepository
import com.volleyballlab.player.pose.analysis.LiveCoachingPlan
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrillFocusTest {

    @Test
    fun `every starter drill focuses on scored checks of its own skill`() = runTest {
        val repository = StaticDrillRepository()
        Skill.entries.forEach { skill ->
            val drills = repository.drillsFor(skill)
            drills.forEach { drill ->
                val focus = DrillFocus.cues(drill.id)
                assertTrue("${drill.id} has a focus", focus.isNotEmpty())
                val skillCues = FormCueCatalog.poseCues(skill).map { it.id }.toSet()
                assertTrue("${drill.id} focuses on its own skill's scored checks", skillCues.containsAll(focus))
            }
            // Drills of one skill do not all ask for the same thing.
            if (drills.size > 1) assertTrue(drills.map { DrillFocus.cues(it.id).first() }.toSet().size > 1)
        }
    }

    @Test
    fun `an unknown drill has no focus and leaves the plan as it was`() {
        val plan = LiveCoachingPlan(focusCueIds = setOf(CueId.ServeTossArmLift))
        assertEquals(plan, plan.withDrill("coach-authored-123"))
    }

    @Test
    fun `the drill's checks lead the live focus and the setup focus stays`() {
        val plan = LiveCoachingPlan(focusCueIds = setOf(CueId.PassKneeFlexion)).withDrill("receive-wall-platform")
        assertEquals(CueId.PassPlatformStraight, plan.focusCueIds.first())
        assertTrue(CueId.PassKneeFlexion in plan.focusCueIds)
        assertEquals("Your focus: forearm platform", plan.focusLabel)
    }

    @Test
    fun `the report practises the drill's own check first when it is clearly off`() {
        // Trunk lean is worse, but the reach drill is about reach height and arm extension.
        val reach = FormCueCatalog.byId(CueId.ServeReachHeight)!!
        val trunk = FormCueCatalog.byId(CueId.ServeTrunkLean)!!
        fun session(drillId: String) = SessionResult("s", Skill.Serving, drillId, 200, 3000L, (0 until 3).map { i ->
            RepResult(i, i * 1000L, i * 1000L + 500, i * 1000L + 900, FormCueCatalog.forSkill(Skill.Serving).map { cue ->
                val value = when (cue.id) {
                    CueId.ServeReachHeight -> reach.target.start - reach.warnTolerance / 2
                    CueId.ServeTrunkLean -> trunk.target.endInclusive + 30f
                    else -> (cue.target.start + cue.target.endInclusive) / 2
                }
                CueScore(cue.id, value, cue.score(value))
            })
        })
        assertEquals(CueId.ServeTrunkLean, CoachAssessmentEngine.assess(session("unknown")).corrections.first().evidence.cue.id)
        assertEquals(CueId.ServeReachHeight, CoachAssessmentEngine.assess(session("serve-jump-float")).corrections.first().evidence.cue.id)
    }
}
