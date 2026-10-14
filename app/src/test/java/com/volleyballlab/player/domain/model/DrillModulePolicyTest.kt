package com.volleyballlab.player.domain.model

import com.volleyballlab.player.data.skill.StaticDrillRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DrillModulePolicyTest {
    @Test fun freeGetsExactlyFiveKnownDrillsOneForEachSkill() = runBlocking {
        val repository = StaticDrillRepository()
        val all = Skill.All.flatMap { repository.drillsFor(it) }
        assertEquals(15, all.size)
        val free = all.filter { DrillAccessPolicy.canStart(it, UserPlan.Free) }
        assertEquals(DrillAccessPolicy.starterIds, free.map { it.id }.toSet())
        assertEquals(Skill.All.toSet(), free.map { it.skill }.toSet())
        assertEquals(5, free.size)
        assertTrue(all.all { DrillAccessPolicy.canStart(it, UserPlan.Pro) })
        assertFalse(DrillAccessPolicy.canStart(null, UserPlan.Pro))
        assertEquals(5, all.count { DrillAccessPolicy.canStart(it, null) })
    }
    @Test fun everyDrillHasUsableGuideAndConsistentDefaults() = runBlocking {
        val repository = StaticDrillRepository()
        Skill.All.flatMap { repository.drillsFor(it) }.forEach { drill ->
            val guide = requireNotNull(DrillGuideCatalog.forDrill(drill))
            assertTrue(guide.equipment.isNotBlank() && guide.preparation.isNotBlank())
            assertEquals(4, guide.steps.size)
            assertTrue(guide.steps.all { it.action.isNotBlank() })
            assertTrue(guide.mistakes.isNotEmpty())
            assertEquals(3, guide.defaultSets)
            assertEquals(30, guide.defaultRestSeconds)
        }
    }
    private fun draft(sets: Int = 3, rest: Int = 30) = DrillPracticeDraft("workout", "set-wall-reps", "Wall setting reps", Skill.Setting,
        DrillWorkoutConfig(sets, 20, rest), 1000)

    @Test fun onlyConfirmedSetsCreateRepsAndRestNeverCreatesReps() {
        var state = DrillPracticeEngine.startSet(draft())
        state = DrillPracticeEngine.tick(state, 12_000)
        assertEquals(0, state.totalReps)
        state = DrillPracticeEngine.completeSet(state, 17)
        assertEquals(17, state.totalReps)
        assertEquals(12_000L, state.sets.single().workMs)
        assertEquals(state, DrillPracticeEngine.completeSet(state, 17))
        state = DrillPracticeEngine.tick(state, 60_000)
        assertEquals(30_000L, state.restMs)
        assertEquals(DrillPracticeStage.Ready, state.stage)
        assertEquals(17, state.totalReps)
        assertEquals(state, DrillPracticeEngine.tick(state, 50_000))
    }
    @Test fun pauseBackgroundAndRecoveryExcludeTimeAndKeepConfirmedSets() {
        var state = DrillPracticeEngine.startSet(draft())
        state = DrillPracticeEngine.tick(state, 1000)
        state = DrillPracticeEngine.completeSet(state, 8)
        state = DrillPracticeEngine.tick(state, 5000)
        val paused = DrillPracticeEngine.recover(state)
        assertEquals(paused, DrillPracticeEngine.tick(paused, 100_000))
        val decoded = Json.decodeFromString<DrillPracticeDraft>(Json.encodeToString(paused))
        assertEquals(paused, decoded)
        val resumed = DrillPracticeEngine.resume(decoded)
        assertEquals(DrillPracticeStage.Resting, resumed.stage)
        assertEquals(25_000L, resumed.remainingRestMs)
        assertEquals(8, resumed.totalReps)
    }
    @Test fun skipRestAndFinalSetProduceCompletedRecord() {
        var state = DrillPracticeEngine.completeSet(DrillPracticeEngine.startSet(draft(2)), 20)
        state = DrillPracticeEngine.skipRest(state)
        state = DrillPracticeEngine.completeSet(DrillPracticeEngine.startSet(state), 19)
        assertEquals(DrillPracticeStage.Complete, state.stage)
        assertTrue(state.record(10_000).completed)
        assertEquals(39, state.record(10_000).totalReps)
        assertEquals(state, DrillPracticeEngine.startSet(state))
    }
    @Test fun partialAndZeroRestWorkoutsKeepActualNotTargetReps() {
        val partial = DrillPracticeEngine.completeSet(DrillPracticeEngine.startSet(draft(rest = 0)), 0)
        assertEquals(DrillPracticeStage.Ready, partial.stage)
        assertFalse(partial.record(10_000).completed)
        assertEquals(0, partial.record(10_000).totalReps)
        assertEquals(1, partial.record(10_000).sets.size)
    }
    @Test fun invalidConfigurationAndRepsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { DrillWorkoutConfig(0, 10, 30) }
        assertThrows(IllegalArgumentException::class.java) { DrillWorkoutConfig(3, 101, 30) }
        assertThrows(IllegalArgumentException::class.java) { DrillWorkoutConfig(3, 10, 121) }
        assertThrows(IllegalArgumentException::class.java) { DrillPracticeEngine.completeSet(draft(), -1) }
    }
}
