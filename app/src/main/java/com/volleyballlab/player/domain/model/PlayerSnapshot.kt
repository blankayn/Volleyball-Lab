package com.volleyballlab.player.domain.model

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One skill's standing for one player, as the coach's breakdown row shows it. */
data class SkillStanding(
    val skill: Skill,
    val sessionCount: Int,
    val meanScore: Int?,
    val repCount: Int,
) {
    val isMeasured: Boolean get() = meanScore != null
}

/**
 * Everything the coach sees about one player, derived from that player's sessions.
 *
 * ## What the storyboard asks for, and which parts are honest to give
 *
 * The storyboard's player-detail screen lists a thirty-day form trend, a
 * consistency score, session history, a skill breakdown, and "an AI-recommended
 * corrective drill generated from patterns detected in the athlete's recorded
 * movements". Four of those five are arithmetic over stored sessions and are here.
 *
 * The fifth is not a model. [weakestCue] picks the cue with the lowest pooled mean
 * across the player's history, which is the same rule the player's own Coach tab
 * uses, and the screen presents it as "lowest-scoring cue" rather than as a
 * generated recommendation. Calling a `minByOrNull` an AI recommendation in front
 * of a panel is the kind of claim that gets a capstone sent back, and conflict 8 -
 * whether Coach Mode is AI personalisation or a human reviewing remotely - is
 * still open besides.
 *
 * @param trendDelta change in mean form score between the older and newer halves
 *   of the last [TREND_WINDOW_DAYS] days, or null when there is not enough on both
 *   sides to compare. Null is a real answer and the UI prints a dash for it; a
 *   trend computed from one session against nothing is a made-up number.
 * @param consistency `0f..1f`, higher meaning less spread between sessions. See
 *   [consistencyOf] for what it actually measures.
 */
data class PlayerSnapshot(
    val member: RosterMember,
    val totals: TrainingTotals,
    val recentSessions: List<SessionResult>,
    val standings: List<SkillStanding>,
    val trendDelta: Int?,
    val consistency: Float?,
    val weakestCue: FormCue?,
    val weakestCueSkill: Skill?,
    val openAssignments: Int,
    val pendingSubmissions: Int,
) {
    val hasTraining: Boolean get() = totals.sessionCount > 0

    val consistencyOutOfHundred: Int?
        get() = consistency?.let { (it * 100).roundToInt().coerceIn(0, 100) }

    companion object {
        /** The storyboard says thirty days, so thirty days it is. */
        const val TREND_WINDOW_DAYS = 30

        const val TREND_WINDOW_MS = TREND_WINDOW_DAYS * 24L * 60L * 60L * 1000L

        /** How many sessions the player-detail history list shows. */
        const val HISTORY_SIZE = 8

        /**
         * A trend needs at least this many scored sessions on each side of the
         * midpoint before it is worth printing.
         */
        const val MIN_SESSIONS_PER_HALF = 2

        /**
         * The spread, in score points, that maps to a consistency of zero.
         *
         * A standard deviation of forty points across sessions is about as
         * inconsistent as a scored athlete gets - it means good sets and unusable
         * sets in the same week. Anything at or beyond it reads the same on the
         * gauge, so the useful resolution stays in the 0..40 band where real
         * athletes actually sit.
         */
        const val CONSISTENCY_FLOOR_SD = 40f

        fun build(
            member: RosterMember,
            sessions: List<SessionResult>,
            openAssignments: Int,
            pendingSubmissions: Int,
            nowEpochMs: Long,
        ): PlayerSnapshot {
            val ordered = sessions.sortedByDescending { it.startedAtEpochMs }
            val window = ordered.filter { nowEpochMs - it.startedAtEpochMs <= TREND_WINDOW_MS }

            val weakest = weakestCueOf(sessions)

            return PlayerSnapshot(
                member = member,
                totals = TrainingTotals.from(sessions),
                recentSessions = ordered.take(HISTORY_SIZE),
                standings = Skill.All.map { skill ->
                    val forSkill = sessions.filter { it.skill == skill }
                    val scores = forSkill.mapNotNull { it.formScore() }
                    SkillStanding(
                        skill = skill,
                        sessionCount = forSkill.size,
                        meanScore = scores.takeIf { it.isNotEmpty() }
                            ?.average()?.roundToInt()?.coerceIn(0, 100),
                        repCount = forSkill.sumOf { it.repCount },
                    )
                },
                trendDelta = trendOf(window),
                consistency = consistencyOf(ordered.mapNotNull { it.formScore() }),
                weakestCue = weakest?.first,
                weakestCueSkill = weakest?.second,
                openAssignments = openAssignments,
                pendingSubmissions = pendingSubmissions,
            )
        }

        /**
         * Newer half minus older half, in score points.
         *
         * Split by count rather than by date so that a player who trained hard for
         * a week and then stopped still gets a comparison, instead of an empty
         * newer half and a null. The window has already been clipped to
         * [TREND_WINDOW_DAYS] by the caller, which is where the date filtering
         * belongs.
         */
        fun trendOf(windowSessions: List<SessionResult>): Int? {
            val scored = windowSessions
                .sortedBy { it.startedAtEpochMs }
                .mapNotNull { it.formScore() }
            if (scored.size < MIN_SESSIONS_PER_HALF * 2) return null
            val midpoint = scored.size / 2
            val older = scored.take(midpoint).average()
            val newer = scored.drop(midpoint).average()
            return (newer - older).roundToInt()
        }

        /**
         * How settled the player's scores are, `0f..1f`.
         *
         * Population standard deviation of the per-session form scores, inverted
         * and clamped against [CONSISTENCY_FLOOR_SD]. Deliberately *not* a
         * rolling-window measure: a coach reading this wants "is this athlete
         * repeatable", which is a property of their whole record, not of their
         * last four sets.
         *
         * Null with fewer than two scored sessions, because one session has no
         * spread and reporting it as perfect consistency would be a lie that
         * flatters every beginner on their first day.
         */
        fun consistencyOf(scores: List<Int>): Float? {
            if (scores.size < 2) return null
            val mean = scores.average()
            val variance = scores.sumOf { score ->
                val delta = score - mean
                delta * delta
            } / scores.size
            val sd = sqrt(variance).toFloat()
            return (1f - (sd / CONSISTENCY_FLOOR_SD)).coerceIn(0f, 1f)
        }

        /**
         * The player's lowest-scoring cue across every session, with its skill.
         *
         * Pooled per cue across the whole history and floored at
         * [SessionResult.MIN_REPS_FOR_RECOMMENDATION] measurements, matching the
         * player-side rule exactly. The two screens must agree: a coach told to
         * work on platform angle while the athlete's own app is telling them to
         * work on knee bend is worse than either screen saying nothing.
         */
        fun weakestCueOf(sessions: List<SessionResult>): Pair<FormCue, Skill>? {
            data class Pooled(val skill: Skill, val scores: MutableList<Float>)

            val pooled = mutableMapOf<CueId, Pooled>()
            sessions.forEach { session ->
                session.reps.forEach { rep ->
                    rep.cueScores.forEach { cueScore ->
                        val score = cueScore.score ?: return@forEach
                        pooled
                            .getOrPut(cueScore.cueId) { Pooled(session.skill, mutableListOf()) }
                            .scores += score
                    }
                }
            }

            val worst = pooled
                .filterValues { it.scores.size >= SessionResult.MIN_REPS_FOR_RECOMMENDATION }
                .minByOrNull { (_, value) -> value.scores.average() }
                ?: return null

            val cue = FormCueCatalog.forSkill(worst.value.skill)
                .firstOrNull { it.id == worst.key }
                ?: return null
            return cue to worst.value.skill
        }
    }
}

