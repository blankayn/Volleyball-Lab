package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.ExperienceLevel

/**
 * One instruction in two lengths: the full sentence that teaches it, and the
 * short cue that reminds an athlete who has already been taught.
 */
data class SpokenCue(val full: String, val short: String)

/**
 * Progressive instruction: teach once in full, then use the short cue.
 *
 * An athlete three metres from the phone, mid-drill, takes in a short cue far
 * more easily than a sentence - but only once they know what it refers to.
 * "Turn left" means nothing until they have heard "Turn to your left, so your
 * right shoulder points at the camera". So each instruction is spoken in full
 * until it has been heard, and after that as its short cue.
 *
 * Only speech is shortened. The card keeps the full sentence, so a glance at
 * the screen always gives the whole instruction.
 *
 * A new athlete hears the full version twice before it shortens. Anyone who
 * has not heard an instruction for [relearnAfterMs] hears it in full again,
 * because a cue learned two minutes ago is not reliably remembered mid-drill.
 *
 * Not thread-safe; driven from the capture screen's main-thread collector.
 */
class ProgressiveInstructions(private val relearnAfterMs: Long = 120_000L) {
    private data class Heard(val times: Int, val lastMs: Long)

    private val heard = mutableMapOf<String, Heard>()
    /** What each spoken event changed, so a line the athlete never heard can be taken back. */
    private val pending = LinkedHashMap<Long, Pair<String, Heard?>>()
    private var fullTimes = 1

    /** Starts a session: nothing has been taught yet. */
    fun reset(experience: ExperienceLevel?) {
        heard.clear()
        pending.clear()
        fullTimes = if (experience == ExperienceLevel.New) 2 else 1
    }

    /**
     * The words to speak for [cue] now, and records that they were spoken.
     *
     * @param eventId identifies this utterance, for [dropped].
     */
    fun speak(cue: SpokenCue, nowMs: Long, eventId: Long): String {
        val previous = heard[cue.full]
        val fresh = previous != null && nowMs - previous.lastMs < relearnAfterMs
        val times = if (fresh) previous!!.times else 0
        heard[cue.full] = Heard(times + 1, nowMs)
        pending[eventId] = cue.full to previous
        while (pending.size > MAX_PENDING) pending.remove(pending.keys.first())
        return if (times < fullTimes) cue.full else cue.short
    }

    /**
     * The utterance [eventId] was never heard. Forget it, so the full version
     * is still owed rather than skipped straight to a cue they cannot decode.
     */
    fun dropped(eventId: Long) {
        val (key, before) = pending.remove(eventId) ?: return
        if (before == null) heard.remove(key) else heard[key] = before
    }

    private companion object {
        const val MAX_PENDING = 16
    }
}
