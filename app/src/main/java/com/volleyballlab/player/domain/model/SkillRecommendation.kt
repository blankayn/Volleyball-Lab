package com.volleyballlab.player.domain.model

import androidx.annotation.StringRes
import com.volleyballlab.player.R
import kotlin.math.roundToInt

/**
 * How far along an athlete is at one skill.
 *
 * ## These bands are invented, and nothing else in the app depends on them
 *
 * The capstone document defines no notion of a level. The three bands below are a
 * presentation device, chosen so the dashboard can say something more useful than a
 * bare number, and the thresholds are a first guess in exactly the way every value
 * in `FormCueCatalog` is a first guess. They are gathered here, as one `when`, so
 * that a coach who disagrees can move them without reading any other file.
 *
 * They must not be reported as a validated scale.
 */
enum class SkillLevel(val number: Int, @param:StringRes val label: Int) {
    Beginner(1, R.string.level_beginner),
    Intermediate(2, R.string.level_intermediate),
    Advanced(3, R.string.level_advanced);

    companion object {
        /** From a measured form score, 0..100. */
        fun fromScore(score: Int): SkillLevel = when {
            score < INTERMEDIATE_FLOOR -> Beginner
            score < ADVANCED_FLOOR -> Intermediate
            else -> Advanced
        }

        /** From the athlete's own opinion, when there is nothing measured yet. */
        fun fromRating(rating: SelfRating): SkillLevel = when (rating) {
            SelfRating.Struggling -> Beginner
            SelfRating.Okay -> Intermediate
            SelfRating.Confident -> Advanced
        }

        private const val INTERMEDIATE_FLOOR = 60
        private const val ADVANCED_FLOOR = 80
    }
}

/**
 * What the dashboard should tell this athlete to work on next.
 *
 * @param level where they currently sit at [skill].
 * @param measuredScore their mean form score for [skill], or null if they have not
 *   recorded any measurable reps of it yet. Its presence is what separates a claim
 *   the app measured from a claim the athlete made, and the card says which it is
 *   showing rather than letting the reader assume.
 */
data class SkillRecommendation(
    val skill: Skill,
    val level: SkillLevel,
    val measuredScore: Int?,
) {
    val isMeasured: Boolean get() = measuredScore != null

    companion object {
        /**
         * Picks the focus skill from the assessment, and levels it from real reps
         * where any exist.
         *
         * ## Why the recommendation comes from the questionnaire and the level does not
         *
         * These answer two different questions. *Which skill* is a choice about
         * where the athlete wants to spend their training, and the athlete's own
         * sense of their weakest skill is a legitimate - and, on a new install, the
         * only - answer to it. *How good they currently are* is a factual claim, and
         * the moment the app has measured reps of that skill it should use them
         * rather than repeat back an opinion it can check.
         *
         * So a beginner who rates their serving Struggling is sent to serving, and
         * once they have recorded a few sets the card starts showing what their
         * serve actually scores - which may well be better than they thought.
         *
         * Returns null when the athlete rated nothing, which is the skipped-
         * onboarding case. The dashboard offers the questionnaire instead of
         * inventing a recommendation out of nothing.
         */
        fun from(
            assessment: SkillAssessment,
            profile: AthleteProfile,
            sessions: List<SessionResult>,
        ): SkillRecommendation? {
            val skill = assessment.weakestSkill(profile.position) ?: return null
            val measured = meanScoreFor(skill, sessions)
            val level = when {
                measured != null -> SkillLevel.fromScore(measured)
                else -> assessment.ratings[skill]?.let(SkillLevel::fromRating) ?: SkillLevel.Beginner
            }
            return SkillRecommendation(skill = skill, level = level, measuredScore = measured)
        }

        /**
         * Mean form score across every session of one skill, or null if none of
         * them produced a measurable score.
         *
         * Sessions are weighted equally rather than by rep count. A ten-rep set and
         * a three-rep set are both one attempt at the skill, and rep-weighting would
         * let one long session drown out a run of short ones.
         */
        fun meanScoreFor(skill: Skill, sessions: List<SessionResult>): Int? {
            val scores = sessions.filter { it.skill == skill }.mapNotNull { it.formScore() }
            if (scores.isEmpty()) return null
            return scores.average().roundToInt().coerceIn(0, 100)
        }
    }
}
