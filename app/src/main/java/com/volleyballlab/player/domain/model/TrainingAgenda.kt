package com.volleyballlab.player.domain.model

import java.time.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class ScheduledTraining(val id: String, val date: String, val time: String, val skill: Skill,
    val targetReps: Int = 10, val completed: Boolean = false)

data class AgendaItem(val id: String, val skill: Skill, val time: String?, val targetReps: Int, val completed: Boolean)

/** The practices a player scheduled for themselves. Coach assignments have their own card ([HomeAssignment]). */
object TrainingAgenda {
    fun today(scheduled: List<ScheduledTraining>, today: LocalDate = LocalDate.now()): List<AgendaItem> =
        scheduled.filter { it.date == today.toString() }
            .map { AgendaItem(it.id, it.skill, it.time, it.targetReps, it.completed) }.sortedBy { it.time ?: "23:59" }
}
