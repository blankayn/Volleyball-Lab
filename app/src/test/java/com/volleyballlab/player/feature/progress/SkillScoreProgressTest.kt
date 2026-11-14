package com.volleyballlab.player.feature.progress

import com.volleyballlab.player.domain.model.CueScore
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.RepResult
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import org.junit.Assert.*
import org.junit.Test

class SkillScoreProgressTest {
    @Test fun `comparison ignores intervening sessions of another skill`() {
        val sessions = listOf(
            session("serve-before", Skill.Serving, 1, 0.4f),
            session("setting", Skill.Setting, 2, 0.9f),
            session("serve-latest", Skill.Serving, 3, 0.6f),
        )
        val progress = SkillScoreProgress.from(Skill.Serving, sessions.reversed())
        assertEquals(listOf("serve-before", "serve-latest"), progress.points.map { it.sessionId })
        assertEquals(40, progress.previous?.score)
        assertEquals(60, progress.latest?.score)
        assertEquals(20, progress.change)
    }

    @Test fun `chart sorts by date and contains only seven most recent scores`() {
        val sessions = (1..12).map { session("s$it", Skill.Setting, it.toLong(), it / 20f) }
        val progress = SkillScoreProgress.from(Skill.Setting, sessions.reversed())
        assertEquals(7, progress.points.size)
        assertEquals((6..12).map { "s$it" }, progress.points.map { it.sessionId })
        assertEquals(12L, progress.latest?.startedAtEpochMs)
    }

    @Test fun `zero is retained and missing measurements are omitted`() {
        val progress = SkillScoreProgress.from(Skill.Blocking, listOf(
            session("zero", Skill.Blocking, 1, 0f),
            session("missing", Skill.Blocking, 2, null),
        ))
        assertEquals(1, progress.points.size)
        assertEquals(0, progress.latest?.score)
        assertNull(progress.previous)
        assertNull(progress.change)
    }

    @Test fun `default skill uses latest scored session rather than latest unmeasured capture`() {
        val sessions = listOf(
            session("serve", Skill.Serving, 1, 0.6f),
            session("set", Skill.Setting, 2, 0.8f),
            session("spike-no-score", Skill.Spiking, 3, null),
        )
        assertEquals(Skill.Setting, SkillScoreProgress.defaultSkill(sessions))
        assertEquals(Skill.Setting, SkillScoreProgress.defaultSkill(sessions.reversed()))
    }

    @Test fun `empty selected skill has no fabricated score or focus`() {
        val progress = SkillScoreProgress.from(Skill.Receiving,
            listOf(session("set", Skill.Setting, 1, 0.8f)))
        assertTrue(progress.points.isEmpty())
        assertNull(progress.latest)
        assertNull(progress.previous)
        assertNull(progress.focusCue)
        assertEquals(Skill.Serving, SkillScoreProgress.defaultSkill(emptyList()))
    }

    @Test fun `focus uses latest selected session and requires repeated measurements`() {
        val previous = session("older", Skill.Serving, 1, 0.1f, 3)
        val latest = session("latest", Skill.Serving, 2, 0.9f, 2)
        assertNull(SkillScoreProgress.from(Skill.Serving, listOf(previous, latest)).focusCue)
        val enough = latest.copy(reps = session("fixture", Skill.Serving, 2, 0.5f, 3).reps)
        assertNotNull(SkillScoreProgress.from(Skill.Serving, listOf(previous, enough)).focusCue)
    }

    @Test fun `equal timestamps use stable ordering regardless of repository order`() {
        val sessions = listOf(session("b", Skill.Serving, 1, 0.5f), session("a", Skill.Serving, 1, 0.4f))
        assertEquals(
            SkillScoreProgress.from(Skill.Serving, sessions).points,
            SkillScoreProgress.from(Skill.Serving, sessions.reversed()).points,
        )
    }

    private fun session(id: String, skill: Skill, at: Long, score: Float?, count: Int = 1) = SessionResult(
        id = id,
        skill = skill,
        drillId = "fixture",
        startedAtEpochMs = at,
        durationMs = 1_000,
        reps = (0 until count).map { index -> RepResult(index, 0, 0, 1_000,
            FormCueCatalog.forSkill(skill).map { CueScore(it.id, score?.let { 0f }, score) }) },
    )
}
