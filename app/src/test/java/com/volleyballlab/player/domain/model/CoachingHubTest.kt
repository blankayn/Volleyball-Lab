package com.volleyballlab.player.domain.model

import org.junit.Assert.*
import org.junit.Test

class CoachingHubTest {
    @Test fun responseTimeNeedsThreeRealAnswersAndUsesMedian() {
        assertNull(CoachPublicSummary(responseTimesMs = listOf(10, 20)).typicalResponseMs)
        assertEquals(20L, CoachPublicSummary(responseTimesMs = listOf(10, 999_999, 20)).typicalResponseMs)
        assertEquals(25L, CoachPublicSummary(responseTimesMs = listOf(10, 20, 30, 100)).typicalResponseMs)
    }
    @Test fun ratingsHaveNoFabricatedStartingScore() {
        assertNull(CoachPublicSummary().averageRating)
        val review = CoachReview("id", "coach", "player", "session", 5, "", 1)
        assertEquals(4.0, CoachPublicSummary(reviews = listOf(review, review.copy(rating = 3))).averageRating!!, .001)
    }
    @Test fun questionContextsAreStableAndIntroIsSeparateFromSessionFeedback() {
        assertEquals("coach_player_intro", coachingContextId("coach", "player", null))
        assertNotEquals(coachingContextId("coach", "player", null), coachingContextId("coach", "player", "session"))
        assertEquals("under a minute", coachingResponseLabel(2_000))
        assertEquals("2 hr", coachingResponseLabel(7_200_000))
        assertTrue(hasCoachingLink("Look here\nHTTPS://example.com"))
        assertTrue(hasCoachingLink("www.example.com"))
        assertFalse(hasCoachingLink("How can I improve my contact point?"))
    }
}
