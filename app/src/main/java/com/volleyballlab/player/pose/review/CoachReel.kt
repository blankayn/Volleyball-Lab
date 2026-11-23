package com.volleyballlab.player.pose.review

import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.pose.analysis.CoachPhrasebook

/** A rep as the reel lists it: its corrections, worst first, and the part of the recording that shows it. */
data class ReelRep(val rep: ReviewRep, val issues: List<ReviewIssue>, val startMs: Long, val endMs: Long, val freezeAtMs: Long?)

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

    /** Every rep in the order it was performed, with or without corrections. */
    fun repList(review: VideoReview): List<ReelRep> {
        val issues = review.issues.filter { it.score.isFinite() && it.measured.isFinite() && it.score < 0.8f }
        val known = reps(review)
        // A correction whose rep boundaries were never saved is still worth listing.
        val rest = issues.filter { issue -> known.none { it.repNumber == issue.repNumber } }.groupBy { it.repNumber }.map { (number, found) ->
            ReviewRep(number, (found.minOf { it.timestampMs } - 1_200).coerceAtLeast(0), found.first().timestampMs,
                (found.maxOf { it.timestampMs } + 1_200).coerceAtMost(review.durationMs), null,
                found.map { it.angle }.distinct().singleOrNull() ?: CameraAngle.Unknown)
        }
        return (known + rest).sortedWith(compareBy<ReviewRep> { it.startMs }.thenBy { it.repNumber }).map { rep ->
            val own = issues.filter { it.repNumber == rep.repNumber }
                .sortedWith(compareBy<ReviewIssue> { it.score }.thenByDescending { FormCueCatalog.byId(it.cueId)?.weight ?: 0f })
            val freeze = own.firstOrNull()?.timestampMs?.takeIf { it in rep.startMs..rep.endMs }
            val start = maxOf(0L, rep.startMs, (freeze ?: rep.startMs) - 4_000)
            ReelRep(rep, own, start, minOf(review.durationMs, rep.endMs, start + 8_000), freeze)
        }
    }

    /** The rep with the worst correction, or the first one. */
    fun startingRep(reps: List<ReelRep>): Int =
        (reps.filter { it.issues.isNotEmpty() }.minByOrNull { it.issues.first().score } ?: reps.firstOrNull())?.rep?.repNumber ?: 0
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
