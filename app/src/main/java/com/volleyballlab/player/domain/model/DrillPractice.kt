package com.volleyballlab.player.domain.model

import kotlinx.serialization.Serializable

object DrillAccessPolicy {
    val starterIds = setOf("serve-standing-float", "receive-wall-platform", "set-wall-reps", "spike-standing-swing", "block-ready-jumps")
    fun canStart(drill: Drill?, plan: UserPlan?): Boolean =
        drill != null && (drill.id in starterIds || plan == UserPlan.Pro)
}

@Serializable
data class DrillWorkoutConfig(val sets: Int = 3, val reps: Int = 10, val restSeconds: Int = 30) {
    init { require(sets in 1..5 && reps in 1..100 && restSeconds in 0..120) }
}

@Serializable enum class DrillPracticeStage { Ready, Working, Resting, Paused, Complete }
@Serializable data class DrillPracticeSet(val number: Int, val reps: Int, val workMs: Long) {
    init { require(number in 1..5 && reps in 0..100 && workMs >= 0) }
}

@Serializable
data class DrillPracticeDraft(
    val id: String,
    val drillId: String,
    val drillName: String,
    val skill: Skill,
    val config: DrillWorkoutConfig,
    val startedAtEpochMs: Long,
    val sets: List<DrillPracticeSet> = emptyList(),
    val stage: DrillPracticeStage = DrillPracticeStage.Ready,
    val pausedFrom: DrillPracticeStage = DrillPracticeStage.Ready,
    val workMs: Long = 0,
    val restMs: Long = 0,
    val currentSetWorkMs: Long = 0,
    val remainingRestMs: Long = 0,
) {
    val totalReps: Int get() = sets.sumOf { it.reps }
    val setNumber: Int get() = (sets.size + 1).coerceAtMost(config.sets)
    fun record(finishedAt: Long): DrillPracticeRecord = DrillPracticeRecord(
        id, drillId, drillName, skill, config, startedAtEpochMs, finishedAt, sets,
        workMs, restMs, sets.size == config.sets,
    )
}

@Serializable
data class DrillPracticeRecord(
    val id: String,
    val drillId: String,
    val drillName: String,
    val skill: Skill,
    val config: DrillWorkoutConfig,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val sets: List<DrillPracticeSet>,
    val workMs: Long,
    val restMs: Long,
    val completed: Boolean,
) {
    val totalReps: Int get() = sets.sumOf { it.reps }
    val durationMs: Long get() = workMs + restMs
}

/** Pure state transitions. Only confirmed sets create repetitions. */
object DrillPracticeEngine {
    fun startSet(draft: DrillPracticeDraft) = if (draft.stage == DrillPracticeStage.Ready)
        draft.copy(stage = DrillPracticeStage.Working, currentSetWorkMs = 0) else draft

    fun tick(draft: DrillPracticeDraft, elapsedMs: Long): DrillPracticeDraft {
        val delta = elapsedMs.coerceAtLeast(0)
        return when (draft.stage) {
            DrillPracticeStage.Working -> draft.copy(workMs = draft.workMs + delta, currentSetWorkMs = draft.currentSetWorkMs + delta)
            DrillPracticeStage.Resting -> {
                val consumed = delta.coerceAtMost(draft.remainingRestMs)
                val remaining = draft.remainingRestMs - consumed
                draft.copy(restMs = draft.restMs + consumed, remainingRestMs = remaining,
                    stage = if (remaining == 0L) DrillPracticeStage.Ready else DrillPracticeStage.Resting)
            }
            else -> draft
        }
    }

    fun completeSet(draft: DrillPracticeDraft, reps: Int): DrillPracticeDraft {
        require(reps in 0..100)
        if (draft.stage != DrillPracticeStage.Working) return draft
        val sets = draft.sets + DrillPracticeSet(draft.setNumber, reps, draft.currentSetWorkMs)
        val stage = when {
            sets.size == draft.config.sets -> DrillPracticeStage.Complete
            draft.config.restSeconds == 0 -> DrillPracticeStage.Ready
            else -> DrillPracticeStage.Resting
        }
        return draft.copy(sets = sets, stage = stage, currentSetWorkMs = 0,
            remainingRestMs = if (stage == DrillPracticeStage.Resting) draft.config.restSeconds * 1000L else 0)
    }

    fun skipRest(draft: DrillPracticeDraft) = if (draft.stage == DrillPracticeStage.Resting)
        draft.copy(stage = DrillPracticeStage.Ready, remainingRestMs = 0) else draft

    fun pause(draft: DrillPracticeDraft) = if (draft.stage !in setOf(DrillPracticeStage.Paused, DrillPracticeStage.Complete))
        draft.copy(stage = DrillPracticeStage.Paused, pausedFrom = draft.stage) else draft

    fun resume(draft: DrillPracticeDraft) = if (draft.stage == DrillPracticeStage.Paused)
        draft.copy(stage = draft.pausedFrom) else draft

    fun recover(draft: DrillPracticeDraft) = pause(draft)
}
