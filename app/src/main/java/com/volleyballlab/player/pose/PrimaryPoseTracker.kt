package com.volleyballlab.player.pose

import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.Landmark
import com.volleyballlab.player.pose.model.PoseFrame
import kotlin.math.hypot

/** Selects one player from a multi-pose result and keeps that identity across frames.
 *
 * The player starts the framing gate in the middle of the viewfinder. Once chosen,
 * proximity to the last torso centre takes precedence over pose order or size.
 * A few consecutive plausible detections are required before a skeleton is
 * displayed. Missing players keep their position lock until the camera/session
 * is explicitly reset; a background person is never adopted just because the
 * athlete has been absent for a while. Reacquisition near the last position is
 * confirmed again after a gap, including on a slower CPU delegate.
 */
internal class PrimaryPoseTracker {
    private var tracked: Candidate? = null
    private var pending: Candidate? = null
    private var confirmationFrames = 0
    private var missingFrames = 0
    private var firstMissingAtMs: Long? = null
    private var lastSeenAtMs: Long? = null

    fun reset() {
        tracked = null
        clearConfirmation()
        missingFrames = 0
        firstMissingAtMs = null
        lastSeenAtMs = null
    }

    fun select(poses: List<List<Landmark>>, timestampMs: Long? = null): Int? {
        val candidates = poses.mapIndexedNotNull { index, pose ->
            candidate(pose)?.let { index to it }
        }
        val previous = tracked
        val chosen = if (previous == null) {
            // A full-body player standing centrally wins the framing stage.
            candidates.minByOrNull { (_, c) ->
                distance(c.centre, 0.5f to 0.5f) - c.height * 0.25f
            }
        } else {
            candidates.filter { (_, c) -> matches(previous, c) }
                .minByOrNull { (_, c) -> distance(previous.centre, c.centre) }
        }
        if (chosen == null) {
            if (missingFrames == 0) firstMissingAtMs = timestampMs
            missingFrames++
            clearConfirmation()
            return null
        }

        val missingDurationMs = firstMissingAtMs?.let { first -> timestampMs?.minus(first) } ?: 0L
        val elapsedSinceSeenMs = lastSeenAtMs?.let { last -> timestampMs?.minus(last) } ?: 0L
        val needsConfirmation = previous == null || missingFrames >= MAX_MISSING_FRAMES ||
            missingDurationMs >= MAX_MISSING_MS || elapsedSinceSeenMs >= MAX_MISSING_MS
        if (needsConfirmation) {
            val proposed = chosen.second
            confirmationFrames = if (pending?.let { matches(it, proposed) } == true) {
                confirmationFrames + 1
            } else 1
            pending = proposed
            if (confirmationFrames < CONFIRMATION_FRAMES) return null
        }
        clearConfirmation()
        missingFrames = 0
        firstMissingAtMs = null
        lastSeenAtMs = timestampMs
        tracked = chosen.second
        return chosen.first
    }

    private fun clearConfirmation() {
        pending = null
        confirmationFrames = 0
    }

    private fun matches(previous: Candidate, current: Candidate): Boolean =
        distance(previous.centre, current.centre) <= MAX_TRAVEL_PER_FRAME &&
            current.torsoSize / previous.torsoSize in MIN_SIZE_RATIO..MAX_SIZE_RATIO

    private fun candidate(pose: List<Landmark>): Candidate? {
        if (pose.size != PoseFrame.EXPECTED_LANDMARK_COUNT) return null
        val reliable = BodyPoint.TorsoAndLimbs.associateWith { pose[it.ordinal] }
            .filterValues { it.isReliable(MIN_BODY_CONFIDENCE) }
        if (reliable.size < MIN_BODY_POINTS) return null
        val torso = TORSO_POINTS.mapNotNull(reliable::get)
        // A turn or reaching arm often hides one shoulder. Three reliable torso
        // points still locate the same player; requiring all four dropped the
        // pose until the athlete faced the camera again.
        if (torso.size < MIN_TORSO_POINTS) return null
        // Do not draw a skeleton on a collapsed cluster of guessed joints.
        // Shoulder-to-hip lengths work for front, rear and side views without
        // requiring left/right points to have a particular horizontal order.
        val torsoLengths = TORSO_SIDES.mapNotNull { (shoulder, hip) ->
            val a = reliable[shoulder] ?: return@mapNotNull null
            val b = reliable[hip] ?: return@mapNotNull null
            hypot(a.x - b.x, a.y - b.y).takeIf { it >= MIN_TORSO_SIZE }
        }
        if (torsoLengths.isEmpty()) return null
        val visible = reliable.values
        val width = visible.maxOf { it.x } - visible.minOf { it.x }
        val height = visible.maxOf { it.y } - visible.minOf { it.y }
        if (hypot(width, height) < MIN_BODY_EXTENT) return null
        val x = torso.map { it.x }.average().toFloat()
        val y = torso.map { it.y }.average().toFloat()
        // Torso centre outside the camera cannot represent the athlete in view.
        if (x !in 0f..1f || y !in 0f..1f) return null
        return Candidate(x to y, height, torsoLengths.average().toFloat())
    }

    private fun distance(a: Pair<Float, Float>, b: Pair<Float, Float>): Float =
        hypot(a.first - b.first, a.second - b.second)

    private data class Candidate(
        val centre: Pair<Float, Float>,
        val height: Float,
        val torsoSize: Float,
    )

    companion object {
        private val TORSO_POINTS = listOf(
            BodyPoint.LEFT_SHOULDER, BodyPoint.RIGHT_SHOULDER,
            BodyPoint.LEFT_HIP, BodyPoint.RIGHT_HIP,
        )
        private val TORSO_SIDES = listOf(
            BodyPoint.LEFT_SHOULDER to BodyPoint.LEFT_HIP,
            BodyPoint.RIGHT_SHOULDER to BodyPoint.RIGHT_HIP,
        )
        private const val MIN_BODY_CONFIDENCE = 0.55f
        private const val MIN_BODY_POINTS = 8
        private const val MIN_TORSO_POINTS = 3
        private const val MIN_TORSO_SIZE = 0.035f
        private const val MIN_BODY_EXTENT = 0.12f
        private const val CONFIRMATION_FRAMES = 3
        private const val MAX_TRAVEL_PER_FRAME = 0.20f
        private const val MIN_SIZE_RATIO = 0.6f
        private const val MAX_SIZE_RATIO = 1.65f
        private const val MAX_MISSING_FRAMES = 15
        private const val MAX_MISSING_MS = 500L
    }
}
