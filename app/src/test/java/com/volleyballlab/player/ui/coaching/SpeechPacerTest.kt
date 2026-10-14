package com.volleyballlab.player.ui.coaching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPacerTest {
    private fun message(
        key: String,
        time: Long,
        priority: SpeechPriority = SpeechPriority.Correction,
        ttl: Long = 5_000,
    ) = CoachingSpeech("Cue $key", key, priority, time, ttl)

    @Test
    fun `speech waits four seconds even after previous utterance finishes`() {
        val pacer = SpeechPacer()
        val first = message("elbow", 0)
        pacer.offer(first, 0)
        assertEquals(first, pacer.takeReady(0, false))
        pacer.markSpoken(first, 0)
        val second = message("knee", 1_000)
        pacer.offer(second, 1_000)
        assertNull(pacer.takeReady(3_999, false))
        assertEquals(second, pacer.takeReady(4_000, false))
    }

    @Test
    fun `same correction cannot repeat until twelve seconds`() {
        val pacer = SpeechPacer()
        val first = message("elbow", 0)
        pacer.markSpoken(first, 0)
        assertFalse(pacer.offer(message("elbow", 11_999), 11_999))
        assertTrue(pacer.offer(message("elbow", 12_000), 12_000))
        assertEquals("elbow", pacer.takeReady(12_000, false)?.key)
    }

    @Test
    fun `long speech never overlaps new speech`() {
        val pacer = SpeechPacer()
        val pending = message("rotate", 1_000, SpeechPriority.Rotation, ttl = 15_000)
        pacer.offer(pending, 1_000)
        assertNull(pacer.takeReady(8_000, true))
        assertEquals(pending, pacer.takeReady(8_000, false))
    }

    @Test
    fun `latest correction replaces pending correction with no backlog`() {
        val pacer = SpeechPacer()
        pacer.offer(message("elbow", 0), 0)
        pacer.offer(message("knee", 500), 500)
        assertEquals("knee", pacer.takeReady(500, false)?.key)
        assertNull(pacer.takeReady(10_000, false))
    }

    @Test
    fun `rotation wins over rep celebrations and corrections`() {
        val pacer = SpeechPacer()
        pacer.offer(message("elbow", 0), 0)
        pacer.offer(message("rotate", 100, SpeechPriority.Rotation), 100)
        assertFalse(pacer.offer(message("good-rep", 200, SpeechPriority.Milestone), 200))
        assertEquals("rotate", pacer.takeReady(200, false)?.key)
    }

    @Test
    fun `expired speech drops rather than playing after athlete moves`() {
        val pacer = SpeechPacer()
        pacer.offer(message("elbow", 0), 0)
        assertNull(pacer.takeReady(5_000, false))
        assertFalse(pacer.hasPending)
    }

    @Test
    fun `expired high priority message cannot block fresh correction`() {
        val pacer = SpeechPacer()
        pacer.offer(message("tracking", 0, SpeechPriority.Tracking, ttl = 1_000), 0)
        assertTrue(pacer.offer(message("elbow", 1_000), 1_000))
        assertEquals("elbow", pacer.takeReady(1_000, false)?.key)
    }

    @Test
    fun `stop discards pending speech and preserves spacing`() {
        val pacer = SpeechPacer()
        pacer.markSpoken(message("elbow", 0), 0)
        pacer.offer(message("knee", 500), 500)
        pacer.clearPending()
        assertNull(pacer.takeReady(4_000, false))
        pacer.offer(message("tracking", 1_000, SpeechPriority.Tracking), 1_000)
        assertNull(pacer.takeReady(2_000, false))
    }

    @Test
    fun `blank cues and future timestamps are never spoken`() {
        val pacer = SpeechPacer()
        assertFalse(pacer.offer(message("elbow", 2_000), 1_000))
        assertFalse(pacer.offer(CoachingSpeech(" ", "empty", SpeechPriority.Correction, 0), 0))
    }
}
