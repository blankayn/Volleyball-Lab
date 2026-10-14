package com.volleyballlab.player.pose.review

import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.SyntheticPoseDetector
import com.volleyballlab.player.pose.TestPoses
import com.volleyballlab.player.pose.analysis.SessionScorer
import org.junit.Assert.*
import org.junit.Test

class CapturedVideoReviewTest {
    @Test fun `clock uses recorder duration not delayed pose result delivery`() {
        val clock = RecordingVideoClock()
        clock.started(99_900)
        clock.observe(101_000, 1_000)
        clock.observe(102_005, 2_000)
        clock.observe(103_000, 3_000)
        clock.observe(104_900, 4_000) // delayed/outlying callback
        assertEquals(100_005L, clock.origin())
        clock.reset()
        assertNull(clock.origin())
        clock.started(42)
        assertEquals(42L, clock.origin())
    }

    @Test fun `replay uses original camera clock even after monotonic correction`() {
        val frame = TestPoses.wellFramedStanding(500_000).copy(cameraTimestampMs = 100_200, mirrored = true)
        val issue = ReviewIssue(100_200, 2, CueId.PassKneeFlexion, 175f, 0f, listOf(25, 26))
        val review = CapturedReviewInput(listOf(frame), listOf(issue), 2, 100_000).toReview(1_000, 480, 640)
        assertTrue(review.fromCapture)
        assertEquals(200L, review.frames.single().timestampMs)
        assertEquals(200L, review.issues.single().timestampMs)
        // Raw pixels are unmirrored in the MP4; a mirrored preview must not flip replay coordinates.
        assertEquals(frame.image[25].x, review.frames.single().points[25].x, 0f)
    }

    @Test fun `frames and errors outside encoded duration are excluded`() {
        val frames = listOf(-50L, 0L, 100L, 2_000L).map { TestPoses.wellFramedStanding(100_000 + it) }
        val issues = listOf(99_950L, 100_100L, 102_000L).map { ReviewIssue(it, 1, CueId.PassKneeFlexion, 175f, 0f, listOf(25)) }
        val review = CapturedReviewInput(frames, issues, 3, 100_000).toReview(1_000, 480, 640)
        assertEquals(listOf(0L, 100L), review.frames.map { it.timestampMs })
        assertEquals(listOf(100L), review.issues.map { it.timestampMs })
    }

    @Test fun `captured final feedback preserves the original scoring and exact phase values`() {
        val detector = SyntheticPoseDetector()
        val frames = (0L..9_600L step 50).map { detector.frameAt(it).let { frame ->
            frame.copy(image = frame.image.map { it.copy(x = 1f - it.x) }, cameraTimestampMs = frame.timestampMs + 100_000)
        } }
        val scorer = SessionScorer()
        val detailed = scorer.scoreWithReview("capture", Skill.Setting, "set", 0, frames)
        assertEquals(scorer.score("capture", Skill.Setting, "set", 0, frames), detailed.session)
        assertTrue(detailed.session.reps.isNotEmpty())
        assertTrue(detailed.issues.isNotEmpty())
        detailed.issues.forEach { issue ->
            val scored = detailed.session.reps[issue.repNumber - 1].cueScores.first { it.cueId == issue.cueId }
            assertEquals(scored.measured, issue.measured)
            assertEquals(scored.score, issue.score)
            assertTrue(frames.any { it.cameraTimestampMs == issue.timestampMs })
        }
    }

    @Test fun `a tracking gap cannot hold a captured skeleton over missing video moments`() {
        val review = CapturedReviewInput(listOf(TestPoses.wellFramedStanding(100), TestPoses.wellFramedStanding(1_000)), emptyList(), 0, 0)
            .toReview(1_500, 480, 640)
        assertNull(review.frameAt(600))
        assertEquals(1_000L, review.frameAt(1_000)?.timestampMs)
    }
}
