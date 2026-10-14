package com.volleyballlab.player.ui.coaching

/** Larger priorities replace stale lower-priority feedback before it is spoken. */
enum class SpeechPriority {
    Improvement, Milestone, Correction, Tracking, Rotation,
}

data class CoachingSpeech(
    val text: String,
    val key: String,
    val priority: SpeechPriority,
    val createdAtMs: Long,
    val expiresInMs: Long = 5_000,
)

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

    val hasPending: Boolean get() = pending != null

    fun offer(message: CoachingSpeech, nowMs: Long): Boolean {
        if (message.text.isBlank() || message.key.isBlank() || expired(message, nowMs)) return false
        if (spokenKeys[message.key]?.let { nowMs - it < repeatIntervalMs } == true) return false
        val current = pending?.takeUnless { expired(it, nowMs) }
        if (current != null && current.priority > message.priority) return false
        pending = message
        return true
    }

    fun takeReady(nowMs: Long, isSpeaking: Boolean): CoachingSpeech? {
        val message = pending ?: return null
        if (expired(message, nowMs)) {
            pending = null
            return null
        }
        if (isSpeaking || lastSpokenAt?.let { nowMs - it < minimumIntervalMs } == true) return null
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

    fun clearPending() { pending = null }

    /** Clear duplicate history when a new session starts, preserving minimum pacing. */
    fun resetSession() {
        clearPending()
        spokenKeys.clear()
    }

    private fun expired(message: CoachingSpeech, nowMs: Long): Boolean =
        message.expiresInMs <= 0 || nowMs < message.createdAtMs ||
            nowMs - message.createdAtMs >= message.expiresInMs
}
