package com.volleyballlab.player.ui.coaching

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.volleyballlab.player.domain.repository.CoachingPreferences
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CoachingAudioStatus { Initializing, Ready, Degraded, Unavailable }

/**
 * Screen-owned speech. Attach to the screen lifecycle, then close on disposal.
 * Short cues use installed phone speech. At a break, a fully buffered optional
 * coach WAV and its next-view instruction share the same audio focus and lane.
 */
class CoachingAudio(
    context: Context,
    private val voiceFactory: (((String) -> Unit, (String) -> Unit) -> VoiceEngine)? = null,
    private val onValidRepHaptic: () -> Unit = {},
    /** A submitted line that will never be spoken, by the key it was submitted with. */
    private val onDropped: (key: String) -> Unit = {},
) : DefaultLifecycleObserver, AutoCloseable {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val pacer = SpeechPacer().also { it.onDropped = { message -> onDropped(message.key) } }
    private val _status = MutableStateFlow(CoachingAudioStatus.Initializing)
    val status: StateFlow<CoachingAudioStatus> = _status.asStateFlow()

    private var engine: VoiceEngine? = null
    private var preferences = CoachingPreferences()
    private var foreground = false
    private var sessionActive = false
    private var muted = false
    private var closed = false
    private var currentUtterance: String? = null
    private var sequence = 0L
    private var hasAudioFocus = false
    private val guidance = GuidanceQueue({ delay, action -> main.postDelayed({ action() }, delay) })
    private var lastCorrection: String? = null
    private var lastGuidance: List<String> = emptyList()
    private val focusLinger = Runnable {
        if (currentUtterance == null && !pacer.hasPending && !guidance.active) abandonFocus()
    }
    private var player: MediaPlayer? = null
    private var playingFile: String? = null
    private val guidanceNext = Runnable { playNextGuidance() }
    private var speechWatchdog: Runnable? = null
    private var focusWaitStarted: Long? = null
    private var correctionWindow = true
    private var activeMessage: CoachingSpeech? = null
    private var suspendedMessage: CoachingSpeech? = null

    private val audioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(audioAttributes)
        .setWillPauseWhenDucked(true)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    hasAudioFocus = true
                    suspendedMessage?.let { pacer.restoreInterrupted(it, SystemClock.elapsedRealtime()) }
                    suspendedMessage = null
                    if (guidance.active) playNextGuidance() else pump()
                }
                AudioManager.AUDIOFOCUS_LOSS -> stopNow()
                else -> {
                    hasAudioFocus = false
                    engine?.stop()
                    speechWatchdog?.let(main::removeCallbacks)
                    suspendedMessage = activeMessage
                    activeMessage = null
                    currentUtterance = null
                    // Completed guidance segments stay consumed. The next segment resumes on focus gain.
                    if (!guidance.active) main.postDelayed(pumpRunnable, 250) else main.postDelayed(guidanceNext, 250)
                }
            }
        }, main)
        .build()
    private val pumpRunnable = Runnable { pump() }

    init {
        initializeEngine()
    }

    override fun onResume(owner: LifecycleOwner) = onMain {
        foreground = true
        // Return from installing voice data in system settings without requiring
        // the athlete to leave and reopen the capture screen.
        if (_status.value == CoachingAudioStatus.Unavailable) initializeEngine()
    }

    override fun onPause(owner: LifecycleOwner) = onMain {
        foreground = false
        stopNow()
    }

    override fun onDestroy(owner: LifecycleOwner) { close() }

    fun setSessionActive(active: Boolean) = onMain {
        if (active && !sessionActive) { pacer.resetSession(); lastCorrection = null; lastGuidance = emptyList() }
        sessionActive = active
        if (!active) stopNow()
    }

    fun setPreferences(value: CoachingPreferences) = onMain {
        preferences = value
        if (!value.voiceEnabled) stopNow()
    }

    fun setMuted(value: Boolean) = onMain {
        muted = value
        if (value) stopNow()
    }

    fun submit(
        text: String,
        key: String,
        priority: SpeechPriority = SpeechPriority.Correction,
        expiresInMs: Long = 5_000,
        style: SpeechStyle = SpeechStyle.Guidance,
    ) = onMain {
        if (!canPlay() || guidance.active) { onDropped(key); return@onMain }
        if (_status.value == CoachingAudioStatus.Degraded) initializeEngine()
        val now = SystemClock.elapsedRealtime()
        if (pacer.offer(CoachingSpeech(text, key, priority, now, expiresInMs, style), now)) pump()
    }

    fun setCorrectionWindow(open: Boolean) = onMain {
        correctionWindow = open
        // Gate the start of a cue, not every syllable of one already speaking.
        // Keep one expiring pending cue while moving, then actively wake it.
        // Angle changes, tracking loss, mute and background still call stop().
        if (open) pump()
    }

    /** One complete coaching turn; completion also fires for mute/focus/error. */
    fun playGuidance(text: String, voicePath: String? = null, instruction: String = "", onFinished: () -> Unit = {}) =
        playSegments(listOf(GuidanceSegment(text, SpeechStyle.Guidance, voicePath), GuidanceSegment(instruction)), onFinished)

    /** A multi-sentence turn: one segment per sentence, with a short pause between them. */
    fun playGuidance(
        segments: List<String>,
        style: SpeechStyle = SpeechStyle.Guidance,
        instruction: String = "",
        lateJoinWaitMs: Long = 0L,
        onFinished: () -> Unit = {},
    ) = playSegments(segments.map { GuidanceSegment(it, style) } + GuidanceSegment(instruction), onFinished, lateJoinWaitMs)

    private fun playSegments(segments: List<GuidanceSegment>, onFinished: () -> Unit, lateJoinWaitMs: Long = 0L) = onMain {
        stopNow()
        if (!canPlay() || _status.value == CoachingAudioStatus.Unavailable) {
            segments.forEach { it.voicePath?.let { path -> File(path).delete() } }
            onFinished()
            return@onMain
        }
        lastGuidance = segments.map { it.text }.filter { it.isNotBlank() }
        if (guidance.begin(segments, lateJoinWaitMs, onFinished)) playNextGuidance()
    }

    /**
     * Adds one late line to the turn now playing, before its final instruction.
     * Returns false when the turn has already reached that instruction or ended.
     */
    fun appendToGuidance(text: String, style: SpeechStyle = SpeechStyle.Guidance): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        return guidance.insertBeforeLast(GuidanceSegment(text, style))
    }

    /** "Say again": the last correction, else the last guidance turn. Never interrupts speech. */
    fun repeatLast() = onMain {
        val text = lastCorrection ?: lastGuidance.joinToString(" ").takeIf { it.isNotBlank() } ?: return@onMain
        if (!canSpeak() || guidance.active || currentUtterance != null) return@onMain
        if (guidance.begin(listOf(GuidanceSegment(text, SpeechStyle.Correction))) {}) playNextGuidance()
    }

    /** Drop what is waiting but let the sentence already being spoken finish. */
    fun stopAfterCurrent() = onMain {
        main.removeCallbacks(pumpRunnable)
        pacer.clearPending()
    }

    /** Countdown has its own rhythm, outside the correction cooldown. */
    fun announceCountdown(seconds: Int) = onMain {
        if (!canSpeak()) return@onMain
        stopNow()
        if (!requestFocus()) return@onMain
        val id = "countdown-${++sequence}"
        currentUtterance = id
        if (engine?.speak(listOf("", "one", "two", "three").getOrElse(seconds) { seconds.toString() }, id, true, SpeechStyle.Count) != true) utteranceFailed(id)
        else watch(id, 1_500)
    }

    /** The caller consumes a completed-rep event once, rather than each redraw. */
    fun feedbackValidRep() = onMain {
        if (!closed && foreground && sessionActive && preferences.hapticsEnabled) onValidRepHaptic()
    }

    /** Drop outdated corrections at an angle transition or tracking interruption. */
    fun stop() = onMain { stopNow() }

    override fun close() = onMain {
        if (closed) return@onMain
        closed = true
        stopNow()
        engine?.shutdown()
        engine = null
    }

    private fun initializeEngine() {
        if (closed) return
        engine?.shutdown()
        engine = ReliableVoiceEngine(
            factory = voiceFactory ?: { done, failed -> AndroidVoiceEngine(appContext, main, audioAttributes, done, failed) },
            schedule = { delay, action -> main.postDelayed({ action() }, delay) },
            state = { value ->
                _status.value = value
                if (value == CoachingAudioStatus.Ready) main.post { pump() }
            },
            finished = ::utteranceFinished,
        ).also { it.initialize {} }
    }

    private fun watch(id: String, delay: Long) {
        speechWatchdog?.let(main::removeCallbacks)
        speechWatchdog = Runnable { if (currentUtterance == id) utteranceFinished(id) }
            .also { main.postDelayed(it, delay) }
    }

    private fun canPlay(): Boolean = !closed && foreground && sessionActive && !muted && preferences.voiceEnabled
    private fun canSpeak(): Boolean = canPlay() && _status.value == CoachingAudioStatus.Ready

    private fun requestFocus(): Boolean {
        if (hasAudioFocus) return true
        hasAudioFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun playNextGuidance() {
        if (!canPlay() || !guidance.active || !guidance.hasNext) { stopNow(); return }
        if (!requestFocus()) {
            val now = SystemClock.elapsedRealtime()
            if (focusWaitStarted == null) focusWaitStarted = now
            if (now - requireNotNull(focusWaitStarted) < 3_000) main.postDelayed(guidanceNext, 250) else stopNow()
            return
        }
        focusWaitStarted = null
        main.removeCallbacks(focusLinger)
        val segment = guidance.next() ?: run { stopNow(); return }
        val id = "coach-break-${++sequence}"
        currentUtterance = id
        // A missing completion callback must never trap an athlete in a break.
        speechWatchdog?.let(main::removeCallbacks)
        watch(id, if (segment.voicePath != null) 30_000 else SpeechTiming.watchdogMs(segment.text) + 2_000)
        val path = segment.voicePath?.takeIf { File(it).isFile }
        if (path != null) {
            try {
                playingFile = path
                player = MediaPlayer().apply {
                    setAudioAttributes(audioAttributes)
                    setDataSource(path)
                    setOnPreparedListener { if (currentUtterance == id && canPlay()) it.start() }
                    setOnCompletionListener { utteranceFinished(id) }
                    setOnErrorListener { _, _, _ ->
                        if (currentUtterance == id) { releasePlayer(); speakGuidanceSegment(segment, id) }
                        true
                    }
                    prepareAsync()
                }
                return
            } catch (_: Exception) { releasePlayer() }
        }
        speakGuidanceSegment(segment, id)
    }

    private fun speakGuidanceSegment(segment: GuidanceSegment, id: String) {
        if (!canPlay() || engine?.speak(segment.text, id, true, segment.style) != true) utteranceFinished(id)
    }

    private fun pump() {
        main.removeCallbacks(pumpRunnable)
        if (!canPlay()) { pacer.clearPending(); return }
        if (!canSpeak() || guidance.active || !correctionWindow) return
        // Never dequeue before focus is granted. Pending speech expires naturally.
        if (!requestFocus()) {
            val now = SystemClock.elapsedRealtime()
            if (focusWaitStarted == null) focusWaitStarted = now
            if (now - requireNotNull(focusWaitStarted) < 3_000) main.postDelayed(pumpRunnable, 250)
            return
        }
        focusWaitStarted = null
        val now = SystemClock.elapsedRealtime()
        val message = pacer.takeReady(now, isSpeaking = currentUtterance != null)
        if (message == null) {
            if (!pacer.hasPending && currentUtterance == null) releaseFocusSoon()
            if (pacer.hasPending && currentUtterance == null) main.postDelayed(pumpRunnable, 200)
            return
        }
        if (!requestFocus()) return
        main.removeCallbacks(focusLinger)
        val id = "live-coach-${++sequence}"
        currentUtterance = id
        activeMessage = message
        val result = engine?.speak(message.text, id, true, message.style)
        if (result == true) {
            pacer.markSpoken(message, now)
            if (message.style == SpeechStyle.Correction) lastCorrection = message.text
            watch(id, SpeechTiming.watchdogMs(message.text) + 2_000)
        } else {
            utteranceFailed(id)
        }
    }

    private fun utteranceFinished(id: String?) {
        if (id == null || id != currentUtterance) return
        engine?.stop()
        speechWatchdog?.let(main::removeCallbacks)
        releasePlayer()
        currentUtterance = null
        activeMessage = null
        if (guidance.active) {
            if (!guidance.hasNext) stopNow() else guidance.segmentDone { playNextGuidance() }
            return
        }
        // Hold focus briefly so back-to-back rep speech does not duck music on every count.
        releaseFocusSoon()
        pump()
    }

    private fun utteranceFailed(id: String?) {
        if (id == null || id != currentUtterance) return
        _status.value = CoachingAudioStatus.Degraded
        utteranceFinished(id)
    }

    private fun stopNow() {
        main.removeCallbacks(pumpRunnable)
        main.removeCallbacks(guidanceNext)
        speechWatchdog?.let(main::removeCallbacks)
        pacer.clearPending()
        activeMessage = null; suspendedMessage = null; focusWaitStarted = null
        currentUtterance = null
        releasePlayer()
        main.removeCallbacks(focusLinger)
        engine?.stop()
        abandonFocus()
        // Completion always fires: finished, muted, focus lost, error or timeout.
        guidance.cancel().forEach { it.voicePath?.let { path -> File(path).delete() } }
    }

    private fun releaseFocusSoon() {
        main.removeCallbacks(focusLinger)
        if (sessionActive) main.postDelayed(focusLinger, FOCUS_LINGER_MS) else abandonFocus()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        playingFile?.let { File(it).delete() }
        playingFile = null
    }

    private fun abandonFocus() {
        if (hasAudioFocus) audioManager.abandonAudioFocusRequest(focusRequest)
        hasAudioFocus = false
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    companion object {
        private const val FOCUS_LINGER_MS = 2_000L

        /** Guard startActivity with resolveActivity or catch ActivityNotFoundException. */
        fun settingsIntent(): Intent = Intent("com.android.settings.TTS_SETTINGS")
    }
}
