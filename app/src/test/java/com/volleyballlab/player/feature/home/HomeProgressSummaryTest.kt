package com.volleyballlab.player.feature.home

import com.volleyballlab.player.domain.model.*
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class HomeProgressSummaryTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 20)

    private fun session(id: String, skill: Skill, daysAgo: Long, score: Int?): SessionResult {
        val at = today.minusDays(daysAgo).atTime(10, 0).toInstant(zone).toEpochMilli() + id.hashCode() % 1000
        val reps = if (score == null) emptyList() else
            listOf(RepResult(0, 0, 250, 500, listOf(CueScore(FormCueCatalog.poseCues(skill).first().id, 0.5f, score / 100f))))
        return SessionResult(id, skill, "drill", at, 5000, reps)
    }

    @Test
    fun latestScoreChangeAndSkillsFollowTheMockup() {
        val summary = HomeProgressSummary.from(listOf(
            session("a", Skill.Spiking, 6, 79),
            session("b", Skill.Receiving, 4, 50),
            session("c", Skill.Setting, 2, 55),
            session("d", Skill.Spiking, 0, 90),
        ), zone, today)

        assertEquals(Skill.Spiking, summary.latestSkill)
        assertEquals(90, summary.latestScore)
        assertEquals(11, summary.change)
        assertEquals(79, summary.firstScore)
        assertEquals(90, summary.bestScore)
        assertEquals(listOf(SkillScore(Skill.Spiking, 90), SkillScore(Skill.Setting, 55), SkillScore(Skill.Receiving, 50)), summary.skills)
        assertEquals(listOf(Skill.Serving, Skill.Blocking), summary.unscored)
    }

    @Test
    fun weekCoversSevenDaysEndingToday() {
        val summary = HomeProgressSummary.from(listOf(
            session("a", Skill.Serving, 0, 70),
            session("b", Skill.Serving, 0, null),
            session("c", Skill.Setting, 3, 60),
            session("d", Skill.Setting, 9, 60),
        ), zone, today)

        assertEquals(7, summary.week.size)
        assertEquals(today, summary.week.last().date)
        assertEquals(2, summary.week.last().sessions)
        assertEquals(1, summary.week[3].sessions)
        assertEquals(3, summary.weekSessions)
    }

    @Test
    fun noChangeChipUntilTwoScoredSessions() {
        val one = HomeProgressSummary.from(listOf(session("a", Skill.Serving, 0, 70)), zone, today)
        assertNull(one.change)
        val empty = HomeProgressSummary.from(emptyList(), zone, today)
        assertNull(empty.latestScore)
        assertTrue(empty.skills.isEmpty())
        assertEquals(Skill.entries.toList(), empty.unscored)
    }
}
