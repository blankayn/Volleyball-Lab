package com.volleyballlab.player.domain.model

import androidx.annotation.StringRes
import com.volleyballlab.player.R

/**
 * How an athlete rates themselves at one skill.
 *
 * Three levels rather than a five- or ten-point scale. The question is being asked
 * once, in under a minute, by someone who has just installed the app and wants to
 * start training - and the only thing the answer is used for is ordering five
 * skills worst-first. A finer scale would collect precision the app then throws
 * away, and would make the onboarding longer to no end.
 *
 * Persisted by [Enum.name], so the same wire-format caution as [CourtPosition]
 * applies: add entries, never repurpose or rename them.
 */
enum class SelfRating(@param:StringRes val label: Int) {
    Struggling(R.string.rating_struggling),
    Okay(R.string.rating_okay),
    Confident(R.string.rating_confident),
}

/** Same wire-format caution as [SelfRating]. */
enum class ExperienceLevel(@param:StringRes val label: Int) {
    New(R.string.experience_new),
    Developing(R.string.experience_developing),
    Experienced(R.string.experience_experienced),
}

/**
 * What the athlete told the app about themselves during onboarding.
 *
 * ## This is an opinion, and the app must keep saying so
 *
 * Nothing here is measured. It is the athlete's own view of their game, collected
 * before the app has seen them play, and everything derived from it inherits that
 * status. The dashboard names its source on the card for exactly this reason: the
 * Coach tab reaches its conclusions from `CoachAdvice`, which reads real cue scores
 * off real reps, and the two can legitimately disagree. An athlete who thinks their
 * serve is fine and whose toss says otherwise is the normal case, not an error.
 *
 * @param ratings one entry per skill the athlete answered for. A skipped skill is
 *   absent rather than defaulted, so "did not say" stays distinguishable from
 *   "said Okay".
 * @param completedAtEpochMs when the questionnaire was finished, so the dashboard
 *   can say how old the advice it is giving actually is.
 */
data class SkillAssessment(
    val ratings: Map<Skill, SelfRating> = emptyMap(),
    val experience: ExperienceLevel? = null,
    val completedAtEpochMs: Long = 0L,
) {
    val isComplete: Boolean get() = ratings.size == Skill.All.size

    val hasAnyRating: Boolean get() = ratings.isNotEmpty()

    /**
     * The skill to send this athlete to work on, or null if they rated nothing.
     *
     * Lowest self-rating wins. Ties break by [Skill.All] order, which is the order
     * the capstone document lists the five fundamentals in - an arbitrary rule, but
     * a stable one, so the dashboard does not recommend a different skill each time
     * it recomposes.
     *
     * ## Why position filters the candidates
     *
     * [AthleteProfile]'s KDoc has said from the start that position "decides which
     * skills are worth recommending, since asking a libero to drill blocking is
     * wasted training", and noted that nothing was wired to it yet. This is that
     * wiring. A libero cannot legally block or attack above the net, so a libero
     * who honestly rates themselves Struggling at blocking would otherwise be sent
     * to drill the one skill they are not allowed to perform.
     *
     * If filtering leaves nothing - an athlete who only rated skills their position
     * excludes - the filter is dropped rather than returning null. A recommendation
     * they can act on beats no recommendation.
     */
    fun weakestSkill(position: CourtPosition? = null): Skill? {
        if (ratings.isEmpty()) return null
        val relevant = ratings.filterKeys { position == null || it in position.relevantSkills }
        val candidates = relevant.ifEmpty { ratings }
        return Skill.All.filter { it in candidates }
            .minByOrNull { candidates.getValue(it).ordinal }
    }

    companion object {
        /** No questionnaire taken. Distinct from one taken and skipped part-way. */
        val Unanswered = SkillAssessment()
    }
}

/**
 * The skills worth recommending to each position.
 *
 * Deliberately generous: this excludes only what a position genuinely does not do,
 * not what it does least often. A middle blocker who wants to work on their passing
 * is not making a mistake, and an app that refuses to suggest it is being clever at
 * the athlete's expense. The exclusions are:
 *
 * - **Libero** may not attack above net height and may not block, under both FIVB
 *   and NCAA rules. Serving is left in because the rules differ - FIVB forbids it,
 *   NCAA and USAV allow it in one rotation - and the safer error is to offer a
 *   skill some liberos can use rather than withhold one they can.
 * - **Middle blocker** and **opposite** are kept out of serve-receive in essentially
 *   every rotation at every level.
 * - **Setter** is excluded from spiking; a setter's attack is a dump, which is not
 *   what the spiking analyzer measures.
 */
val CourtPosition.relevantSkills: Set<Skill>
    get() = when (this) {
        CourtPosition.Setter -> Skill.All.toSet() - Skill.Spiking
        CourtPosition.OutsideHitter -> Skill.All.toSet()
        CourtPosition.MiddleBlocker -> Skill.All.toSet() - Skill.Receiving
        CourtPosition.Opposite -> Skill.All.toSet() - Skill.Receiving
        CourtPosition.Libero -> Skill.All.toSet() - Skill.Spiking - Skill.Blocking
    }
