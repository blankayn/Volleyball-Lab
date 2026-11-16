package com.volleyballlab.player.ui.coaching

/** Larger priorities replace stale lower-priority feedback before it is spoken. */
enum class SpeechPriority {
    Improvement, Milestone, Correction, RepFeedback,
    /** A rep turn that carries a correction or a confirmed improvement. A plain count never replaces it. */
    Coaching,
    Tracking, Rotation,
}

/** How a line is delivered. The words are unchanged; only rate and pitch differ. */
enum class SpeechStyle { Count, Praise, Correction, Guidance, Urgent }

data class CoachingSpeech(
    val text: String,
    val key: String,
    val priority: SpeechPriority,
    val createdAtMs: Long,
    val expiresInMs: Long = 5_000,
    val style: SpeechStyle = SpeechStyle.Guidance,
)

/** One estimate for every bounded wait on speech, so no turn is cut short or trapped. */
object SpeechTiming {
    const val SEGMENT_PAUSE_MS = 300L

    /** Generous upper bound for one utterance: about ninety milliseconds a character plus start-up. */
    fun watchdogMs(text: String): Long = maxOf(6_000L, 90L * text.length + 3_000L)

    /** Upper bound for a whole multi-sentence turn, including the pauses between segments. */
    fun turnMs(segments: List<String>): Long {
        val spoken = segments.filter { it.isNotBlank() }
        if (spoken.isEmpty()) return 0L
        return maxOf(6_000L, 90L * spoken.sumOf { it.length } + 3_000L) +
            SEGMENT_PAUSE_MS * (spoken.size - 1)
    }
}

data class GuidanceSegment(
    val text: String,
    val style: SpeechStyle = SpeechStyle.Guidance,
    val voicePath: String? = null,
)

/**
 * The segments of one coaching turn, spoken one at a time with a pause between
 * sentences. Completion fires exactly once however the turn ends: finished,
 * muted, interrupted or timed out. No Android dependency.
 */
class GuidanceQueue(
    private val schedule: (delayMs: Long, action: () -> Unit) -> Unit,
    private val pauseMs: Long = SpeechTiming.SEGMENT_PAUSE_MS,
) {
    private val pending = ArrayDeque<GuidanceSegment>()
    private var finished: (() -> Unit)? = null
    private var turn = 0L
    private var lateJoinWaitMs = 0L
    private var waitingPlay: (() -> Unit)? = null

    val active: Boolean get() = finished != null
    val hasNext: Boolean get() = pending.isNotEmpty()

    /** Starts a turn. Returns false, having already completed, when there is nothing to say. */
    fun begin(segments: List<GuidanceSegment>, lateJoinWaitMs: Long = 0L, onFinished: () -> Unit): Boolean {
        check(!active) { "Cancel the current turn before starting another." }
        turn++
        waitingPlay = null
        // How long the final segment may be held for a line that is still on its way.
        this.lateJoinWaitMs = lateJoinWaitMs.coerceIn(0L, 1_500L)
        pending.clear()
        pending.addAll(segments.filter { it.text.isNotBlank() })
        if (pending.isEmpty()) { onFinished(); return false }
        finished = onFinished
        return true
    }

    fun next(): GuidanceSegment? = pending.removeFirstOrNull()

    /**
     * A late line joins the turn just before its final segment, which is the
     * instruction the athlete must act on. Refused once that segment has started.
     */
    fun insertBeforeLast(segment: GuidanceSegment): Boolean {
        if (!active || pending.isEmpty() || segment.text.isBlank()) return false
        pending.add(pending.size - 1, segment)
        lateJoinWaitMs = 0L
        // It arrived during the hold: speak it now instead of waiting the hold out.
        waitingPlay?.let { play -> waitingPlay = null; turn++; play() }
        return true
    }

    /** One segment ended. Either pause and continue, or complete the turn. */
    fun segmentDone(playNext: () -> Unit) {
        if (!active) return
        if (pending.isEmpty()) { complete(); return }
        val token = ++turn
        val hold = pending.size == 1 && lateJoinWaitMs > pauseMs
        if (hold) waitingPlay = playNext
        val wait = if (hold) lateJoinWaitMs else pauseMs
        lateJoinWaitMs = if (hold) 0L else lateJoinWaitMs
        schedule(wait) { if (active && token == turn) { waitingPlay = null; playNext() } }
    }

    /** Ends the turn now. Returns the segments that were never spoken. */
    fun cancel(): List<GuidanceSegment> {
        val dropped = pending.toList()
        pending.clear()
        complete()
        return dropped
    }

    private fun complete() {
        val callback = finished ?: return
        finished = null
        waitingPlay = null
        turn++
        callback()
    }
}

