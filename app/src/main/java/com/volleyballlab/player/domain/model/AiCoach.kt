package com.volleyballlab.player.domain.model

/** Measurements the language model may explain. The pose engine remains authoritative. */
data class AiCoachRequest(
    val focusCueId: CueId,
    val skill: Skill,
    val scoreOutOfHundred: Int,
    val measuredReps: Int,
    val sessionCount: Int,
    val unmeasuredPercent: Int,
)

/** Short, validated copy shown beside the deterministic coaching result. */
data class AiCoachMessage(
    val headline: String,
    val explanation: String,
    val nextAction: String,
)
