package com.volleyballlab.player.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionExplanationDecoderTest {
    private fun response(text: String = "Keep the trunk close to upright.", count: Int = 5) = mapOf(
        "status" to "ready",
        "evidence" to mapOf("limitedCues" to listOf("ServeTossArmLift"), "cues" to listOf(mapOf("id" to "ServeTrunkLean", "label" to "Stay tall", "measuredReps" to count, "score" to 100, "tip" to "Stay upright."))),
        "explanation" to mapOf("overview" to "The saved measurements show a stable starting position.", "strengths" to listOf(mapOf("cueId" to "ServeTrunkLean", "explanation" to text)), "priorities" to emptyList<Any>()),
    )
    @Test fun `decodes cue-linked prose and keeps numeric data separate`() {
        val result = SessionExplanationDecoder.decode(response())
        assertEquals("ready", result.status)
        assertEquals(5, result.strengths.single().measuredReps)
        assertEquals(100, result.strengths.single().score)
        assertEquals(1, result.limitedCueCount)
    }
    @Test fun `rejects sparse findings and invented numbers in prose`() {
        assertThrows(IllegalArgumentException::class.java) { SessionExplanationDecoder.decode(response(count = 1)) }
        assertThrows(IllegalArgumentException::class.java) { SessionExplanationDecoder.decode(response(text = "Try 20 repetitions.")) }
    }
    @Test fun `empty and insufficient results do not require generated text`() {
        assertEquals("empty", SessionExplanationDecoder.decode(mapOf("status" to "empty", "evidence" to emptyMap<String, Any>())).status)
        assertEquals("insufficient", SessionExplanationDecoder.decode(mapOf("status" to "insufficient", "evidence" to emptyMap<String, Any>())).status)
    }
}
