package com.volleyballlab.player.pose

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.volleyballlab.player.pose.model.Landmark
import com.volleyballlab.player.pose.model.PoseFrame
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.google.mediapipe.tasks.components.containers.Landmark as MpWorldLandmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark as MpImageLandmark

/**
 * [PoseDetector] backed by MediaPipe's Pose Landmarker in live-stream mode.
 *
 * ## Model variants
 *
 * MediaPipe ships three bundles. The choice is a real accuracy-versus-latency
 * tradeoff and it belongs in the capstone's evaluation chapter, since MPJPE is
 * the reported accuracy metric:
 *
 * | Bundle   | Approx. size | Notes                                           |
 * |----------|--------------|-------------------------------------------------|
 * | `lite`   | ~3 MB        | Fastest. Noticeably worse on fast limb motion.  |
 * | `full`   | ~9 MB        | The default here. Best balance on mid phones.   |
 * | `heavy`  | ~29 MB       | Most accurate, too slow for live use on mid-range. |
 *
 * See `app/src/main/assets/README.md` for how to fetch one.
 *
 * ## Delegate fallback
 *
 * Construction tries the GPU delegate and silently retries on CPU if that fails.
 * GPU initialisation failing is common enough on budget Android hardware - which
 * is exactly the hardware student athletes are likely to have - that treating it
 * as fatal would make the app unusable for part of the intended audience. The
 * chosen delegate is reported in [usingGpu] so the results screen can note it,
 * because latency differs enough between the two to matter when interpreting a
 * session.
 */
