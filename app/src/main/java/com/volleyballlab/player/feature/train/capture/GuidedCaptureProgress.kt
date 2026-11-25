package com.volleyballlab.player.feature.train.capture

import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.pose.analysis.Facing
import com.volleyballlab.player.pose.analysis.LiveCoachingAdvice

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

/** The view label, the way to face for it, and the counters, presented together on the live screen. */
data class CaptureProgressDisplay(
    val angle: CameraAngle,
    val metrics: LiveCaptureMetrics,
    val facing: Facing = Facing.Forward,
)

fun CaptureUiState.Recording.displayedProgress(options: GuidedCaptureOptions): CaptureProgressDisplay =
    CaptureProgressDisplay(
        facing = rotation?.facing ?: facing,
        angle = rotation?.requestedAngle ?: angle,
        metrics = when {
            !options.enabled -> totalMetrics
            // During a turn, stageMetrics still belongs to the completed view.
            // The screen labels the upcoming view, whose counters start at zero.
            rotation != null -> LiveCaptureMetrics()
            else -> stageMetrics
        },
    )

data class RotationUi(
    val requestedAngle: CameraAngle,
    val stageIndex: Int,
    val elapsedMs: Long = 0,
    val secondsLeft: Int? = null,
    val message: String,
    /** Short direction for the visual card; the full message remains in voice coaching. */
    val instruction: String = message,
    val guidanceId: Long = 0,
    val loadingCoaching: Boolean = false,
    val speechPending: Boolean = false,
    val advice: LiveCoachingAdvice? = null,
    val required: Boolean = false,
    val blockedReason: String? = null,
    /** The spoken debrief, one sentence per segment. The instruction follows them. */
    val segments: List<String> = emptyList(),
    /** An AI line is still on its way and may join this turn's speech if it arrives in time. */
    val awaitingAdvice: Boolean = false,
    /** Which way the athlete faces for the requested view. */
    val facing: Facing = Facing.Forward,
) {
    val timedOut: Boolean get() = elapsedMs >= 10_000
}

/** Shared by the controller and tests; never advances on a posture score. */
object GuidedCaptureProgress {
    /**
     * Front, then side. The rear view was removed on 19 Nov 2026: a back view hides the
     * arm cues serve and spike are coached on, and it lengthened every session. Saved
     * sessions that include a rear stage still display it.
     */
    val angles = listOf(CameraAngle.Front, CameraAngle.Side)
    val requiredAngles = setOf(CameraAngle.Front, CameraAngle.Side)
    fun isRequired(index: Int) = angles.getOrNull(index) in requiredAngles
    fun canSkip(index: Int) = index in angles.indices && !isRequired(index)
    sealed interface StopDecision {
        data object Finish : StopDecision
        data class ContinueToSide(val stageIndex: Int = 1) : StopDecision
        data class Blocked(val repsRemaining: Int) : StopDecision
    }
    fun stopDecision(stageIndex: Int, stageReps: Int, options: GuidedCaptureOptions): StopDecision = when {
        !options.enabled -> StopDecision.Finish
        stageIndex == 0 -> StopDecision.ContinueToSide()
        stageIndex == 1 && stageReps < options.repsPerAngle -> StopDecision.Blocked(options.repsPerAngle - stageReps)
        else -> StopDecision.Finish
    }
    fun recordingLimit(options: GuidedCaptureOptions): Long =
        if (options.enabled) maxOf(300_000L, options.repsPerAngle * angles.size * 12_000L) else 90_000L
    fun reachedTarget(options: GuidedCaptureOptions, metrics: LiveCaptureMetrics): Boolean =
        options.enabled && metrics.reps >= options.repsPerAngle
    fun nextStage(stageIndex: Int): Int? = (stageIndex + 1).takeIf { it < angles.size }
}
