package com.volleyballlab.player.domain.model

import kotlinx.serialization.Serializable

/** How often something is true for the player. Saved by name, so entries are never renamed. */
@Serializable
enum class CanDo(val label: String, /** Share of a statement this answer earns; null when it says nothing either way. */ val share: Float?) {
    Yes("Yes", 1f),
    Sometimes("Sometimes", .5f),
    NotYet("Not yet", 0f),
    NotTried("I haven't tried it", null),
}

/** One thing a player either does or does not do. [id] is saved, so it is never reused. */
data class SkillStatement(val id: String, val skill: Skill, val text: String)

/**
 * The skill check: what a player can do now, in their own words about each skill.
 *
 * ## Built so it cannot lean toward a skill or a kind of player
 *
 * - Every skill has the same four statements, about the same four things: getting
 *   ready, the movement, control, and doing it while moving or under pressure.
 * - Each is something a person can notice about themselves. None asks for a level,
 *   a playing style, a position, an age or years played.
 * - Nothing is worth more for being in one skill than another, and "I haven't tried
 *   it" is left out of the result instead of counting against the player.
 * - The skills are asked in a different order for different players
 *   ([orderFor]), so no skill is always first.
 *
 * Answers are the player's own view. Once sessions are recorded, what was measured
 * is used instead ([SkillEvidence]).
 */
object SkillCheck {
    private fun of(skill: Skill, ready: String, movement: String, control: String, moving: String) = listOf(
        "ready" to ready, "movement" to movement, "control" to control, "moving" to moving,
    ).map { (trait, text) -> SkillStatement("${skill.name.lowercase()}_$trait", skill, text) }

    val statements: List<SkillStatement> = of(Skill.Serving,
        "I start my serve from the same balanced stance.",
        "I reach up above my shoulder when I swing.",
        "I can send most of my serves where I want.",
        "I can serve the same way when it counts in a game.",
    ) + of(Skill.Receiving,
        "I bend my knees and get ready before I receive.",
        "I keep my forearms together and my elbows straight.",
        "I can send most of my passes where I want.",
        "I can receive after moving to a new spot.",
    ) + of(Skill.Setting,
        "I get my hands above my forehead before I set.",
        "I push with both hands at the same time.",
        "I can send most of my sets where I want.",
        "I can set after moving to a new spot.",
    ) + of(Skill.Spiking,
        "I draw my hitting elbow back before I swing.",
        "I swing with my arm long above my shoulder.",
        "I can send most of my spikes where I want.",
        "I can jump, swing and land with balance.",
    ) + of(Skill.Blocking,
        "I bend my knees with my hands up before I block.",
        "I reach up with both arms straight.",
        "I can keep my hands where I want when I block.",
        "I can block after moving to a new spot.",
    )

    const val PER_SKILL = 4

    fun forSkill(skill: Skill): List<SkillStatement> = statements.filter { it.skill == skill }

    /** A different, but for one player always the same, order of skills. */
    fun orderFor(uid: String?): List<Skill> = Skill.All.shuffled(kotlin.random.Random(uid.orEmpty().hashCode()))

    /** Answers to statements that no longer exist are dropped. */
    fun known(answers: Map<String, CanDo>): Map<String, CanDo> = answers.filterKeys { id -> statements.any { it.id == id } }
}

/** Where a skill's number comes from. */
enum class EvidenceSource { Measured, Answers, Unknown }

/**
 * What is known about one skill.
 *
 * [strength] is 0..100: the mean form score of the latest scored sessions when there
 * are any, otherwise the share of the player's answers. It is never a blend, so it is
 * always clear which one is being shown. It is null when neither says enough.
 */
data class SkillEvidence(
    val skill: Skill,
    val measuredScore: Int?,
    val scoredSessions: Int,
    val yes: Int,
    val sometimes: Int,
    val notYet: Int,
    val notTried: Int,
    val lastPractisedEpochMs: Long?,
) {
    val answered: Int get() = yes + sometimes + notYet
    val answerShare: Int? get() = if (answered < MIN_ANSWERED) null
        else Math.round((yes * CanDo.Yes.share!! + sometimes * CanDo.Sometimes.share!!) / answered * 100f)
    val source: EvidenceSource get() = when {
        measuredScore != null -> EvidenceSource.Measured
        answerShare != null -> EvidenceSource.Answers
        else -> EvidenceSource.Unknown
    }
    val strength: Int? get() = measuredScore ?: answerShare

    companion object {
        /** Fewer answers than this about a skill say too little to compare it. */
        const val MIN_ANSWERED = 2

        fun from(answers: Map<String, CanDo>, sessions: List<SessionResult>): List<SkillEvidence> = Skill.All.map { skill ->
            val given = SkillCheck.forSkill(skill).mapNotNull { answers[it.id] }
            val own = sessions.filter { it.skill == skill }
            SkillEvidence(skill, SkillRecommendation.recentScoreFor(skill, sessions), own.count { it.formScore() != null },
                given.count { it == CanDo.Yes }, given.count { it == CanDo.Sometimes }, given.count { it == CanDo.NotYet },
                given.count { it == CanDo.NotTried }, own.maxOfOrNull { it.startedAtEpochMs })
        }
    }
}

data class SkillSuggestion(val skill: Skill, val reason: String)

/** [source] is "local" for the app's own ordering and "groq" when the AI chose. */
data class SkillSuggestions(val items: List<SkillSuggestion>, val source: String) {
    val fromAi: Boolean get() = source == "groq"
}

/**
 * The skills to work on next, from [SkillEvidence] only.
 *
 * Lowest strength first. Ties go to the skill with fewer scored sessions, then the
 * one practised longest ago; a tie that is still exact is broken by rotating through
 * the skills as sessions add up, so no skill wins every time. Position, age and
 * gender are never used.
 */
object NextSkills {
    const val MAX = 2

    fun rank(evidence: List<SkillEvidence>): List<SkillEvidence> {
        val turn = evidence.sumOf { it.scoredSessions }
        return evidence.filter { it.strength != null }.sortedWith(
            compareBy<SkillEvidence> { it.strength }.thenBy { it.scoredSessions }.thenBy { it.lastPractisedEpochMs ?: 0L }
                .thenBy { Math.floorMod(it.skill.ordinal - turn, evidence.size) },
        )
    }

    fun suggest(evidence: List<SkillEvidence>): SkillSuggestions {
        val known = rank(evidence).take(MAX)
        val untried = evidence.filter { it.strength == null && it.notTried > 0 }
            .sortedBy { Math.floorMod(it.skill.ordinal - evidence.sumOf { e -> e.scoredSessions }, evidence.size) }
        return SkillSuggestions((known + untried.take((MAX - known.size).coerceAtLeast(0))).map { SkillSuggestion(it.skill, reason(it)) }, "local")
    }

    /** The reason is the evidence itself. */
    fun reason(evidence: SkillEvidence): String = when (evidence.source) {
        EvidenceSource.Measured -> if (evidence.scoredSessions == 1) "Form score ${evidence.measuredScore} in your scored session."
            else "Form score ${evidence.measuredScore} across your latest ${minOf(evidence.scoredSessions, SkillRecommendation.RECENT_SESSIONS)} scored sessions."
        EvidenceSource.Answers -> "You answered Yes to ${evidence.yes} of ${evidence.answered} statements you could answer."
        EvidenceSource.Unknown -> "You haven't tried this yet."
    }
}
