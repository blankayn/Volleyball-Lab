package com.volleyballlab.player.feature.home

import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One skill's latest measured form score, for the "Your skills" bars. */
data class SkillScore(val skill: Skill, val score: Int)

/** One day of the "Weekly activity" chart. */
data class DayActivity(val date: LocalDate, val sessions: Int)

/**
 * What the home Progress section shows, worked out from the session history.
 *
 * @param latestScore the most recent session's form score, or null when it had none.
 * @param change latest minus the first scored session. Null until there are two scored
 *   sessions, so a single session never shows a "+0 pts" chip.
 * @param skills each skill's latest scored session, highest score first.
 * @param unscored skills with no scored session yet, in [Skill] order.
 * @param week the last seven days ending today, oldest first.
 */
data class HomeProgressSummary(
    val latestSkill: Skill?,
    val latestScore: Int?,
    val firstScore: Int?,
    val bestScore: Int?,
    val change: Int?,
    val skills: List<SkillScore>,
    val unscored: List<Skill>,
    val week: List<DayActivity>,
) {
    val weekSessions: Int get() = week.sumOf { it.sessions }

    companion object {
        fun from(
            sessions: List<SessionResult>,
            zone: ZoneId = ZoneId.systemDefault(),
            today: LocalDate = LocalDate.now(zone),
        ): HomeProgressSummary {
            val ordered = sessions.sortedBy { it.startedAtEpochMs }
            val latest = ordered.lastOrNull()
            val scored = ordered.mapNotNull { session -> session.formScore()?.let { session to it } }
            val latestScore = latest?.formScore()
            val firstScore = scored.firstOrNull()?.second
            val change = if (latestScore != null && firstScore != null && scored.size >= 2) latestScore - firstScore else null
            val perSkill = scored.groupBy { it.first.skill }.mapValues { (_, list) -> list.last().second }
            val skills = perSkill.map { (skill, score) -> SkillScore(skill, score) }
                .sortedWith(compareByDescending<SkillScore> { it.score }.thenBy { it.skill.ordinal })
            val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
            val counts = sessions.groupingBy { Instant.ofEpochMilli(it.startedAtEpochMs).atZone(zone).toLocalDate() }.eachCount()
            return HomeProgressSummary(
                latestSkill = latest?.skill,
                latestScore = latestScore,
                firstScore = firstScore,
                bestScore = scored.maxOfOrNull { it.second },
                change = change,
                skills = skills,
                unscored = Skill.entries.filter { it !in perSkill },
                week = days.map { DayActivity(it, counts[it] ?: 0) },
            )
        }
    }
}
