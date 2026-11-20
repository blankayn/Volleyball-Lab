package com.volleyballlab.player.pose.review

import com.volleyballlab.player.domain.model.*
import kotlin.math.abs

data class PosturePane(val angle: CameraAngle, val repNumber: Int?, val timestampMs: Long?,
    val pose: ReviewPose?, val issue: ReviewIssue?)
data class PostureComparison(val front: PosturePane, val side: PosturePane, val referencePhase: Int, val issue: ReviewIssue)

object PostureComparisonBuilder {
    fun forIssue(review: VideoReview, selected: ReviewIssue): PostureComparison {
        val reps = CoachReel.reps(review)
        fun pane(angle: CameraAngle): PosturePane {
            val matching = review.issues.filter { it.angle == angle && it.cueId == selected.cueId }
            val issue = selected.takeIf { it.angle == angle } ?: matching
                .minWithOrNull(compareBy<ReviewIssue> { it.score }.thenBy { it.timestampMs })
            val rep = reps.filter { it.angle == angle }.minByOrNull { abs(it.contactMs - selected.timestampMs) }
            val time = issue?.timestampMs ?: rep?.contactMs
            val pose = time?.let(review::frameAt)
            val visibleIssue = issue?.copy(points = issue.points.filter { pose?.points?.getOrNull(it)?.visible == true })
            return PosturePane(angle, issue?.repNumber ?: rep?.repNumber, time, pose, visibleIssue)
        }
        return PostureComparison(pane(CameraAngle.Front), pane(CameraAngle.Side),
            FormCueCatalog.byId(selected.cueId)?.phase?.ordinal?.coerceIn(0, 3) ?: 0, selected)
    }
}
