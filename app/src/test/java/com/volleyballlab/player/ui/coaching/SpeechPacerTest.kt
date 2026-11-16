package com.volleyballlab.player.ui.coaching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPacerTest {
    @Test fun repCountsFollowTheRepRhythmWithoutOverlappingSpeech() {
        val pacer = SpeechPacer()
        val first = message("rep-1", 0, SpeechPriority.RepFeedback)
        pacer.offer(first, 0)
        assertEquals(first, pacer.takeReady(0, false))
        pacer.markSpoken(first, 0)
        val second = message("rep-2", 1_000, SpeechPriority.RepFeedback)
        pacer.offer(second, 1_000)
        assertNull(pacer.takeReady(1_000, true))
        assertEquals(second, pacer.takeReady(1_100, false))
    }

    @Test fun aNewRepReplacesAnOldUnspokenCountAndInterruptedSpeechCannotRestoreIt() {
        val pacer = SpeechPacer()
        val first = message("rep-1", 0, SpeechPriority.RepFeedback)
        pacer.markSpoken(first, 0)
        pacer.offer(message("rep-2", 500, SpeechPriority.RepFeedback), 500)
        pacer.restoreInterrupted(first, 600)
        assertEquals("rep-2", pacer.takeReady(600, false)?.key)
        assertNull(pacer.takeReady(1_000, false))
    }

    @Test fun trackingAndRotationStillTakePriorityOverRepCounts() {
        for (priority in listOf(SpeechPriority.Tracking, SpeechPriority.Rotation)) {
            val pacer = SpeechPacer()
            pacer.offer(message("guidance", 0, priority), 0)
            assertFalse(pacer.offer(message("rep-1", 100, SpeechPriority.RepFeedback), 100))
            assertEquals("guidance", pacer.takeReady(100, false)?.key)
        }
    }

    @Test fun interruptedCueSurvivesFocusLossButExpiredCueDoesNotReturn() {
        val pacer = SpeechPacer()
        val cue = CoachingSpeech("Set the platform", "platform", SpeechPriority.Correction, 0, 5_000)
        pacer.offer(cue, 0)
        // Focus denial happens before takeReady: the pending cue remains untouched.
        assertTrue(pacer.hasPending)
        assertEquals(cue, pacer.takeReady(250, false))
        pacer.markSpoken(cue, 250)
        pacer.restoreInterrupted(cue, 500)
        assertEquals(cue, pacer.takeReady(500, false))
        pacer.restoreInterrupted(cue, 6_000)
        assertFalse(pacer.hasPending)
    }

    @Test fun aCorrectionOutranksACountAndIsNeverReplacedByOne() {
        val pacer = SpeechPacer()
        val dropped = mutableListOf<String>()
        pacer.onDropped = { dropped += it.key }
        pacer.offer(message("rep-3", 0, SpeechPriority.RepFeedback), 0)
        assertTrue(pacer.offer(message("rep-4", 500, SpeechPriority.Coaching, ttl = 6_000), 500))
        assertFalse(pacer.offer(message("rep-5", 1_000, SpeechPriority.RepFeedback), 1_000))
        assertEquals(listOf("rep-3", "rep-5"), dropped)
        // Like a count, a coaching rep turn follows the movement without the cooldown.
        pacer.markSpoken(message("rep-2", 400, SpeechPriority.RepFeedback), 400)
        assertEquals("rep-4", pacer.takeReady(1_200, false)?.key)
        for (priority in listOf(SpeechPriority.Tracking, SpeechPriority.Rotation)) {
            assertTrue(SpeechPriority.Coaching < priority)
            val urgent = SpeechPacer()
            urgent.offer(message("fix", 0, SpeechPriority.Coaching), 0)
            assertTrue(urgent.offer(message("urgent", 100, priority), 100))
        }
    }

    @Test fun anUnspokenLineIsReportedHoweverItIsLost() {
        val pacer = SpeechPacer()
        val dropped = mutableListOf<String>()
        pacer.onDropped = { dropped += it.key }
        pacer.offer(message("expired", 0, SpeechPriority.Coaching, ttl = 6_000), 0)
        assertNull(pacer.takeReady(6_000, false))
        pacer.offer(message("cleared", 7_000, SpeechPriority.Coaching), 7_000)
        pacer.clearPending()
        val spoken = message("spoken", 8_000, SpeechPriority.Coaching)
        pacer.offer(spoken, 8_000)
        assertEquals(spoken, pacer.takeReady(8_000, false))
        pacer.clearPending()
        assertEquals(listOf("expired", "cleared"), dropped)
    }

    @Test fun watchdogsScaleWithTheTextAndNeverDropBelowSixSeconds() {
        assertEquals(6_000, SpeechTiming.watchdogMs("One."))
        assertEquals(90L * 200 + 3_000, SpeechTiming.watchdogMs("x".repeat(200)))
        assertEquals(0, SpeechTiming.turnMs(listOf("", " ")))
        val turn = listOf("Hi Maya.", "Today's about your forearm platform.", "I'll watch the first couple before I say anything.")
        assertEquals(90L * turn.sumOf { it.length } + 3_000 + 600, SpeechTiming.turnMs(turn))
    }

    private class Turn {
        var now = 0L
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val spoken = mutableListOf<String>()
        var finished = 0
        val queue = GuidanceQueue({ delay, action -> tasks += now + delay to action })
        fun play() { queue.next()?.let { spoken += it.text } }
        fun advance(ms: Long) {
            val target = now + ms
            while (tasks.any { it.first <= target }) {
                val next = tasks.minBy { it.first }; tasks.remove(next); now = next.first; next.second()
            }
            now = target
        }
        fun begin(vararg text: String) = queue.begin(text.map { GuidanceSegment(it) }) { finished++ }
    }

    @Test fun aTurnSpeaksItsSegmentsInOrderWithAPauseAndCompletesOnce() {
        val turn = Turn()
        assertTrue(turn.begin("Hi Maya.", "", "Bend your knees.", "Go."))
        turn.play()
        turn.queue.segmentDone(turn::play)
        turn.advance(299); assertEquals(listOf("Hi Maya."), turn.spoken)
        turn.advance(1); assertEquals(listOf("Hi Maya.", "Bend your knees."), turn.spoken)
        turn.queue.segmentDone(turn::play); turn.advance(300)
        assertEquals(0, turn.finished)
        turn.queue.segmentDone(turn::play)
        assertEquals(1, turn.finished)
        assertFalse(turn.queue.active)
        turn.queue.segmentDone(turn::play); turn.queue.cancel(); turn.advance(1_000)
        assertEquals(1, turn.finished)
        assertEquals(listOf("Hi Maya.", "Bend your knees.", "Go."), turn.spoken)
    }

    @Test fun anInterruptedOrEmptyTurnStillCompletesAndNeverSpeaksLate() {
        val turn = Turn()
        assertFalse(turn.begin("", " "))
        assertEquals(1, turn.finished)
        turn.begin("One.", "Two.", "Three.")
        turn.play(); turn.queue.segmentDone(turn::play)
        assertEquals(listOf("Two.", "Three."), turn.queue.cancel().map { it.text })
        assertEquals(2, turn.finished)
        // The pause scheduled by the cancelled turn cannot speak into the next one.
        turn.begin("Fresh.")
        turn.advance(300)
        assertEquals(listOf("One."), turn.spoken)
    }

    @Test fun aLateLineJoinsBeforeTheFinalInstructionOrNotAtAll() {
        val turn = Turn()
        turn.begin("Five.", "Front view done.", "Turn sideways to the camera.")
        turn.play()
        assertTrue(turn.queue.insertBeforeLast(GuidanceSegment("Reach a little taller.")))
        repeat(3) { turn.queue.segmentDone(turn::play); turn.advance(300) }
        assertEquals(listOf("Five.", "Front view done.", "Reach a little taller.", "Turn sideways to the camera."), turn.spoken)
        assertFalse("The instruction has started", turn.queue.insertBeforeLast(GuidanceSegment("Too late.")))
        turn.queue.segmentDone(turn::play)
        assertFalse(turn.queue.insertBeforeLast(GuidanceSegment("After the turn.")))
        assertEquals(1, turn.finished)
    }

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