class MediaPipePoseDetector private constructor(
    private val landmarker: PoseLandmarker,
    val usingGpu: Boolean,
) : PoseDetector {

    private val _poses = MutableSharedFlow<PoseFrame>(
        replay = 1,
        extraBufferCapacity = 1,
        // Dropping is the correct policy for a live overlay. Suspending would
        // apply backpressure to the camera thread, and buffering would draw the
        // athlete where they were half a second ago.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val poses: SharedFlow<PoseFrame> = _poses.asSharedFlow()

    /**
     * Carries the mirroring flag from [submit] across to the async result.
     *
     * MediaPipe's result callback hands back the result and the input image but
     * none of our own per-frame context, so it has to be parked somewhere keyed
     * by the one value that does survive: the timestamp. Entries are removed on
     * read; the size guard covers frames that error out and never produce a
     * result, which would otherwise leak an entry per dropped frame.
     */
    private val mirroredByTimestamp = ConcurrentHashMap<Long, Boolean>()

    override fun submit(bitmap: Bitmap, timestampMs: Long, mirrored: Boolean) {
        if (mirroredByTimestamp.size > MAX_PENDING_FRAMES) mirroredByTimestamp.clear()
        mirroredByTimestamp[timestampMs] = mirrored
        val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
        runCatching { landmarker.detectAsync(mpImage, timestampMs) }
            .onFailure {
                mirroredByTimestamp.remove(timestampMs)
                // Out-of-order timestamps are rejected outright by live-stream
                // mode. That is a caller bug, not a runtime condition, so it is
                // logged loudly rather than surfaced to the athlete.
                Log.w(TAG, "detectAsync rejected frame at $timestampMs", it)
            }
    }

    private fun onResult(result: PoseLandmarkerResult, input: MPImage) {
        val imageLandmarks = result.landmarks().firstOrNull()
        val worldLandmarks = result.worldLandmarks().firstOrNull()
        val timestampMs = result.timestampMs()
        val mirrored = mirroredByTimestamp.remove(timestampMs) ?: false

        // No pose in frame is a normal, frequent condition - the athlete walks
        // out of shot between reps - so it emits nothing rather than an error.
        // PoseQualityValidator treats an absent frame as NoPersonDetected.
        if (imageLandmarks == null || worldLandmarks == null) return
        if (imageLandmarks.size != PoseFrame.EXPECTED_LANDMARK_COUNT ||
            worldLandmarks.size != PoseFrame.EXPECTED_LANDMARK_COUNT
        ) {
            Log.w(TAG, "Unexpected landmark count: ${imageLandmarks.size}")
            return
        }

        _poses.tryEmit(
            PoseFrame(
                timestampMs = timestampMs,
                image = imageLandmarks.map { it.toLandmark() },
                world = worldLandmarks.map { it.toLandmark() },
                imageWidth = input.width,
                imageHeight = input.height,
                mirrored = mirrored,
            )
        )
    }

    override fun close() {
        mirroredByTimestamp.clear()
        runCatching { landmarker.close() }
            .onFailure { Log.w(TAG, "Failed to close PoseLandmarker", it) }
    }

    companion object {
        private const val TAG = "MediaPipePose"
        private const val MAX_PENDING_FRAMES = 32

        const val DEFAULT_MODEL_ASSET = "pose_landmarker_full.task"

        /**
         * Builds a detector, preferring the GPU delegate.
         *
         * @throws PoseDetectorUnavailableException with
         *   [PoseDetectorUnavailableException.Reason.ModelMissing] when the asset
         *   is absent, which is by far the most likely failure on a fresh clone
         *   and has a specific fix worth telling the user about.
         */
        fun create(
            context: Context,
            modelAsset: String = DEFAULT_MODEL_ASSET,
            minDetectionConfidence: Float = 0.5f,
            minTrackingConfidence: Float = 0.5f,
            minPresenceConfidence: Float = 0.5f,
        ): MediaPipePoseDetector {
            if (!context.hasAsset(modelAsset)) {
                throw PoseDetectorUnavailableException(
                    message = "Missing pose model asset: $modelAsset",
                    reason = PoseDetectorUnavailableException.Reason.ModelMissing,
                )
            }

            // Holder, because the result listener is registered during option
            // building but needs to call a method on the instance those options
            // will eventually produce.
            val holder = DetectorHolder()

            fun build(delegate: Delegate): PoseLandmarker {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(modelAsset)
                    .setDelegate(delegate)
                    .build()
                val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    // One athlete. Tracking a bystander in the background would
                    // both waste inference budget and risk scoring the wrong body.
                    .setNumPoses(1)
                    .setMinPoseDetectionConfidence(minDetectionConfidence)
                    .setMinTrackingConfidence(minTrackingConfidence)
                    .setMinPosePresenceConfidence(minPresenceConfidence)
                    .setOutputSegmentationMasks(false)
                    .setResultListener { result, input -> holder.instance?.onResult(result, input) }
                    .setErrorListener { error -> Log.e(TAG, "PoseLandmarker error", error) }
                    .build()
                return PoseLandmarker.createFromOptions(context, options)
            }

            val (landmarker, usingGpu) = try {
                build(Delegate.GPU) to true
            } catch (gpuFailure: Throwable) {
                Log.w(TAG, "GPU delegate unavailable, falling back to CPU", gpuFailure)
                try {
                    build(Delegate.CPU) to false
                } catch (cpuFailure: Throwable) {
                    throw PoseDetectorUnavailableException(
                        message = "PoseLandmarker failed to initialise on both GPU and CPU",
                        reason = PoseDetectorUnavailableException.Reason.InitialisationFailed,
                        cause = cpuFailure,
                    )
                }
            }

            return MediaPipePoseDetector(landmarker, usingGpu).also { holder.instance = it }
        }

        private class DetectorHolder {
            @Volatile
            var instance: MediaPipePoseDetector? = null
        }

        private fun Context.hasAsset(name: String): Boolean =
            runCatching { assets.open(name).close() }.isSuccess

        private fun MpImageLandmark.toLandmark() = Landmark(
            x = x(),
            y = y(),
            z = z(),
            // Absent visibility means the model did not estimate it. Defaulting
            // to 0 would make every joint look untrustworthy and block capture
            // entirely, so an unstated visibility is treated as fully visible.
            visibility = visibility().orElse(1f),
            presence = presence().orElse(1f),
        )

        private fun MpWorldLandmark.toLandmark() = Landmark(
            x = x(),
            y = y(),
            z = z(),
            visibility = visibility().orElse(1f),
            presence = presence().orElse(1f),
        )
    }
}
