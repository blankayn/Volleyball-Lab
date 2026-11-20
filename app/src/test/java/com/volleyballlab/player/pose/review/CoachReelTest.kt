package com.volleyballlab.player.pose.review

import com.volleyballlab.player.domain.model.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CoachReelTest {
    private fun review(): VideoReview {
        val reps = (1..9).map { ReviewRep(it, it * 3_000L, it * 3_000L + 500, it * 3_000L + 1_500, 20,
            if (it == 9) CameraAngle.Side else CameraAngle.Front) }
        val issues = reps.map { ReviewIssue(it.contactMs, it.repNumber, CueId.PassPlatformStraight,
            130f, if (it.repNumber == 9) .7f else .1f, listOf(11, 13, 15), it.angle) }
        return VideoReview(30_000, 480, 640, 50, issues.map { issue ->
            ReviewPose(issue.timestampMs, List(33) { ReviewPoint(.5f, .5f, 1f) }) }, issues, 9, reps = reps)
    }
    @Test fun reservesBothViewsCapsSixAndUsesOriginalVideoTimes() {
        val clips = CoachReel.build(review())
        assertEquals(6, clips.size)
        assertEquals(setOf(CameraAngle.Front, CameraAngle.Side), clips.map { it.rep.angle }.toSet())
        assertTrue(clips.all { it.freezeAtMs in it.sourceStartMs..it.sourceEndMs && it.sourceEndMs - it.sourceStartMs <= 8_000 })
        assertTrue(clips.zipWithNext().all { (a, b) -> a.issue.score <= b.issue.score })
    }
    @Test fun legacyDefaultsAreUnknownAndMissingSkeletonIsNotAFaultClip() {
        val old = Json.decodeFromString<VideoReview>("""{"durationMs":1000,"width":480,"height":640,"sampleIntervalMs":50,"frames":[],"issues":[],"assessedCueCount":0}""")
        assertTrue(old.reps.isEmpty()); assertNull(old.guided); assertNull(old.guidedCapture)
        assertTrue(CoachReel.build(review().copy(frames = emptyList())).isEmpty())
    }
    @Test fun missingSideIsHonestAndNoIssueMeansNoHighlight() {
        val data = review().let { it.copy(issues = it.issues.filter { issue -> issue.angle == CameraAngle.Front }) }
        val comparison = PostureComparisonBuilder.forIssue(data, data.issues.first())
        assertNotNull(comparison.side.timestampMs)
        assertNull(comparison.side.issue)
        val missing = PostureComparisonBuilder.forIssue(data.copy(reps = data.reps.filter { it.angle == CameraAngle.Front }), data.issues.first())
        assertNull(missing.side.timestampMs); assertNull(missing.side.pose)
    }
    @Test fun guidedCompletionRequiresFrontAndSideTarget() {
        assertFalse(GuidedReview(5, listOf(GuidedStageReview(CameraAngle.Front, 5, 3, true))).complete)
        assertFalse(GuidedReview(5, listOf(GuidedStageReview(CameraAngle.Side, 5, 3, true))).complete)
        assertTrue(GuidedReview(5, listOf(GuidedStageReview(CameraAngle.Front, 1, 0, false), GuidedStageReview(CameraAngle.Side, 5, 3, true))).complete)
    }
}
