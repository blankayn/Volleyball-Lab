package com.volleyballlab.player.domain.model

import kotlinx.serialization.Serializable

/** The different ways the live coach can say one correction. Names are stored. */
enum class TeachingStyle { Direct, Rehearse, Reset, Short, Feel }

/** How often a style was used on one check, and how often the next reps got better. */
@Serializable
data class TeachingTally(val tried: Int = 0, val helped: Int = 0) {
    /** Between 0 and 1, and one half until something is known. */
    val rate: Float get() = (helped + 1f) / (tried + 2f)

    /** Old results fade, so a player who has changed is not held to them. */
    fun plus(worked: Boolean): TeachingTally {
        val next = TeachingTally(tried + 1, helped + if (worked) 1 else 0)
        return if (next.tried <= KEEP) next else TeachingTally(next.tried / 2, next.helped / 2)
    }

    private companion object { const val KEEP = 20 }
}

/** One correction the coach gave, and whether the next two measured reps were better. */
data class TeachingOutcome(val cueId: CueId, val style: TeachingStyle, val helped: Boolean)

/** A wrong attempt that was not counted, so it is in no saved session. */
@Serializable
data class MissedRep(val skill: String, val cue: String, val atEpochMs: Long)

/** What this phone remembers between sessions, beyond the sessions themselves. */
@Serializable
data class AdaptiveMemory(
    val misses: List<MissedRep> = emptyList(),
    /** Check name, then style name. */
    val teaching: Map<String, Map<String, TeachingTally>> = emptyMap(),
) {
    fun tally(cueId: CueId, style: TeachingStyle): TeachingTally = teaching[cueId.name]?.get(style.name) ?: TeachingTally()

    fun remember(skill: Skill, outcomes: List<TeachingOutcome>, missed: List<Pair<CueId, Long>>): AdaptiveMemory = copy(
        misses = (misses + missed.map { MissedRep(skill.name, it.first.name, it.second) }).takeLast(MAX_MISSES),
        teaching = outcomes.fold(teaching) { all, outcome ->
            val styles = all[outcome.cueId.name].orEmpty()
            all + (outcome.cueId.name to (styles + (outcome.style.name to (styles[outcome.style.name] ?: TeachingTally()).plus(outcome.helped))))
        },
    )

    /** The style that helped this player most on a check, once one has been tried at least twice and helped. */
    fun bestStyle(cueId: CueId): Pair<TeachingStyle, TeachingTally>? = TeachingStyle.entries
        .map { it to tally(cueId, it) }.filter { it.second.tried >= 2 && it.second.helped > 0 && it.second.rate >= 0.5f }
        .maxByOrNull { it.second.rate }

    private companion object { const val MAX_MISSES = 300 }
}

/** A human coach's decision on one rep. It outranks the camera. */
data class RepMark(val sessionId: String, val repNumber: Int, val right: Boolean, val wrongCues: Set<CueId>, val atEpochMs: Long)

enum class CueTrend { Learning, Solid, Improving, Slipping, WorkOn }

/**
 * How one check has been going.
 *
 * @param need what there is to gain from it: its weight times the share of recent reps
 *   that were wrong. Zero when nothing is known.
 */
data class LearnedCue(
    val cue: FormCue,
    val trend: CueTrend,
    val recentRight: Int,
    val recentTotal: Int,
    val earlierRight: Int,
    val earlierTotal: Int,
) {
    val recentShare: Float get() = if (recentTotal == 0) 0f else recentRight.toFloat() / recentTotal
    val need: Float get() = if (trend == CueTrend.Learning) 0f else cue.weight * (1f - recentShare)
    val needsWork: Boolean get() = trend == CueTrend.WorkOn || trend == CueTrend.Slipping || trend == CueTrend.Improving
}

