package com.volleyballlab.player.feature.progress

import com.volleyballlab.player.domain.model.FormCue
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill

/** One real scored session; a zero is a score, while absent measurements are omitted. */
data class SkillScorePoint(val sessionId: String, val startedAtEpochMs: Long, val score: Int)

/** Same-skill score changes only. These are score differences, not ability classifications. */
data class SkillScoreProgress(
    val skill: Skill,
    val points: List<SkillScorePoint>,
    val focusCue: FormCue?,
) {
    val latest: SkillScorePoint? get() = points.lastOrNull()
    val previous: SkillScorePoint? get() = points.getOrNull(points.lastIndex - 1)
    val change: Int? get() = previous?.let { latest!!.score - it.score }

    companion object {
        const val WINDOW_SIZE = 7

        fun defaultSkill(sessions: List<SessionResult>): Skill = sessions
            .filter { it.formScore() != null }
            .maxWithOrNull(compareBy<SessionResult> { it.startedAtEpochMs }.thenBy { it.id })
            ?.skill ?: Skill.Serving

        fun from(skill: Skill, sessions: List<SessionResult>): SkillScoreProgress {
            val scored = sessions.asSequence().filter { it.skill == skill }
                .mapNotNull { session -> session.formScore()?.let { session to it } }
                .sortedWith(compareBy<Pair<SessionResult, Int>> { it.first.startedAtEpochMs }.thenBy { it.first.id })
                .toList().takeLast(WINDOW_SIZE)
            val latestSession = scored.lastOrNull()?.first
            val focus = latestSession?.weakestCue()?.let { id ->
                FormCueCatalog.forSkill(skill).firstOrNull { it.id == id }
            }
            return SkillScoreProgress(skill, scored.map { (session, score) ->
                SkillScorePoint(session.id, session.startedAtEpochMs, score)
            }, focus)
        }
    }
}
