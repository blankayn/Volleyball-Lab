package com.volleyballlab.player.pose.review

import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.pose.analysis.CoachPhrasebook

data class CoachReelClip(val rep: ReviewRep, val issue: ReviewIssue,
    val sourceStartMs: Long, val sourceEndMs: Long, val freezeAtMs: Long) {
    val label: String get() = (if (rep.angle == CameraAngle.Unknown) "" else "${rep.angle.name} · ") + "Rep ${rep.repNumber}"
}

/** Selection only. Playback always seeks the original private recording. */
object CoachReel {
    fun reps(review: VideoReview): List<ReviewRep> = review.reps.ifEmpty {
        review.issues.groupBy { it.repNumber }.mapNotNull { (number, issues) ->
            val visible = issues.filter { issue -> review.frameAt(issue.timestampMs)?.let { pose ->
                issue.points.isNotEmpty() && issue.points.all { pose.points.getOrNull(it)?.visible == true }
            } == true }
            if (visible.isEmpty()) null else ReviewRep(number,
                (visible.minOf { it.timestampMs } - 1_200).coerceAtLeast(0), visible.first().timestampMs,
                (visible.maxOf { it.timestampMs } + 1_200).coerceAtMost(review.durationMs), null,
                visible.map { it.angle }.distinct().singleOrNull() ?: CameraAngle.Unknown)
        }
    }

    fun build(review: VideoReview): List<CoachReelClip> {
        val candidates = reps(review).mapNotNull { rep ->
            val issue = review.issues.filter { it.repNumber == rep.repNumber && it.score.isFinite() && it.measured.isFinite() &&
                it.score < 0.8f && it.timestampMs in rep.startMs..rep.endMs &&
                review.frameAt(it.timestampMs)?.let { pose -> it.points.isNotEmpty() && it.points.all { p -> pose.points.getOrNull(p)?.visible == true } } == true
            }.minWithOrNull(compareBy<ReviewIssue> { it.score }.thenByDescending { FormCueCatalog.byId(it.cueId)?.weight ?: 0f })
                ?: return@mapNotNull null
            val start = maxOf(0L, rep.startMs, issue.timestampMs - 4_000)
            val end = minOf(review.durationMs, rep.endMs, start + 8_000)
            if (end <= start) null else CoachReelClip(rep, issue, start, end, issue.timestampMs.coerceIn(start, end))
        }.sortedWith(compareBy<CoachReelClip> { it.issue.score }.thenByDescending { FormCueCatalog.byId(it.issue.cueId)?.weight ?: 0f }
            .thenBy { it.freezeAtMs })
        val selected = mutableListOf<CoachReelClip>()
        fun add(clip: CoachReelClip) {
            if (selected.size < 6 && selected.none { it.rep.repNumber == clip.rep.repNumber ||
                    (it.sourceStartMs < clip.sourceEndMs && clip.sourceStartMs < it.sourceEndMs) }) selected += clip
        }
        // Reserve measured failures in each required view before filling by severity.
        listOf(CameraAngle.Front, CameraAngle.Side).forEach { angle -> candidates.firstOrNull { it.rep.angle == angle }?.let(::add) }
        candidates.forEach(::add)
        return selected.sortedWith(compareBy<CoachReelClip> { it.issue.score }.thenBy { it.freezeAtMs })
    }
}

object ReelComment {
    fun what(issue: ReviewIssue): String {
        val cue = FormCueCatalog.byId(issue.cueId) ?: return "This measured position was outside the practice range."
        return if (issue.measured < cue.target.start) "The measured position was below the practice range."
        else if (issue.measured > cue.target.endInclusive) "The measured position was above the practice range."
        else "The posture model flagged this cue; the displayed angle alone does not explain the result."
    }
    fun why(id: CueId): String = when (id) {
        CueId.PassKneeFlexion, CueId.SetKneeFlexion, CueId.SpikeApproachLoad, CueId.BlockKneeLoad ->
            "A balanced knee bend helps you prepare for the movement."
        CueId.PassPlatformStraight -> "Joined, straight forearms help form a consistent receiving platform."
        CueId.PassShouldersLevel -> "Level shoulders help you maintain a balanced receiving posture."
        CueId.PassTrunkAngle, CueId.ServeTrunkLean, CueId.SpikeTrunkDrive ->
            "Trunk position is part of maintaining a balanced posture."
        else -> "This describes one part of your starting posture, body alignment, or finishing position."
    }
    fun practice(issue: ReviewIssue): String = CoachPhrasebook.action(issue.cueId, issue.measured) + "."
}
