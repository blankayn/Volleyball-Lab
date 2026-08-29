package com.volleyballlab.player.domain.model

import kotlin.math.roundToInt

/**
 * Training totals, derived rather than stored.
 *
 * Nothing here is a counter that gets incremented; every figure is recomputed from
 * the session list. A stored counter is a second source of truth that drifts the
 * first time a session fails to save, and then the profile quietly claims training
 * that no longer exists anywhere.
 *
 * ## Why this lives in the domain rather than beside one screen
 *
 * It started as a private type inside the Profile tab. The dashboard's Daily
 * progress row needs the same three numbers over a filtered slice of the same list,
 * and a second aggregator computing "total minutes trained" its own way is how two
 * screens start quietly disagreeing about one fact. The period filtering stays with
 * the caller - [from] has no opinion about which sessions it is given - so the
 * dashboard passes this week's and the Profile tab passes all of them.
 */
data class TrainingTotals(
    val sessionCount: Int,
    val repCount: Int,
    val totalMillis: Long,
    val bestScore: Int?,
    val averageScore: Int?,
    val mostTrained: Skill?,
) {
    /** Whole minutes, rounded down: a set of 90 seconds is one minute trained. */
    val totalMinutes: Int get() = (totalMillis / 60_000L).toInt()

    companion object {
        val Empty = TrainingTotals(0, 0, 0L, null, null, null)

        fun from(sessions: List<SessionResult>): TrainingTotals {
            if (sessions.isEmpty()) return Empty
            val scores = sessions.mapNotNull { it.formScore() }
            return TrainingTotals(
                sessionCount = sessions.size,
                repCount = sessions.sumOf { it.repCount },
                totalMillis = sessions.sumOf { it.durationMs },
                bestScore = scores.maxOrNull(),
                // Null rather than zero when nothing was measurable. A session
                // filmed from an angle that hid the athlete is not a session scored
                // zero, and an accuracy tile reading 0% would report a camera
                // problem as a technique problem.
                averageScore = scores.takeIf { it.isNotEmpty() }
                    ?.average()?.roundToInt()?.coerceIn(0, 100),
                mostTrained = sessions
                    .groupingBy { it.skill }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key,
            )
        }
    }
}
