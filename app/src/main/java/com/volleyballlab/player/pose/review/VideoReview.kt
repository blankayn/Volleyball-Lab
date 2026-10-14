package com.volleyballlab.player.pose.review

import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.analysis.AngleCuePolicy
import com.volleyballlab.player.pose.analysis.CameraOrientationDetector
import com.volleyballlab.player.pose.analysis.RepSegmenter
import com.volleyballlab.player.pose.analysis.RepWindow
import com.volleyballlab.player.pose.analysis.SessionScorer
import com.volleyballlab.player.pose.analysis.SkillAnalyzers
import com.volleyballlab.player.pose.model.PoseFrame
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.min

@Serializable
data class ReviewPoint(val x: Float, val y: Float, val confidence: Float) {
    val visible: Boolean get() = x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f && confidence >= 0.5f
}

/** Coordinates belong to the decoded video, including its rotation and mirroring. */
@Serializable
data class ReviewPose(val timestampMs: Long, val points: List<ReviewPoint>)

@Serializable
data class ReviewIssue(
    val timestampMs: Long,
    val repNumber: Int,
    val cueId: CueId,
    val measured: Float,
    val score: Float,
    val points: List<Int>,
)

@Serializable
data class VideoReview(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sampleIntervalMs: Long,
    val frames: List<ReviewPose>,
    val issues: List<ReviewIssue>,
    val assessedCueCount: Int,
    val fromCapture: Boolean = false,
) {
    /** Binary lookup also works after scrubbing backwards. Never hold a lost pose. */
    fun frameAt(positionMs: Long): ReviewPose? {
        if (positionMs !in 0..durationMs || frames.isEmpty()) return null
        val index = frames.binarySearchBy(positionMs) { it.timestampMs }
        val insertion = if (index >= 0) index else -index - 1
        val nearest = listOfNotNull(frames.getOrNull(insertion), frames.getOrNull(insertion - 1))
            .minByOrNull { abs(it.timestampMs - positionMs) } ?: return null
        return nearest.takeIf {
            abs(it.timestampMs - positionMs) <= min(sampleIntervalMs / 2 + 25, 150) && it.points.size == 33
        }
    }

    fun issuesAt(positionMs: Long): List<ReviewIssue> {
        val pose = frameAt(positionMs) ?: return emptyList()
        return issues.filter { issue ->
            abs(issue.timestampMs - positionMs) <= min(sampleIntervalMs / 2 + 25, 100) &&
                issue.points.all { pose.points.getOrNull(it)?.visible == true }
        }
    }
}

/** Fit, rather than crop/stretch, so portrait, landscape and letterboxed video align. */
data class ReviewFit(val width: Float, val height: Float, val left: Float, val top: Float) {
    fun map(point: ReviewPoint): Pair<Float, Float> = left + point.x * width to top + point.y * height

    companion object {
        fun inside(canvasWidth: Float, canvasHeight: Float, videoWidth: Int, videoHeight: Int): ReviewFit {
            val scale = min(canvasWidth / videoWidth.coerceAtLeast(1), canvasHeight / videoHeight.coerceAtLeast(1))
            val width = videoWidth * scale
            val height = videoHeight * scale
            return ReviewFit(width, height, (canvasWidth - width) / 2, (canvasHeight - height) / 2)
        }
    }
}

/** Uses the same phase-specific cue policy as capture; a bent loading arm is not a contact error. */
class VideoReviewFeedback {
    fun analyse(frames: List<PoseFrame>, skill: Skill, sampleIntervalMs: Long): Pair<List<ReviewIssue>, Int> {
        val analyzer = SkillAnalyzers.forSkill(skill)
        val segmenter = RepSegmenter()
        // Tracking gaps must not join two different actions (or two different players).
        val chunks = mutableListOf<MutableList<PoseFrame>>()
        frames.forEach { frame ->
            if (chunks.isEmpty() || frame.timestampMs - chunks.last().last().timestampMs > sampleIntervalMs * 2) {
                chunks.add(mutableListOf())
            }
            chunks.last().add(frame)
        }
        val windows = chunks.flatMap { segmenter.segment(it, analyzer) }
        var assessed = 0
        val issues = windows.flatMapIndexed { index, window ->
            val contact = window.phaseFrames[com.volleyballlab.player.domain.model.RepPhase.Contact]
            val angle = contact?.let { CameraOrientationDetector().detect(it).angle }
                ?: com.volleyballlab.player.domain.model.CameraAngle.Unknown
            val rep = SessionScorer().scoreRep(window, skill, analyzer, angle = angle)
            assessed += rep.cueScores.count { it.score != null }
            rep.cueScores.mapNotNull { scored ->
                val cue = FormCueCatalog.byId(scored.cueId) ?: return@mapNotNull null
                val measured = scored.measured ?: return@mapNotNull null
                val score = scored.score ?: return@mapNotNull null
                issueFor(window, index + 1, scored.cueId, cue.phase, measured, score)
            }
        }
        return issues.sortedWith(compareBy<ReviewIssue> { it.timestampMs }.thenBy { it.score }) to assessed
    }

    internal fun issueFor(
        window: RepWindow,
        repNumber: Int,
        cueId: CueId,
        phase: com.volleyballlab.player.domain.model.RepPhase,
        measured: Float,
        score: Float,
    ): ReviewIssue? {
        if (!measured.isFinite() || !score.isFinite() || score !in 0f..<0.8f) return null
        val frame = window.frameFor(phase) ?: return null
        val side = AngleCuePolicy.completedSide(cueId, window)
        val points = AngleCuePolicy.affectedPoints(cueId, frame, side)
        if (points.isEmpty() || points.any {
                !AngleCuePolicy.isReliable(frame, it) || frame.image(it).x !in 0f..1f || frame.image(it).y !in 0f..1f
            }) return null
        return ReviewIssue(frame.cameraTimestampMs, repNumber, cueId, measured, score, points.map { it.ordinal })
    }
}
