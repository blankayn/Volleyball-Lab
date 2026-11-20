package com.volleyballlab.player.domain.model

data class CueLearningChange(val cueId: CueId, val previousInRange: Int, val previousCount: Int,
    val currentInRange: Int, val currentCount: Int)
data class LearningProgress(val focus: CueId?, val change: CueLearningChange?, val skill: Skill?) {
    companion object {
        fun from(sessions: List<SessionResult>): LearningProgress {
            val ordered = sessions.sortedByDescending { it.startedAtEpochMs }
            val latest = ordered.firstOrNull() ?: return LearningProgress(null, null, null)
            fun values(session: SessionResult) = session.reps.flatMap { it.cueScores }
                .filter { it.measured?.isFinite() == true && it.score?.isFinite() == true }
                .groupBy { it.cueId }.filterValues { it.size >= 3 }
            val current = values(latest)
            val focus = current.filterValues { scores -> scores.count { it.score!! < 0.8f } >= 2 }
                .minByOrNull { (_, scores) -> scores.map { it.score!! }.average() }?.key
            // Same exercise and proven view coverage; never compare unlike sessions.
            val views = latest.angleStages.filter { it.liveRepCount > 0 }.map { it.angle }.toSet()
            val previous = ordered.drop(1).firstOrNull { it.skill == latest.skill && it.drillId == latest.drillId &&
                views.isNotEmpty() && CameraAngle.Unknown !in views &&
                it.angleStages.filter { stage -> stage.liveRepCount > 0 }.map { stage -> stage.angle }.toSet() == views }
            val old = previous?.let(::values).orEmpty()
            val id = current.keys.filter { it in old }.maxByOrNull { cue ->
                current.getValue(cue).map { it.score!! }.average() - old.getValue(cue).map { it.score!! }.average()
            }
            val change = id?.let { CueLearningChange(it, old.getValue(it).count { s -> s.score!! >= 0.8f }, old.getValue(it).size,
                current.getValue(it).count { s -> s.score!! >= 0.8f }, current.getValue(it).size) }
            return LearningProgress(focus, change, latest.skill)
        }
    }
}