/**
 * One pending message, never a backlog. This uses monotonic milliseconds so wall
 * clock adjustments cannot break cooldowns. The Android controller owns speaking.
 */
class SpeechPacer(
    private val minimumIntervalMs: Long = 4_000,
    private val repeatIntervalMs: Long = 12_000,
) {
    private var pending: CoachingSpeech? = null
    private var lastSpokenAt: Long? = null
    private val spokenKeys = mutableMapOf<String, Long>()

    /** Told about every offered message that will now never be spoken. */
    var onDropped: ((CoachingSpeech) -> Unit)? = null

    val hasPending: Boolean get() = pending != null

    fun offer(message: CoachingSpeech, nowMs: Long): Boolean {
        if (message.text.isBlank() || message.key.isBlank() || expired(message, nowMs)) return false
        if (spokenKeys[message.key]?.let { nowMs - it < repeatIntervalMs } == true) return false
        val current = pending?.takeUnless { expired(it, nowMs) }
        if (current != null && current.priority > message.priority) {
            onDropped?.invoke(message)
            return false
        }
        pending?.takeIf { it.key != message.key }?.let { onDropped?.invoke(it) }
        pending = message
        return true
    }

    fun takeReady(nowMs: Long, isSpeaking: Boolean): CoachingSpeech? {
        val message = pending ?: return null
        if (expired(message, nowMs)) {
            pending = null
            onDropped?.invoke(message)
            return null
        }
        // A rep turn follows the completed movement, without the correction
        // cooldown. It still shares one speech lane and never overlaps audio.
        val interval = if (message.priority == SpeechPriority.RepFeedback ||
            message.priority == SpeechPriority.Coaching) 0 else minimumIntervalMs
        if (isSpeaking || lastSpokenAt?.let { nowMs - it < interval } == true) return null
        if (spokenKeys[message.key]?.let { nowMs - it < repeatIntervalMs } == true) {
            pending = null
            return null
        }
        pending = null
        return message
    }

    /** Record only speech accepted by the engine; failed focus requests do not count. */
    fun markSpoken(message: CoachingSpeech, nowMs: Long) {
        lastSpokenAt = nowMs
        spokenKeys[message.key] = nowMs
        spokenKeys.entries.removeAll { nowMs - it.value >= repeatIntervalMs }
    }

    /** A transient focus interruption may retry the still-current sentence once focus returns. */
    fun restoreInterrupted(message: CoachingSpeech, nowMs: Long) {
        if (expired(message, nowMs)) return
        spokenKeys.remove(message.key)
        lastSpokenAt = null
        // An interrupted count must never replace the newer completed rep.
        if (pending == null || pending!!.priority < message.priority ||
            pending!!.priority == message.priority && pending!!.createdAtMs <= message.createdAtMs) pending = message
    }

    fun clearPending() {
        val dropped = pending
        pending = null
        dropped?.let { onDropped?.invoke(it) }
    }

    /** Clear duplicate history when a new session starts, preserving minimum pacing. */
    fun resetSession() {
        clearPending()
        spokenKeys.clear()
    }

    private fun expired(message: CoachingSpeech, nowMs: Long): Boolean =
        message.expiresInMs <= 0 || nowMs < message.createdAtMs ||
            nowMs - message.createdAtMs >= message.expiresInMs
}
