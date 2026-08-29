package com.volleyballlab.player.pose.quality

import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.Landmark
import com.volleyballlab.player.pose.model.PoseFrame
import kotlin.math.sqrt

/**
 * Decides whether the current framing is good enough to start recording.
 *
 * ## Why this gate exists
 *
 * Every downstream number depends on it. A rep captured with the athlete's feet
 * out of frame produces knee angles from extrapolated ankles, which produces a
 * confident, precise, wrong score. It is far better to refuse to record for two
 * seconds than to hand a coach a number built on guessed joints.
 *
 * ## Scope
 *
 * This runs during **framing only**, before the countdown, and is not evaluated
 * while recording. That distinction is what makes [PoseQualityIssue.Unstable]
 * meaningful: during setup the athlete is standing ready, so a moving centroid
 * means either a shaking phone or an athlete who has not settled. During a drill
 * a moving centroid is the entire point, and the same check would fire constantly.
 *
 * The validator is **stateful** - it keeps a short history for the stability
 * check - so it holds one instance per capture session and must be [reset] when
 * the camera restarts or the athlete steps away.
 */
class PoseQualityValidator(
    private val config: Config = Config(),
) {

    /**
     * Every threshold in one place, so a coach or adviser can retune the gate
     * without reading the geometry, and so tests can construct edge cases
     * directly instead of trying to synthesise them through real poses.
     */
    data class Config(
        /** Per-landmark visibility below which a joint counts as not seen. */
        val landmarkVisibility: Float = 0.5f,
        /** Mean visibility below which we assume nobody is in frame at all. */
        val noPersonVisibility: Float = 0.3f,
        /** Mean visibility below which tracking is too weak to trust. */
        val lowConfidenceVisibility: Float = 0.6f,
        /** Landmarks must sit this far inside the frame edge, in normalised units. */
        val edgeMargin: Float = 0.02f,
        /** Athlete must span at least this fraction of frame height. */
        val minBodyCoverage: Float = 0.45f,
        /** Above this they are close enough to risk clipping on any movement. */
        val maxBodyCoverage: Float = 0.95f,
        /** Horizontal band the body centre must sit within. */
        val centreBand: ClosedFloatingPointRange<Float> = 0.25f..0.75f,
        /** Frames of history used for the stability check. */
        val stabilityWindow: Int = 12,
        /** Centroid spread, in normalised units, above which the scene is unsettled. */
        val maxCentroidSpread: Float = 0.012f,
    )

    private val centroidHistory = ArrayDeque<Pair<Float, Float>>()

    /** Clears stability history. Call when the camera restarts or capture ends. */
    fun reset() = centroidHistory.clear()

    /**
     * @param frame the latest inference, or null when the detector found no pose.
     */
    fun validate(frame: PoseFrame?): PoseQuality {
        if (frame == null) {
            reset()
            return PoseQuality.NotYetKnown
        }

        val meanVisibility = frame.torsoAndLimbVisibility()
        if (meanVisibility < config.noPersonVisibility) {
            reset()
            return PoseQuality(
                issues = setOf(PoseQualityIssue.NoPersonDetected),
                bodyCoverage = 0f,
                meanVisibility = meanVisibility,
            )
        }

        val issues = mutableSetOf<PoseQualityIssue>()
        val tracked = BodyPoint.TorsoAndLimbs.map { frame.image(it) }

        // A joint is missing if it is either unseen or outside the frame. Both
        // are reported as PartialBody because the fix is the same: get all of
        // yourself into shot.
        val anyMissing = tracked.any { landmark ->
            !landmark.isReliable(config.landmarkVisibility) || landmark.isOutsideFrame()
        }
        if (anyMissing) issues += PoseQualityIssue.PartialBody

        // Coverage uses only reliably seen landmarks. Including a landmark the
        // model guessed at would let a hallucinated ankle inflate the athlete's
        // apparent height and mask a TooFar condition.
        val visible = tracked.filter { it.isReliable(config.landmarkVisibility) }
        val bodyCoverage = if (visible.size < 2) {
            0f
        } else {
            val top = visible.minOf { it.y }
            val bottom = visible.maxOf { it.y }
            (bottom - top).coerceIn(0f, 1f)
        }

        when {
            bodyCoverage > config.maxBodyCoverage -> issues += PoseQualityIssue.TooClose
            bodyCoverage < config.minBodyCoverage -> issues += PoseQualityIssue.TooFar
        }

        val centroid = bodyCentroid(frame)
        if (centroid.first !in config.centreBand) issues += PoseQualityIssue.OffCentre

        if (meanVisibility < config.lowConfidenceVisibility) {
            issues += PoseQualityIssue.LowConfidence
        }

        if (isUnsettled(centroid)) issues += PoseQualityIssue.Unstable

        return PoseQuality(
            issues = issues,
            bodyCoverage = bodyCoverage,
            meanVisibility = meanVisibility,
        )
    }

    /**
     * Torso centre in image space. Shoulders and hips are used rather than all
     * landmarks because limbs swing, and a centroid that moves every time the
     * athlete raises an arm would make the OffCentre check useless.
     */
    private fun bodyCentroid(frame: PoseFrame): Pair<Float, Float> {
        val points = listOf(
            frame.image(BodyPoint.LEFT_SHOULDER),
            frame.image(BodyPoint.RIGHT_SHOULDER),
            frame.image(BodyPoint.LEFT_HIP),
            frame.image(BodyPoint.RIGHT_HIP),
        )
        return points.map { it.x }.average().toFloat() to
            points.map { it.y }.average().toFloat()
    }

    /**
     * True when the torso centre has been drifting across the recent window.
     *
     * Uses RMS distance from the window mean rather than a simple frame-to-frame
     * delta. A per-frame delta is dominated by single-frame tracking noise and
     * would flag a perfectly steady setup; spread across a window only rises when
     * the scene is genuinely moving.
     */
    private fun isUnsettled(centroid: Pair<Float, Float>): Boolean {
        centroidHistory.addLast(centroid)
        while (centroidHistory.size > config.stabilityWindow) {
            centroidHistory.removeFirst()
        }
        // Not enough history to judge. Reporting "unstable" here would make the
        // first half-second after the athlete steps in always fail.
        if (centroidHistory.size < config.stabilityWindow) return false

        val meanX = centroidHistory.map { it.first }.average().toFloat()
        val meanY = centroidHistory.map { it.second }.average().toFloat()
        val meanSquare = centroidHistory.map { (x, y) ->
            val dx = x - meanX
            val dy = y - meanY
            (dx * dx + dy * dy).toDouble()
        }.average()
        return sqrt(meanSquare).toFloat() > config.maxCentroidSpread
    }

    private fun Landmark.isOutsideFrame(): Boolean =
        x < config.edgeMargin || x > 1f - config.edgeMargin ||
            y < config.edgeMargin || y > 1f - config.edgeMargin
}
