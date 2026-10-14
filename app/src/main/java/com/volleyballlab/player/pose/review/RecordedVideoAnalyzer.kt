package com.volleyballlab.player.pose.review

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.core.graphics.scale
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.MediaPipePoseDetector
import com.volleyballlab.player.pose.PrimaryPoseTracker
import com.volleyballlab.player.pose.model.Landmark
import com.volleyballlab.player.pose.model.PoseFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Decodes the actual MP4, avoiding live-preview crop, mirror and recorder-start offsets. */
class RecordedVideoAnalyzer(private val context: Context) {
    suspend fun analyse(path: String, skill: Skill, onProgress: (Float) -> Unit): VideoReview =
        withContext(Dispatchers.Default) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?.takeIf { it > 0 } ?: error("Recording has no duration")
                // Normal recordings use 20 samples/sec. Bound memory for unusually long clips.
                val interval = max(50L, (duration + 11_999) / 12_000)
                val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder()
                        .setModelAssetPath(MediaPipePoseDetector.DEFAULT_MODEL_ASSET)
                        .setDelegate(Delegate.CPU).build())
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumPoses(1)
                    .setMinPoseDetectionConfidence(MediaPipePoseDetector.MIN_PERSON_CONFIDENCE)
                    .setMinPosePresenceConfidence(MediaPipePoseDetector.MIN_PERSON_CONFIDENCE)
                    .setMinTrackingConfidence(MediaPipePoseDetector.MIN_PERSON_CONFIDENCE)
                    .build()
                val landmarker = PoseLandmarker.createFromOptions(context, options)
                try {
                    val tracker = PrimaryPoseTracker()
                    val poses = mutableListOf<PoseFrame>()
                    val replay = mutableListOf<ReviewPose>()
                    var width = 0
                    var height = 0
                    var time = 0L
                    while (time < duration) {
                        currentCoroutineContext().ensureActive()
                        // Retriever applies the MP4's display rotation. Do not rotate or mirror twice.
                        val decoded = if (Build.VERSION.SDK_INT >= 27) {
                            retriever.getScaledFrameAtTime(time * 1_000, MediaMetadataRetriever.OPTION_CLOSEST, 640, 640)
                        } else {
                            retriever.getFrameAtTime(time * 1_000, MediaMetadataRetriever.OPTION_CLOSEST)?.let { original ->
                                val scale = minOf(1f, 640f / max(original.width, original.height))
                                if (scale == 1f) original else original.scale(
                                    (original.width * scale).toInt().coerceAtLeast(1),
                                    (original.height * scale).toInt().coerceAtLeast(1), true).also { original.recycle() }
                            }
                        }
                        var frame: PoseFrame? = null
                        if (decoded != null) {
                            val bitmap = if (decoded.config == Bitmap.Config.ARGB_8888) decoded
                                else checkNotNull(decoded.copy(Bitmap.Config.ARGB_8888, false))
                            try {
                                width = bitmap.width
                                height = bitmap.height
                                val image = BitmapImageBuilder(bitmap).build()
                                try {
                                    val result = landmarker.detectForVideo(image, time)
                                    val candidates = result.landmarks().map { points -> points.map {
                                        Landmark(it.x(), it.y(), it.z(), it.visibility().orElse(1f), it.presence().orElse(1f))
                                    } }
                                    val selected = tracker.select(candidates, time)
                                    if (selected != null) {
                                        val world = result.worldLandmarks().getOrNull(selected)?.map {
                                            Landmark(it.x(), it.y(), it.z(), it.visibility().orElse(1f), it.presence().orElse(1f))
                                        }
                                        if (candidates[selected].size == 33 && world?.size == 33) {
                                            frame = PoseFrame(time, candidates[selected], world, width, height, mirrored = false)
                                        }
                                    }
                                } finally { image.close() }
                            } finally {
                                if (bitmap !== decoded) bitmap.recycle()
                                decoded.recycle()
                            }
                        } else {
                            tracker.select(emptyList(), time)
                        }
                        frame?.let(poses::add)
                        replay.add(ReviewPose(time, frame?.image?.map {
                            ReviewPoint(it.x, it.y, minOf(it.visibility, it.presence))
                        }.orEmpty()))
                        if (replay.size % 10 == 0) onProgress(time.toFloat() / duration)
                        time += interval
                    }
                    check(width > 0 && height > 0) { "Recording could not be decoded" }
                    currentCoroutineContext().ensureActive()
                    val (issues, assessed) = VideoReviewFeedback().analyse(poses, skill, interval)
                    VideoReview(duration, width, height, interval, replay, issues, assessed)
                } finally { landmarker.close() }
            } finally { retriever.release() }
        }
}
