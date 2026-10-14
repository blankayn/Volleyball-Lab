package com.volleyballlab.player.pose.review

import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.model.PoseFrame

/** Recorder status durations calibrate the sensor clock to the MP4's zero-based clock. */
class RecordingVideoClock {
    private var startCameraMs: Long? = null
    private val origins = ArrayDeque<Long>()

    fun reset() { startCameraMs = null; origins.clear() }
    fun started(cameraMs: Long) { if (cameraMs > 0) startCameraMs = cameraMs }
    fun observe(cameraMs: Long, recordedMs: Long) {
        if (cameraMs <= 0 || recordedMs <= 0) return
        origins.addLast(cameraMs - recordedMs)
        if (origins.size > 31) origins.removeFirst()
    }
    fun origin(): Long? = origins.sorted().let { if (it.isEmpty()) startCameraMs else it[it.size / 2] }
}

data class CapturedReviewInput(
    val frames: List<PoseFrame>,
    val issues: List<ReviewIssue>,
    val assessedCueCount: Int,
    val videoOriginCameraMs: Long,
)

fun interface CapturedReviewWriter {
    suspend fun save(path: String, skill: Skill, input: CapturedReviewInput)
}

/** Reuses detected poses and scored phase moments. No decoding or model inference. */
fun CapturedReviewInput.toReview(durationMs: Long, width: Int, height: Int): VideoReview {
    val poses = frames.asSequence().map { frame ->
        ReviewPose(frame.cameraTimestampMs - videoOriginCameraMs, frame.image.map { point ->
            val finite = point.x.isFinite() && point.y.isFinite()
            ReviewPoint(if (finite) point.x else 0f, if (finite) point.y else 0f,
                if (finite) minOf(point.visibility, point.presence) else 0f)
        })
    }.filter { it.timestampMs in 0..durationMs }.distinctBy { it.timestampMs }.sortedBy { it.timestampMs }.toList()
    val intervals = poses.zipWithNext { a, b -> b.timestampMs - a.timestampMs }.sorted()
    val interval = intervals.getOrNull(intervals.size / 2)?.coerceIn(50L, 200L) ?: 50L
    return VideoReview(durationMs, width, height, interval, poses,
        issues.map { it.copy(timestampMs = it.timestampMs - videoOriginCameraMs) }
            .filter { it.timestampMs in 0..durationMs }.sortedWith(compareBy<ReviewIssue> { it.timestampMs }.thenBy { it.score }),
        assessedCueCount, fromCapture = true)
}