/**
 * Team-wide figures for the coach dashboard.
 *
 * @param averageFormScore mean of every active player's own mean, not of every
 *   session. Otherwise the player who films thirty sets a week decides the team
 *   average and the four who film two each barely register, which is the wrong
 *   answer to "how is the squad doing".
 * @param safetyWatchCount how many *players* have at least one safety cue in a
 *   watch state, not how many flags exist. A coach acts on people.
 */
data class TeamSummary(
    val activePlayers: Int,
    val pendingReviews: Int,
    val averageFormScore: Int?,
    val sessionsThisWeek: Int,
    val safetyWatchCount: Int,
) {
    companion object {
        val Empty = TeamSummary(0, 0, null, 0, 0)

        const val WEEK_MS = 7L * 24L * 60L * 60L * 1000L

        fun from(
            snapshots: List<PlayerSnapshot>,
            pendingReviews: Int,
            nowEpochMs: Long,
        ): TeamSummary {
            val active = snapshots.filter { it.member.isActive }
            val means = active.mapNotNull { it.totals.averageScore }
            return TeamSummary(
                activePlayers = active.size,
                pendingReviews = pendingReviews,
                averageFormScore = means.takeIf { it.isNotEmpty() }
                    ?.average()?.roundToInt()?.coerceIn(0, 100),
                sessionsThisWeek = active.sumOf { snapshot ->
                    snapshot.recentSessions.count { nowEpochMs - it.startedAtEpochMs <= WEEK_MS }
                },
                safetyWatchCount = active.count { snapshot ->
                    snapshot.recentSessions.any { SessionReview.from(it).hasSafetyWatch }
                },
            )
        }
    }
}

/** Formats a trend delta the way the coach screens print it: `+4`, `-7`, `0`. */
fun formatTrend(delta: Int): String = when {
    delta > 0 -> "+$delta"
    delta < 0 -> "-${abs(delta)}"
    else -> "0"
}
