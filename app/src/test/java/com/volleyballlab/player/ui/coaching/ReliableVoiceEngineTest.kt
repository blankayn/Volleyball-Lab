package com.volleyballlab.player.ui.coaching

import org.junit.Assert.*
import org.junit.Test

class ReliableVoiceEngineTest {
    private class Harness(val initialize: Boolean = true) {
        var now = 0L
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val spoken = mutableListOf<String>()
        val styles = mutableListOf<SpeechStyle>()
        val finished = mutableListOf<String>()
        var status = CoachingAudioStatus.Initializing
        var fail: (String) -> Unit = {}
        var done: (String) -> Unit = {}
        val voice = ReliableVoiceEngine({ complete, error ->
            fail = error; done = complete
            object : VoiceEngine {
                override fun initialize(onReady: (Boolean) -> Unit) { if (initialize) onReady(true) }
                override fun speak(text: String, utteranceId: String, flush: Boolean, style: SpeechStyle): Boolean {
                    spoken += text; styles += style; return true
                }
                override fun stop() = Unit
                override fun shutdown() = Unit
            }
        }, { delay, action -> tasks += now + delay to action }, { status = it }, { finished += it })
        init { voice.initialize {} }
        fun advance(ms: Long) {
            val target = now + ms
            while (tasks.any { it.first <= target }) {
                val next = tasks.minBy { it.first }; tasks.remove(next); now = next.first; next.second()
            }
            now = target
        }
    }
    @Test fun missingInitializationIsBounded() {
        val h = Harness(false); h.advance(6_000)
        assertEquals(CoachingAudioStatus.Unavailable, h.status)
    }
    @Test fun oneErrorRetriesAndSecondErrorDegradesWithoutBlockingCompletion() {
        val h = Harness(); h.voice.speak("Set your arms", "a", true)
        h.fail("a"); h.advance(300)
        assertEquals(listOf("Set your arms", "Set your arms"), h.spoken)
        h.fail("a")
        assertEquals(CoachingAudioStatus.Degraded, h.status)
        assertEquals(listOf("a"), h.finished)
    }
    @Test fun oldWatchdogCannotFinishReplacement() {
        val h = Harness(); h.voice.speak("A", "a", true); h.advance(1_000)
        h.voice.speak("B", "b", true); h.advance(14_000)
        assertTrue(h.finished.isEmpty()); h.done("b")
        assertEquals(listOf("b"), h.finished)
    }
    @Test fun aLongTurnIsNotMistakenForAStalledEngine() {
        val h = Harness(); val long = "Slowly extend both elbows. ".repeat(8).trim()
        h.voice.speak(long, "long", true, SpeechStyle.Correction)
        h.advance(SpeechTiming.watchdogMs(long) - 1)
        assertEquals(listOf(long), h.spoken)
        assertEquals(listOf(SpeechStyle.Correction), h.styles)
        assertTrue(h.finished.isEmpty())
        h.done("long")
        assertEquals(listOf("long"), h.finished)
        // A short cue still recovers from a missing callback, sooner than the old fixed bound.
        h.voice.speak("One.", "count", true, SpeechStyle.Count); h.advance(8_300)
        assertEquals(listOf(long, "One.", "One."), h.spoken)
        assertEquals(SpeechStyle.Count, h.styles.last())
    }
    @Test fun fiftyCuesCompleteAndStopDropsRetry() {
        val h = Harness()
        repeat(50) { h.voice.speak("Cue $it", "$it", true); h.done("$it") }
        assertEquals(50, h.finished.size)
        h.voice.speak("stale", "stale", true); h.fail("stale"); h.voice.stop(); h.advance(300)
        assertEquals(51, h.spoken.size)
        assertEquals(CoachingAudioStatus.Ready, h.status)
        h.voice.speak("fresh", "fresh", true)
        assertEquals("fresh", h.spoken.last())
    }
}
