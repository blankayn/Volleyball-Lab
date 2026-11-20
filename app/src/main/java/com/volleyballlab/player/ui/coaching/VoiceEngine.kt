package com.volleyballlab.player.ui.coaching

/** Platform boundary. Callbacks and scheduling are serialized on the owner's thread. */
interface VoiceEngine {
    fun initialize(onReady: (Boolean) -> Unit)
    fun speak(text: String, utteranceId: String, flush: Boolean, style: SpeechStyle = SpeechStyle.Guidance): Boolean
    fun stop()
    fun shutdown()
}

/** Bounded initialization/recovery with generation-scoped callbacks. No Android dependency. */
class ReliableVoiceEngine(
    private val factory: ((String) -> Unit, (String) -> Unit) -> VoiceEngine,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val state: (CoachingAudioStatus) -> Unit,
    private val finished: (String) -> Unit,
) : VoiceEngine {
    private var engine: VoiceEngine? = null
    private var generation = 0L
    private var ready = false
    private var closed = false
    private var initAttempt = 0
    private var callback: ((Boolean) -> Unit)? = null
    private data class Speech(val text: String, val id: String, val style: SpeechStyle, val attempt: Int = 0)
    private var active: Speech? = null

    override fun initialize(onReady: (Boolean) -> Unit) {
        closed = false
        callback = onReady
        initAttempt = 0
        start()
    }

    private fun start() {
        if (closed) return
        ready = false
        state(CoachingAudioStatus.Initializing)
        engine?.shutdown()
        val token = ++generation
        var resolved = false
        fun resolve(ok: Boolean) {
            if (closed || token != generation || resolved) return
            resolved = true
            if (!ok && initAttempt++ == 0) { start(); return }
            ready = ok
            state(if (ok) CoachingAudioStatus.Ready else CoachingAudioStatus.Unavailable)
            callback?.invoke(ok)
            if (ok) active?.let(::dispatch) else active?.let { active = null; finished(it.id) }
        }
        try {
            engine = factory({ id ->
                if (token == generation && active?.id == id) {
                    active = null
                    finished(id)
                }
            }, { id -> if (token == generation) fail(id) })
            engine!!.initialize(::resolve)
        } catch (_: RuntimeException) { resolve(false) }
        // Two bounded attempts, six seconds total, including missing callbacks.
        schedule(3_000) { resolve(false) }
    }

    override fun speak(text: String, utteranceId: String, flush: Boolean, style: SpeechStyle): Boolean {
        if (closed) return false
        val speech = Speech(text, utteranceId, style)
        active = speech
        if (ready) dispatch(speech)
        else if (engine == null) start()
        return true
    }

    private fun dispatch(speech: Speech) {
        val accepted = try { engine?.speak(speech.text, speech.id, true, speech.style) == true }
            catch (_: RuntimeException) { false }
        if (!accepted) fail(speech.id)
        // Capture the attempt object: a timeout for A can never finish B or A's retry.
        // Scaled to the text so a long coaching turn is not mistaken for a stalled engine.
        // The floor keeps one retry plus its own watchdog beyond the old fixed bound.
        schedule(maxOf(MIN_WATCHDOG_MS, SpeechTiming.watchdogMs(speech.text))) { if (active === speech) fail(speech.id) }
    }

    private fun fail(id: String) {
        val speech = active?.takeIf { it.id == id } ?: return
        if (speech.attempt == 0) {
            active = speech.copy(attempt = 1)
            ready = false
            engine?.stop()
            val token = ++generation
            state(CoachingAudioStatus.Initializing)
            schedule(300) { if (!closed && token == generation) { initAttempt = 0; start() } }
        } else {
            active = null
            state(CoachingAudioStatus.Degraded)
            finished(id)
        }
    }

    override fun stop() { active = null; engine?.stop() }
    private companion object { const val MIN_WATCHDOG_MS = 8_000L }
    override fun shutdown() {
        closed = true; ready = false; generation++; active = null
        engine?.shutdown(); engine = null
    }
}
