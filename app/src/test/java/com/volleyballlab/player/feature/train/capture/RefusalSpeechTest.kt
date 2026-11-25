package com.volleyballlab.player.feature.train.capture

import com.volleyballlab.player.pose.analysis.CoachingEventType
import com.volleyballlab.player.ui.coaching.CoachingSpeech
import com.volleyballlab.player.ui.coaching.SpeechPacer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A refusal heard after the next rep has been added sounds like that rep was the wrong one. */
class RefusalSpeechTest {
    private val refusal = speechDelivery(CoachingEventType.NotCounted, isRep = false)
    private val count = speechDelivery(CoachingEventType.RepMilestone, isRep = true)

    private fun line(text: String, delivery: SpeechDelivery, at: Long) =
        CoachingSpeech(text, text, delivery.priority, at, delivery.expiresInMs, delivery.style)

    @Test fun `a refusal and a count share one lane`() {
        assertEquals(count.priority, refusal.priority)
        assertTrue(refusal.expiresInMs <= 1_500)
    }

    @Test fun `a refusal is spoken at once, without the correction cooldown`() {
        val pacer = SpeechPacer()
        pacer.markSpoken(line("One.", count, 0), 0)
        pacer.offer(line("Not counted. Both arms.", refusal, 1_000), 1_000)
        assertEquals("Not counted. Both arms.", pacer.takeReady(1_000, isSpeaking = false)?.text)
    }

    @Test fun `the newest outcome replaces an older one still waiting`() {
        val pacer = SpeechPacer()
        pacer.offer(line("Not counted. Both arms.", refusal, 0), 0)
        assertNull(pacer.takeReady(100, isSpeaking = true))
        pacer.offer(line("Two.", count, 700), 700)
        assertEquals("Two.", pacer.takeReady(800, isSpeaking = false)?.text)
        assertNull(pacer.takeReady(900, isSpeaking = false))
    }

    @Test fun `a refusal that could not be spoken in time is dropped, not said late`() {
        val pacer = SpeechPacer()
        pacer.offer(line("Not counted. Both arms.", refusal, 0), 0)
        assertNull(pacer.takeReady(1_600, isSpeaking = false))
    }
}
