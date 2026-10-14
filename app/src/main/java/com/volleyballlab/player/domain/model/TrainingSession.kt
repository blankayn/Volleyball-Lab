package com.volleyballlab.player.domain.model

import kotlin.math.roundToInt

/** Camera orientation during a guided stage. Enum names are persisted. */
enum class CameraAngle { Front, Side, Rear, Unknown }

/**
 * A scored capture interval in one guided session. [startMs] and [endMs] use the
 * same monotonic camera timestamps as [RepResult], not epoch time. Turning and
 * countdown intervals fall outside these boundaries.
 *
 * The counts and score are live estimates. Final analysis remains in
 * [SessionResult.reps], and may detect a different number of repetitions.
 */
data class SessionAngleStage(
    val stageIndex: Int,
    val angle: CameraAngle,
    val startMs: Long,
    val endMs: Long,
    val liveRepCount: Int,
    val validRepCount: Int,
    val bestStreak: Int,
    val estimatedFormScore: Int?,
)

/**
 * One cue, measured on one repetition.
 *
 * @param measured the raw value in the cue's [FormCue.unit], or null if the cue
 *   could not be measured on this rep.
 * @param score `0f..1f`, or null when [measured] is null.
 */
data class CueScore(
    val cueId: CueId,
    val measured: Float?,
    val score: Float?,
) {
    val isMeasured: Boolean get() = score != null
}

/**
 * A single detected repetition.
 *
 * @param contactMs timestamp of the contact phase, which is the frame most of the
 *   cues are measured on and the frame shown as the rep thumbnail.
 */
data class RepResult(
    val index: Int,
    val startMs: Long,
    val contactMs: Long,
    val endMs: Long,
    val cueScores: List<CueScore>,
) {
    val durationMs: Long get() = endMs - startMs

    /**
     * Weighted form score for this rep, 0..100, or null if nothing was measurable.
     *
     * ## Unmeasured cues are excluded, not zeroed
     *
     * Weights are renormalised across only the cues that produced a measurement.
     * If a spike is filmed from behind and trunk drive cannot be read, the
     * remaining three cues are rescaled to sum to 1 and the rep is scored on
     * those. The alternative - treating an unmeasurable cue as a zero - would
     * make camera placement look like bad technique, which is the fastest way to
     * lose an athlete's trust in the number.
     *
     * Null means *no* cue was measurable. That is a capture problem, and the UI
     * says so rather than printing a score.
     */
    fun formScore(skill: Skill): Int? {
        val cues = FormCueCatalog.forSkill(skill).associateBy { it.id }
        var weighted = 0.0
        var totalWeight = 0.0
        cueScores.forEach { cueScore ->
            val score = cueScore.score ?: return@forEach
            val weight = cues[cueScore.cueId]?.weight?.toDouble() ?: return@forEach
            weighted += score * weight
            totalWeight += weight
        }
        if (totalWeight <= 0.0) return null
        return ((weighted / totalWeight) * 100).roundToInt().coerceIn(0, 100)
    }
}

/**
 * The outcome of one capture session: the post-hoc half of the analysis.
 *
 * The live overlay during capture is intentionally cheaper and less accurate than
 * this. It exists to keep the athlete in frame and give immediate feedback; this
 * is the record that gets scored, stored and shown to a coach.
 */
data class SessionResult(
    val id: String,
    val skill: Skill,
    val drillId: String,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val reps: List<RepResult>,
    /** Private MP4 on this device, when CameraX recording succeeded. */
    val videoPath: String? = null,
    /** Optional guided-stage metadata; absent in ordinary and older sessions. */
    val angleStages: List<SessionAngleStage> = emptyList(),
    /** Local link used to resume and submit a coach-assigned recording. */
    val assignmentId: String? = null,
) {
    val repCount: Int get() = reps.size

    /** Mean of the per-rep scores, ignoring reps that were entirely unmeasurable. */
    fun formScore(): Int? {
        val scores = reps.mapNotNull { it.formScore(skill) }
        if (scores.isEmpty()) return null
        return scores.average().roundToInt().coerceIn(0, 100)
    }

    fun bestRep(): RepResult? = reps.maxByOrNull { it.formScore(skill) ?: -1 }

    /**
     * The cue with the lowest mean score across the session, which becomes the
     * "focus next on" recommendation.
     *
     * Requires at least [MIN_REPS_FOR_RECOMMENDATION] measurements of that cue
     * before recommending it. One bad rep is noise; telling an athlete to rebuild
     * their arm swing on the strength of a single frame is worse than saying
     * nothing.
     */
    fun weakestCue(): CueId? = reps
        .flatMap { it.cueScores }
        .filter { it.score != null }
        .groupBy { it.cueId }
        .filterValues { it.size >= MIN_REPS_FOR_RECOMMENDATION }
        .mapValues { (_, scores) -> scores.mapNotNull { it.score }.average() }
        .minByOrNull { it.value }
        ?.key

    companion object {
        const val MIN_REPS_FOR_RECOMMENDATION = 3
    }
}
