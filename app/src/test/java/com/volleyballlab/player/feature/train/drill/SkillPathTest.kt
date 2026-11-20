package com.volleyballlab.player.feature.train.drill

import com.volleyballlab.player.data.skill.StaticDrillRepository
import com.volleyballlab.player.domain.model.Drill
import com.volleyballlab.player.domain.model.DrillAccessPolicy
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.model.UserPlan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillPathTest {
    private val serving = runBlocking { StaticDrillRepository().drillsFor(Skill.Serving) }
    private val pro: (Drill) -> Boolean = { DrillAccessPolicy.canStart(it, UserPlan.Pro) }
    private val free: (Drill) -> Boolean = { DrillAccessPolicy.canStart(it, UserPlan.Free) }

    private fun best(score: Int?, sessions: Int = 1, manual: Int = 0) =
        DrillPracticeProgress(manual, manual, manual * 10, sessions, score, 1L)

    private fun statuses(progress: Map<String, DrillPracticeProgress>, canStart: (Drill) -> Boolean = pro) =
        SkillPath.steps(serving, progress, canStart).map { it.status }

    @Test fun aNewAthleteStartsOnStepOneEasiestFirst() {
        val steps = SkillPath.steps(serving.reversed(), emptyMap(), pro)
        assertEquals(listOf("serve-standing-float", "serve-jump-float", "serve-topspin-jump"), steps.map { it.drill.id })
        assertEquals(listOf(1, 2, 3), steps.map { it.number })
        assertEquals(listOf(PathStepStatus.Current, PathStepStatus.UpNext, PathStepStatus.UpNext), steps.map { it.status })
    }

    @Test fun aStepIsStrongFromEightyAndThenTheNextOneIsCurrent() {
        assertTrue(SkillPath.isStrong(best(80)))
        assertFalse(SkillPath.isStrong(best(79)))
        assertEquals(listOf(PathStepStatus.Strong, PathStepStatus.Current, PathStepStatus.UpNext),
            statuses(mapOf("serve-standing-float" to best(84))))
        assertEquals(listOf(PathStepStatus.Current, PathStepStatus.UpNext, PathStepStatus.UpNext),
            statuses(mapOf("serve-standing-float" to best(79))))
    }

    @Test fun manualWorkoutsAloneNeverMakeAStepStrong() {
        assertEquals(PathStepStatus.Current,
            statuses(mapOf("serve-standing-float" to best(null, sessions = 0, manual = 6))).first())
    }

    @Test fun everyStepStrongLeavesNoCurrentStep() {
        val all = serving.associate { it.id to best(90) }
        assertEquals(List(3) { PathStepStatus.Strong }, statuses(all))
    }

    @Test fun freeAccountsSeeTheStarterAsCurrentAndTheRestLocked() {
        assertEquals(listOf(PathStepStatus.Current, PathStepStatus.Locked, PathStepStatus.Locked), statuses(emptyMap(), free))
        assertEquals(listOf(PathStepStatus.Strong, PathStepStatus.Locked, PathStepStatus.Locked),
            statuses(mapOf("serve-standing-float" to best(88)), free))
    }
}
