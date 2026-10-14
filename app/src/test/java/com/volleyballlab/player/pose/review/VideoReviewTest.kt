package com.volleyballlab.player.pose.review

import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.TestPoses
import com.volleyballlab.player.pose.analysis.RepWindow
import com.volleyballlab.player.pose.model.BodyPoint
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class VideoReviewTest {
    private val points = List(33) { ReviewPoint(0.5f, 0.5f, 1f) }
    private fun review(frames: List<ReviewPose>, issues: List<ReviewIssue> = emptyList()) =
        VideoReview(1_000, 480, 640, 50, frames, issues, issues.size)

    @Test fun `portrait coordinates align inside landscape letterbox without stretching`() {
        val fit = ReviewFit.inside(400f, 300f, 480, 640)
        assertEquals(225f, fit.width, 0.001f)
        assertEquals(300f, fit.height, 0.001f)
        assertEquals(87.5f to 0f, fit.map(ReviewPoint(0f, 0f, 1f)))
        assertEquals(312.5f to 300f, fit.map(ReviewPoint(1f, 1f, 1f)))
    }

    @Test fun `landscape coordinates align inside portrait letterbox`() {
        val fit = ReviewFit.inside(300f, 400f, 640, 480)
        assertEquals(300f, fit.width, 0.001f)
        assertEquals(225f, fit.height, 0.001f)
        assertEquals(150f to 200f, fit.map(ReviewPoint(0.5f, 0.5f, 1f)))
    }

    @Test fun `lookup supports backwards scrubbing and chooses nearby timestamp`() {
        val data = review(listOf(ReviewPose(0, points), ReviewPose(50, points), ReviewPose(100, points)))
        assertEquals(100L, data.frameAt(90)?.timestampMs)
        assertEquals(0L, data.frameAt(10)?.timestampMs)
        assertNull(data.frameAt(-1))
        assertNull(data.frameAt(1_001))
    }

    @Test fun `tracking loss never holds previous skeleton or annotations`() {
        val issue = ReviewIssue(0, 1, CueId.PassKneeFlexion, 180f, 0f, listOf(25))
        val data = review(listOf(ReviewPose(0, points), ReviewPose(50, emptyList()), ReviewPose(100, points)), listOf(issue))
        assertNull(data.frameAt(50))
        assertTrue(data.issuesAt(50).isEmpty())
        assertNull(data.frameAt(500))
        assertEquals(100L, data.frameAt(100)?.timestampMs)
    }

    @Test fun `correction highlights only its measurement moment and visible joints`() {
        val issue = ReviewIssue(100, 1, CueId.PassKneeFlexion, 180f, 0f, listOf(25))
        val data = review(listOf(ReviewPose(100, points), ReviewPose(300, points)), listOf(issue))
        assertEquals(listOf(issue), data.issuesAt(100))
        assertTrue(data.issuesAt(300).isEmpty())
        val obscured = data.copy(frames = listOf(ReviewPose(100, points.mapIndexed { i, p -> if (i == 25) p.copy(confidence = 0.1f) else p })))
        assertTrue(obscured.issuesAt(100).isEmpty())
    }

    @Test fun `phase-specific correction uses contact rather than loading timestamp`() {
        val loading = TestPoses.wellFramedStanding(100)
        val contact = TestPoses.wellFramedStanding(500)
        val window = RepWindow(0, listOf(loading, contact), mapOf(RepPhase.Loading to loading, RepPhase.Contact to contact), 0.5f)
        val issue = VideoReviewFeedback().issueFor(window, 1, CueId.PassKneeFlexion, RepPhase.Contact, 180f, 0f)
        assertEquals(500L, issue?.timestampMs)
        assertTrue(BodyPoint.LEFT_KNEE.ordinal in issue!!.points)
        assertFalse(BodyPoint.LEFT_ELBOW.ordinal in issue.points)
    }

    @Test fun `unreliable world joint cannot become an error even if image is visible`() {
        val original = TestPoses.wellFramedStanding(500)
        val hidden = original.copy(world = original.world.mapIndexed { i, p -> if (i == BodyPoint.LEFT_KNEE.ordinal) p.copy(presence = 0.1f) else p })
        val window = RepWindow(0, listOf(hidden), mapOf(RepPhase.Contact to hidden), 0.5f)
        assertNull(VideoReviewFeedback().issueFor(window, 1, CueId.PassKneeFlexion, RepPhase.Contact, 180f, 0f))
    }

    @Test fun `good missing or nonfinite measurements do not produce corrections`() {
        val frame = TestPoses.wellFramedStanding(500)
        val window = RepWindow(0, listOf(frame), mapOf(RepPhase.Contact to frame), 0.5f)
        val builder = VideoReviewFeedback()
        assertNull(builder.issueFor(window, 1, CueId.PassKneeFlexion, RepPhase.Contact, 140f, 0.9f))
        assertNull(builder.issueFor(window, 1, CueId.PassKneeFlexion, RepPhase.Contact, Float.NaN, 0f))
        assertNull(builder.issueFor(window, 1, CueId.PassKneeFlexion, RepPhase.Contact, 180f, Float.NaN))
        assertNull(builder.issueFor(window, 1, CueId.PassKneeFlexion, RepPhase.Loading, 180f, 0f))
    }

    @Test fun `a still or untracked video never invents movements or corrections`() {
        val static = List(40) { TestPoses.wellFramedStanding(it * 50L) }
        val (issues, assessed) = VideoReviewFeedback().analyse(static, Skill.Receiving, 50)
        assertTrue(issues.isEmpty())
        assertEquals(0, assessed)
        assertEquals(emptyList<ReviewIssue>() to 0, VideoReviewFeedback().analyse(emptyList(), Skill.Receiving, 50))
    }

    @Test fun `cached replay preserves video coordinates timestamps and cue identity`() {
        val issue = ReviewIssue(100, 2, CueId.PassKneeFlexion, 175f, 0.2f, listOf(25, 26))
        val source = review(listOf(ReviewPose(100, points)), listOf(issue))
        assertEquals(source, Json.decodeFromString<VideoReview>(Json.encodeToString(source)))
    }
}
