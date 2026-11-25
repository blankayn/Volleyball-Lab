package com.volleyballlab.player.domain.model

/**
 * The working behind a session score, so anyone can add it up again.
 *
 * Every number here is one [CoachAssessmentEngine] used. A row's [Row.points] is its
 * share of the final score; the points of all rows add up to [total].
 */
data class ScoreBreakdown(
    val rows: List<Row>,
    /** Weight of the checks that were measured, as a share of the checks this drill trains. */
    val coverage: Float,
    /** Null when too little was measured, see [CoachAssessmentEngine.MIN_COVERAGE]. */
    val total: Int?,
) {
    data class Row(
        val cue: FormCue,
        val median: Float,
        val inRange: Int,
        val measuredReps: Int,
        /** 0..100: the mean over the measured reps of this check. */
        val score: Int,
        /** This check's weight among the checks that were measured, 0..1. */
        val share: Double,
    ) {
        val points: Double get() = score * share
    }

    companion object {
        fun of(assessment: CoachAssessment): ScoreBreakdown {
            val scored = assessment.observedCues.filter { it.cue.id != CueId.SpikeJumpHeight }
            val weight = scored.sumOf { it.cue.weight.toDouble() }
            return ScoreBreakdown(
                rows = scored.map {
                    Row(it.cue, it.medianMeasured, it.measuredReps - it.belowReference - it.aboveReference, it.measuredReps,
                        it.referenceScore, it.cue.weight / weight)
                },
                coverage = assessment.evidenceCoverage,
                total = assessment.overallScore,
            )
        }
    }
}
