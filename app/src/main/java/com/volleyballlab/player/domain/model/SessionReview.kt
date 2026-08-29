package com.volleyballlab.player.domain.model

import androidx.annotation.StringRes
import com.volleyballlab.player.R
import kotlin.math.roundToInt

/**
 * One cue as it reads on a coach's scorecard.
 *
 * @param meanScore `0f..1f` across every rep in the session that produced a
 *   measurement for this cue.
 * @param measuredReps how many reps that was. Shown next to the number for the
 *   same reason the player's Coach tab shows it: a cue read on two reps and a cue
 *   read on twenty are not the same claim, and the coach is about to write advice
 *   on the strength of it.
 */
data class ReviewPoint(
    val cue: FormCue,
    val meanScore: Float,
    val measuredReps: Int,
) {
    val scoreOutOfHundred: Int get() = (meanScore * 100).roundToInt().coerceIn(0, 100)
}

/** What a safety cue is saying about this session. */
enum class SafetyVerdict(@param:StringRes val label: Int) {
    /** Measured and inside the expected band. */
    Clear(R.string.review_safety_clear),

    /** Measured and persistently outside it. Worth the coach looking at the clip. */
    Watch(R.string.review_safety_watch),

    /** Not measurable from this camera angle, so nothing can be said either way. */
    Unknown(R.string.review_safety_unknown),
}

/**
 * One line of the safety panel in Figure 25.
 *
 * ## Read the wording of [SafetyVerdict] carefully before extending this
 *
 * These are *screening prompts for a human coach*, not findings. Every threshold
 * behind them is a first-pass value from general coaching guidance - the ten coach
 * interviews of Experiment 2 are still pending - so a [SafetyVerdict.Watch] means
 * "a joint angle sat outside the band we guessed at, go and look at the video",
 * and it means nothing more than that. The UI is required to say so, and
 * `review_safety_disclaimer` is the string that does it.
 *
 * Turning any of this into an injury-risk claim needs measured data this project
 * does not have yet. Do not let the copy drift in that direction.
 */
data class SafetyCheck(
    val cue: FormCue,
    val verdict: SafetyVerdict,
    val meanScore: Float?,
    val measuredReps: Int,
)

/**
 * Everything the coach's review screen shows about one submitted session.
 *
 * ## Derived, never stored
 *
 * This is a pure function of a [SessionResult]. Nothing here is persisted, so a
 * retuned threshold in [FormCueCatalog] changes every past review the next time it
 * is opened, rather than leaving a trail of scorecards computed against numbers
 * nobody can reconstruct. That matters specifically because those thresholds are
 * expected to move once Experiment 2 runs.
 *
 * @param strengths the best-scoring cues, best first. Figure 25's "strengths".
 * @param corrections the worst-scoring cues, worst first. Figure 25's "next
 *   corrections".
 * @param unmeasuredFraction share of cue slots that came back null. A high value
 *   is a filming problem, and the review screen says so rather than letting the
 *   coach read a thin scorecard as a weak athlete.
 */
data class SessionReview(
    val session: SessionResult,
    val formScore: Int?,
    val strengths: List<ReviewPoint>,
    val corrections: List<ReviewPoint>,
    val safety: List<SafetyCheck>,
    val unmeasuredFraction: Float,
) {
    val repCount: Int get() = session.repCount

    val framingIsPoor: Boolean get() = unmeasuredFraction >= POOR_FRAMING_FRACTION

    /** True when at least one safety cue wants a human to look at the clip. */
    val hasSafetyWatch: Boolean get() = safety.any { it.verdict == SafetyVerdict.Watch }

    companion object {
        /**
         * The cues that get a line in the safety panel.
         *
         * Chosen as the load-bearing and symmetry cues among the twenty in
         * [FormCueCatalog] - knee load through the landing and approach phases,
         * and the two cues that read left-right asymmetry. They are the ones the
         * storyboard's example ("asymmetric knee landing") is about.
         *
         * This is a hand-picked list rather than a flag on [FormCue] on purpose:
         * a flag would invite every new cue to declare itself safety-relevant,
         * and the panel would quietly become a second copy of the scorecard.
         */
        val SAFETY_CUES: Set<CueId> = setOf(
            CueId.SpikeApproachLoad,
            CueId.BlockKneeLoad,
            CueId.PassKneeFlexion,
            CueId.SetKneeFlexion,
            CueId.PassShouldersLevel,
        )

        /** Below this mean score, a safety cue is flagged for a human to look at. */
        const val SAFETY_WATCH_SCORE = 0.5f

        /** At or above this, a cue is worth calling a strength. */
        const val STRENGTH_SCORE = 0.75f

        /** Below this, a cue is worth calling a correction. */
        const val CORRECTION_SCORE = 0.7f

        /** Above this share of unmeasurable cues, the framing is the story. */
        const val POOR_FRAMING_FRACTION = 0.3f

        /** How many strengths and corrections the panel shows. */
        const val PANEL_SIZE = 3

        /**
         * A cue needs this many measurements in the session before it can be
         * called either a strength or a correction.
         *
         * Two, not [SessionResult.MIN_REPS_FOR_RECOMMENDATION]'s three: that floor
         * governs advice pooled across an athlete's whole history, and a single
         * session often has fewer reps of a cue than that. One rep is still noise
         * and is still excluded.
         */
        const val MIN_REPS_FOR_PANEL = 2

        fun from(session: SessionResult): SessionReview {
            val cues = FormCueCatalog.forSkill(session.skill)
            val byCue = session.reps
                .flatMap { it.cueScores }
                .groupBy { it.cueId }

            val slots = session.reps.size * cues.size
            val measured = byCue.values.sumOf { scores -> scores.count { it.isMeasured } }
            val unmeasuredFraction = if (slots == 0) 0f else 1f - (measured.toFloat() / slots)

            val points = cues.mapNotNull { cue ->
                val scores = byCue[cue.id].orEmpty().mapNotNull { it.score }
                if (scores.isEmpty()) return@mapNotNull null
                ReviewPoint(cue, scores.average().toFloat(), scores.size)
            }

            val eligible = points.filter { it.measuredReps >= MIN_REPS_FOR_PANEL }

            return SessionReview(
                session = session,
                formScore = session.formScore(),
                strengths = eligible
                    .filter { it.meanScore >= STRENGTH_SCORE }
                    .sortedByDescending { it.meanScore }
                    .take(PANEL_SIZE),
                corrections = eligible
                    .filter { it.meanScore < CORRECTION_SCORE }
                    .sortedBy { it.meanScore }
                    .take(PANEL_SIZE),
                safety = cues
                    .filter { it.id in SAFETY_CUES }
                    .map { cue ->
                        val scores = byCue[cue.id].orEmpty().mapNotNull { it.score }
                        val mean = scores.takeIf { it.isNotEmpty() }?.average()?.toFloat()
                        SafetyCheck(
                            cue = cue,
                            verdict = when {
                                mean == null -> SafetyVerdict.Unknown
                                mean < SAFETY_WATCH_SCORE -> SafetyVerdict.Watch
                                else -> SafetyVerdict.Clear
                            },
                            meanScore = mean,
                            measuredReps = scores.size,
                        )
                    },
                unmeasuredFraction = unmeasuredFraction.coerceIn(0f, 1f),
            )
        }
    }
}
