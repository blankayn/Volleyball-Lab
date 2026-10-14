package com.volleyballlab.player.feature.train.capture

import com.volleyballlab.player.domain.model.CameraAngle

data class GuidedCaptureOptions(val enabled: Boolean = true, val repsPerAngle: Int = 5) {
    init { require(repsPerAngle == 5 || repsPerAngle == 10) }
}

/** Movement counts and assessment counts deliberately have different meanings. */
data class LiveCaptureMetrics(
    val reps: Int = 0,
    val validReps: Int = 0,
    val streak: Int = 0,
    val bestStreak: Int = 0,
    val estimatedFormScore: Int? = null,
    private val scoredReps: Int = 0,
    private val scoreTotal: Long = 0,
) {
    fun completed(score: Int?, assessable: Boolean, good: Boolean): LiveCaptureMetrics {
        val measured = score?.takeIf { assessable }
        val nextCount = scoredReps + if (measured != null) 1 else 0
        val nextTotal = scoreTotal + (measured ?: 0)
        val nextStreak = if (assessable && good) streak + 1 else 0
        return copy(
            reps = reps + 1,
            validReps = validReps + if (assessable && good) 1 else 0,
            streak = nextStreak,
            bestStreak = maxOf(bestStreak, nextStreak),
            estimatedFormScore = if (nextCount > 0) (nextTotal.toDouble() / nextCount).toInt() else null,
            scoredReps = nextCount,
            scoreTotal = nextTotal,
        )
    }

    fun interrupted(): LiveCaptureMetrics = copy(streak = 0)
}

data class RotationUi(
    val requestedAngle: CameraAngle,
    val stageIndex: Int,
    val elapsedMs: Long = 0,
    val secondsLeft: Int? = null,
    val message: String,
) {
    val timedOut: Boolean get() = elapsedMs >= 10_000
}

/** Shared by the controller and tests; never advances on a posture score. */
object GuidedCaptureProgress {
    val angles = listOf(CameraAngle.Front, CameraAngle.Side, CameraAngle.Rear)
    fun reachedTarget(options: GuidedCaptureOptions, metrics: LiveCaptureMetrics): Boolean =
        options.enabled && metrics.reps >= options.repsPerAngle
    fun nextStage(stageIndex: Int): Int? = (stageIndex + 1).takeIf { it < angles.size }
}
