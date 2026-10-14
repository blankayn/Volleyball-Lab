package com.volleyballlab.player.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

data class DailyProgress(val date: LocalDate, val sessions: Int, val completedReps: Int,
    val validReps: Int, val durationMs: Long, val meanScore: Int?)

object WeeklyProgress {
    /** Missing days have no score, rather than a fabricated zero. */
    fun from(sessions: List<SessionResult>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<DailyProgress> =
        (6L downTo 0L).map { offset ->
            val date = today.minusDays(offset)
            val onDay = sessions.filter { Instant.ofEpochMilli(it.startedAtEpochMs).atZone(zone).toLocalDate() == date }
            val scores = onDay.mapNotNull { it.formScore() }
            DailyProgress(date, onDay.size, onDay.sumOf { it.repCount },
                onDay.sumOf { s -> s.reps.count { it.isValidFor(s.skill) } },
                onDay.sumOf { it.durationMs.coerceAtLeast(0) }, scores.takeIf { it.isNotEmpty() }?.average()?.roundToInt())
        }
}

fun RepResult.isValidFor(skill: Skill, threshold: Int = 80, minimumCoverage: Float = .75f): Boolean {
    val catalog = FormCueCatalog.forSkill(skill).associateBy { it.id }
    val measured = cueScores.filter { it.measured?.isFinite() == true && it.score?.isFinite() == true }.distinctBy { it.cueId }
    val coverage = measured.sumOf { catalog[it.cueId]?.weight?.toDouble() ?: 0.0 }
    return coverage + 1e-6 >= minimumCoverage && (formScore(skill) ?: -1) >= threshold
}
