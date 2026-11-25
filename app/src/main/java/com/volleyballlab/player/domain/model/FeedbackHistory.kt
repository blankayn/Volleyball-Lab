package com.volleyballlab.player.domain.model

/** One check that was the top strength or correction in several of the sent sessions. */
data class RepeatedCue(val skill: Skill, val cueId: CueId, val sessions: Int, val of: Int)

/**
 * The sessions sent to a coach, in a few lines.
 *
 * [workOn] comes from the app's own measurements of those sessions, not from the
 * coach's words, and only when the same check led in at least [MIN_SESSIONS] of
 * them: one session is already on its own card.
 */
data class FeedbackHistory(val sent: Int, val reviewed: Int, val waiting: Int, val workOn: RepeatedCue?) {
    companion object {
        const val MIN_SESSIONS = 2

        fun of(submissions: List<SessionSubmission>, sessions: List<SessionResult>): FeedbackHistory {
            val sentIds = submissions.mapTo(mutableSetOf()) { it.sessionId }
            val sent = sessions.filter { it.id in sentIds }.distinctBy { it.id }
            val measured = sent.map { it.skill to SessionReview.from(it).corrections.firstOrNull()?.cue?.id }
            val workOn = measured.mapNotNull { (skill, id) -> id?.let { skill to it } }.groupingBy { it }.eachCount()
                .filterValues { it >= MIN_SESSIONS }.maxByOrNull { it.value }
                ?.let { (key, count) -> RepeatedCue(key.first, key.second, count, sent.count { it.skill == key.first }) }
            return FeedbackHistory(submissions.size, submissions.count { !it.isPending }, submissions.count { it.isPending }, workOn)
        }
    }
}
