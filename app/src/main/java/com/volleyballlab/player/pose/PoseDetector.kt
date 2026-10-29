package com.volleyballlab.player.pose

import android.graphics.Bitmap
import com.volleyballlab.player.pose.model.PoseFrame
import kotlinx.coroutines.flow.SharedFlow

/**
 * Turns camera frames into tracked poses.
 *
 * ## Why this is an interface
 *
 * Three reasons, in order of how often they matter:
 *
 * 1. The emulator has no usable camera and no GPU delegate, so
 *    [SyntheticPoseDetector] is the only way to exercise the capture UI without
 *    physical hardware in hand.
 * 2. The analysis pipeline can be tested against scripted poses rather than
 *    against whatever a real athlete happened to do in front of a phone.
 * 3. If MediaPipe is swapped for MoveNet - which the capstone's tech-stack prose
 *    mentions but its tech-stack table omits - only one implementation changes.
 *
 * ## Threading contract
 *
 * [submit] is called from the CameraX analyzer executor and must not block; it
 * hands the frame off and returns. Results arrive later on [poses], which is a
 * hot flow. Implementations must tolerate [submit] being called again before the
 * previous frame has produced a result, and must drop rather than queue - a
 * backlog of stale poses is worse than a lower frame rate, because the overlay
 * would lag visibly behind the athlete.
 *
 * ## Bitmap, not ImageProxy
 *
 * The interface takes a decoded [Bitmap] so that nothing below the capture
 * feature depends on CameraX. The ImageProxy-to-Bitmap conversion, along with
 * rotation, lives in the camera glue where it belongs.
 */
interface PoseDetector : AutoCloseable {

    /**
     * Inference results, one per successfully processed frame.
     *
     * A hot [SharedFlow] with a replay of 1 and a drop-oldest buffer: a late
     * collector should immediately see the current pose rather than wait for the
     * next frame, and a slow collector must never apply backpressure to the
     * camera.
     */
    val poses: SharedFlow<PoseFrame>

    /**
     * Submits a frame for inference. Returns immediately.
     *
     * @param timestampMs camera acquisition timestamp from the ImageProxy.
     *   MediaPipe requires strictly increasing timestamps; the real detector
     *   rebases this value if CameraX changes its origin after a rebind.
     * @param mirrored whether the source was the front camera. Carried through to
     *   [PoseFrame.mirrored] for rendering; it does not affect inference.
     */
    fun submit(bitmap: Bitmap, timestampMs: Long, mirrored: Boolean)
}

/**
 * Raised when the detector cannot be constructed.
 *
 * Separated from generic failures because the overwhelmingly common cause is a
 * missing model asset, and that has a specific, actionable fix that the UI
 * surfaces directly. See `app/src/main/assets/README.md`.
 */
class PoseDetectorUnavailableException(
    message: String,
    val reason: Reason,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class Reason {
        /** The .task bundle is not packaged in assets. */
        ModelMissing,

        /** The asset could not be accessed, or MediaPipe could not initialise it. */
        InitialisationFailed,
    }
}
