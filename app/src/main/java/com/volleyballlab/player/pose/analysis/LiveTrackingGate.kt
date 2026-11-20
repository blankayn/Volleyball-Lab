package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CameraAngle

/**
 * Decides, frame by frame during a live session, whether the athlete's pose
 * may be counted, should be skipped, or means tracking was lost.
 *
 * ## Why this exists
 *
 * The view detector reads one frame at a time, and side-on it flickers. On
 * public side-view coaching clips it stopped reading "side" on 25-70% of
 * frames at rest and on 22-64% of frames mid-rep, while the athlete never
 * moved their feet. The capture screen used to act on every one of those
 * frames:
 *
 * - At rest, one flicker raised "turn side-on" and cut tracking, and counting
 *   then waited a full second of clean frames before resuming. Flicker came
 *   back sooner than that, so a correctly placed athlete was told to
 *   reposition and nothing was ever counted.
 * - Mid-rep, a flicker fell back to the visibility test for a facing-forward
 *   athlete, which averages in the far arm and leg. Those are hidden side-on,
 *   so the test failed and the rep in progress was thrown away.
 *
 * Three rules replace that:
 *
 * 1. A wrong stance is acted on only once it has lasted [wrongFacingHoldMs]
 *    at rest. Someone actually facing the wrong way is still told within a
 *    second; a flicker is not.
 * 2. A side-on stance established at rest is kept until the detector clearly
 *    reads front or rear, so side-on visibility rules stay in force through
 *    the swing that rotates the shoulders.
 * 3. A frame that cannot be used is skipped. Tracking is declared lost only
 *    after [dropoutToleranceMs] of unusable frames, which stays under the
 *    rep counter's own 500 ms gap limit.
 */
class LiveTrackingGate(
    private val wrongFacingHoldMs: Long = WRONG_FACING_HOLD_MS,
    private val dropoutToleranceMs: Long = DROPOUT_TOLERANCE_MS,
) {
    enum class Outcome {
        /** Count this frame. */
        Track,

        /** Leave this frame out, but keep the movement in progress. */
        Skip,

        /** Tracking is lost: abandon the movement in progress. */
        Interrupt,

        /** The athlete is standing the wrong way: abandon the movement and say which way to turn. */
        WrongFacing,
    }

    data class Decision(val outcome: Outcome, val correction: SpokenCue? = null)

    private var wrongFacingSinceMs: Long? = null
    private var unusableSinceMs: Long? = null
    private var sideStance = false

    /**
     * @param correction what to say if the stance is wrong for this frame, from
     *   [FacingGuide], or null when it is right.
     * @param inRep whether a movement is in progress.
     * @param limbVisibility [com.volleyballlab.player.pose.model.PoseFrame.torsoAndLimbVisibility].
     */
    fun update(
        timestampMs: Long,
        estimate: OrientationEstimate,
        correction: SpokenCue?,
        inRep: Boolean,
        limbVisibility: Float,
    ): Decision {
        val sideNow = estimate.angle == CameraAngle.Side && estimate.confidence >= SIDE_CONFIDENCE
        if (sideNow) sideStance = true
        else if (estimate.angle == CameraAngle.Front || estimate.angle == CameraAngle.Rear) {
            // A swing can rotate the shoulders square to the lens for a frame.
            // Only at rest does a clear front or rear reading end the side stance.
            if (!inRep) sideStance = false
        }

        // Side-on, the far arm and leg are hidden, so the near leg and torso are
        // what must be seen. Facing forward, everything should be.
        val visible = if (sideStance) estimate.fullBodyVisible else limbVisibility >= FULL_VISIBILITY
        val usable = estimate.fullBodyVisible && visible

        // Facing is judged at rest. Mid-rep, the swing itself turns the body.
        wrongFacingSinceMs = if (inRep || correction == null) null else wrongFacingSinceMs ?: timestampMs
        val facingWrong = wrongFacingSinceMs?.let { timestampMs - it >= wrongFacingHoldMs } == true

        if (!usable) {
            val since = unusableSinceMs ?: timestampMs.also { unusableSinceMs = it }
            return Decision(if (timestampMs - since > dropoutToleranceMs) Outcome.Interrupt else Outcome.Skip)
        }
        unusableSinceMs = null
        if (facingWrong) return Decision(Outcome.WrongFacing, correction)
        return Decision(Outcome.Track)
    }

    fun reset() {
        wrongFacingSinceMs = null
        unusableSinceMs = null
        sideStance = false
    }

    companion object {
        const val WRONG_FACING_HOLD_MS = 800L
        const val DROPOUT_TOLERANCE_MS = 400L

        /** The confidence the capture screen already required before trusting a view. */
        const val SIDE_CONFIDENCE = 0.7f

        /** Mean torso-and-limb visibility required when the whole body faces the lens. */
        const val FULL_VISIBILITY = 0.6f
    }
}
