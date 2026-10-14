package com.volleyballlab.player.domain.model

import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ScheduledTraining(val id: String, val date: String, val time: String, val skill: Skill,
    val targetReps: Int = 10, val completed: Boolean = false)

data class AgendaItem(val id: String, val title: String, val skill: Skill, val time: String?,
    val targetReps: Int, val completed: Boolean, val coachAssigned: Boolean, val overdue: Boolean = false)

object TrainingAgenda {
    fun today(scheduled: List<ScheduledTraining>, assignments: List<CoachAssignment>,
        today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<AgendaItem> {
        val local = scheduled.filter { it.date == today.toString() }.map {
            AgendaItem(it.id, it.skill.name, it.skill, it.time, it.targetReps, it.completed, false)
        }
        val coach = assignments.filter { a -> a.isOpen && a.dueAtEpochMs?.let {
            !Instant.ofEpochMilli(it).atZone(zone).toLocalDate().isAfter(today)
        } == true }.map { a ->
            val due = Instant.ofEpochMilli(requireNotNull(a.dueAtEpochMs)).atZone(zone)
            AgendaItem(a.id, a.title, a.skill, due.toLocalTime().toString().take(5), a.totalReps, false, true, due.toLocalDate().isBefore(today))
        }
        return (local + coach).sortedBy { it.time ?: "23:59" }
    }
}
