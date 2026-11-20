package com.volleyballlab.player.domain.model

import kotlinx.serialization.Serializable

/** Confirmed training preferences; never measured ability or generated scores. */
@Serializable
data class TrainingSetup(
    val focusSkill: Skill,
    val weeklySessionGoal: Int,
    val goalText: String,
    val experience: ExperienceLevel?,
    val concern: String,
    val summary: String,
    val source: String,
    val starterDrillId: String,
    val completedAtEpochMs: Long,
    val reason: String = "",
    val questionId: String = "",
    val optionId: String = "",
) {
    init {
        require(weeklySessionGoal in 1..14)
        require(goalText.length <= 160 && concern.length <= 160 && summary.length <= 320 && reason.length <= 160)
        require(source in setOf("groq", "local"))
        require(questionId.length <= 64 && optionId.length <= 64)
        require(starterDrillId == starterFor(focusSkill))
        require(completedAtEpochMs in 0..4_102_444_800_000L)
    }
    companion object {
        fun starterFor(skill: Skill): String = when (skill) {
            Skill.Serving -> "serve-standing-float"
            Skill.Receiving -> "receive-wall-platform"
            Skill.Setting -> "set-wall-reps"
            Skill.Spiking -> "spike-standing-swing"
            Skill.Blocking -> "block-ready-jumps"
        }
    }
}

/** One private, versioned record also preserves explicit legacy assessment answers. */
@Serializable
data class SavedTrainingSetup(
    val version: Int = 1,
    val hasOnboarded: Boolean = true,
    val assessment: SkillAssessment = SkillAssessment.Unanswered,
    val plan: TrainingSetup? = null,
)