/**
 * Learns from one player's own right and wrong reps, and from nobody else's.
 *
 * ## What it learns from
 *
 * Every measured check of every saved rep is one right or wrong answer: right at a score
 * of 0.8 or more, the same line the coach speaks at. Wrong attempts that were refused and
 * never saved count as wrong too ([MissedRep]). When a verified coach has marked a rep,
 * the mark replaces the camera's answer ([RepMark]).
 *
 * ## What it changes, and what it never changes
 *
 * It changes what is coached first, how it is said and what is practised next. It never
 * changes a measurement, a range, a score or what counts as a rep, so results stay
 * comparable between days and between players.
 *
 * ## Why it compares the last 20 with the 20 before
 *
 * It can be checked by hand, and it adapts within a session or two. One wrong rep cannot
 * change a trend, and an old habit is forgotten after 40 reps.
 */
object AdaptiveCoaching {
    const val RIGHT_AT = 0.8f
    const val WINDOW = 20
    const val MIN_REPS = 6
    const val SOLID = 0.8f
    const val CHANGE = 0.15f

    fun learn(
        skill: Skill,
        sessions: List<SessionResult>,
        memory: AdaptiveMemory = AdaptiveMemory(),
        marks: List<RepMark> = emptyList(),
        drillId: String? = null,
    ): List<LearnedCue> {
        val excluded = DrillFocus.excluded(drillId)
        val latestMark = marks.groupBy { it.sessionId to it.repNumber }.mapValues { (_, all) -> all.maxBy { it.atEpochMs } }
        return FormCueCatalog.poseCues(skill).filterNot { it.id in excluded }.map { cue ->
            val answers = mutableListOf<Pair<Long, Boolean>>()
            sessions.filter { it.skill == skill }.forEach { session ->
                // Reps carry the camera's clock, so they are placed from the session's first rep.
                val firstMs = session.reps.minOfOrNull { it.startMs } ?: 0L
                session.reps.forEach { rep ->
                    val mark = latestMark[session.id to rep.index + 1]
                    val score = rep.cueScores.firstOrNull { it.cueId == cue.id }?.score?.takeIf { it.isFinite() }
                    val right = when {
                        mark != null && cue.id in mark.wrongCues -> false
                        score == null -> null
                        mark?.right == true -> true
                        else -> score >= RIGHT_AT
                    }
                    if (right != null) answers += session.startedAtEpochMs + (rep.startMs - firstMs) to right
                }
            }
            memory.misses.filter { it.skill == skill.name && it.cue == cue.id.name }.forEach { answers += it.atEpochMs to false }
            val ordered = answers.sortedBy { it.first }.map { it.second }
            val recent = ordered.takeLast(WINDOW)
            val earlier = ordered.dropLast(WINDOW).takeLast(WINDOW)
            LearnedCue(cue, trend(recent, earlier), recent.count { it }, recent.size, earlier.count { it }, earlier.size)
        }
    }

    private fun trend(recent: List<Boolean>, earlier: List<Boolean>): CueTrend {
        if (recent.size < MIN_REPS) return CueTrend.Learning
        val now = recent.count { it }.toFloat() / recent.size
        val before = if (earlier.size < MIN_REPS) null else earlier.count { it }.toFloat() / earlier.size
        return when {
            before != null && before - now >= CHANGE -> CueTrend.Slipping
            now >= SOLID -> CueTrend.Solid
            before != null && now - before >= CHANGE -> CueTrend.Improving
            else -> CueTrend.WorkOn
        }
    }

    /** Checks to work on, most to gain first. */
    fun needs(learned: List<LearnedCue>): List<LearnedCue> = learned.filter { it.needsWork }.sortedByDescending { it.need }

    /**
     * What this session should coach first.
     *
     * The drill's own checks stay first, reordered by need, and a check the player has
     * made solid steps aside. When the whole drill is solid, the player's next weakest
     * checks take over, so a drill never keeps teaching what is already learned.
     */
    fun focus(drillCues: Set<CueId>, learned: List<LearnedCue>): Set<CueId> {
        val known = learned.associateBy { it.cue.id }
        val order = needs(learned).map { it.cue.id }
        val open = drillCues.filter { known[it]?.trend != CueTrend.Solid }
        val ranked = order.filter { it in open } + open.filter { it !in order }
        return LinkedHashSet(ranked.ifEmpty { order.ifEmpty { drillCues.toList() } })
    }
}
