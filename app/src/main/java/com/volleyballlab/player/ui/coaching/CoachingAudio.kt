package com.volleyballlab.player.ui.coaching

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.volleyballlab.player.domain.repository.CoachingPreferences
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CoachingAudioStatus { Initializing, Ready, Unavailable }

/**
 * Screen-owned speech. Attach to the screen lifecycle, then close on disposal.
 * Camera images and cues never leave the app: only an installed offline English
 * voice is used. An unavailable voice leaves all visual coaching working.
 */
class CoachingAudio(
    context: Context,
    private val onValidRepHaptic: () -> Unit = {},
) : DefaultLifecycleObserver, AutoCloseable {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val pacer = SpeechPacer()
    private val _status = MutableStateFlow(CoachingAudioStatus.Initializing)
    val status: StateFlow<CoachingAudioStatus> = _status.asStateFlow()

    private var engine: TextToSpeech? = null
    private var preferences = CoachingPreferences()
    private var foreground = false
    private var sessionActive = false
    private var muted = false
    private var closed = false
    private var currentUtterance: String? = null
    private var sequence = 0L
    private var hasAudioFocus = false
    private var engineGeneration = 0L

    private val audioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(audioAttributes)
        .setWillPauseWhenDucked(true)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ change ->
            if (change < AudioManager.AUDIOFOCUS_GAIN) stop()
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
        if (active && !sessionActive) pacer.resetSession()
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
    ) = onMain {
        if (!canSpeak()) return@onMain
        val now = SystemClock.elapsedRealtime()
        if (pacer.offer(CoachingSpeech(text, key, priority, now, expiresInMs), now)) pump()
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
        stopNow()
        engine?.shutdown()
        engine = null
        _status.value = CoachingAudioStatus.Initializing
        val generation = ++engineGeneration
        try {
            engine = TextToSpeech(appContext) { result ->
                main.post {
                    if (!closed && generation == engineGeneration) {
                        try {
                            configureVoice(result)
                        } catch (_: RuntimeException) {
                            _status.value = CoachingAudioStatus.Unavailable
                            stopNow()
                        }
                    }
                }
            }
        } catch (_: RuntimeException) {
            _status.value = CoachingAudioStatus.Unavailable
        }
    }

    private fun configureVoice(result: Int) {
        if (closed) return
        val tts = engine ?: return
        if (result != TextToSpeech.SUCCESS) {
            _status.value = CoachingAudioStatus.Unavailable
            return
        }
        val offlineEnglish = tts.voices.orEmpty()
            .filter {
                it.locale.language == Locale.ENGLISH.language && !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
            }
            .sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.latency })
            .firstOrNull { tts.setVoice(it) == TextToSpeech.SUCCESS }
        if (offlineEnglish == null) {
            _status.value = CoachingAudioStatus.Unavailable
            return
        }
        tts.setAudioAttributes(audioAttributes)
        tts.setSpeechRate(0.98f)
        tts.setPitch(1f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { main.post { utteranceFinished(utteranceId) } }
            @Deprecated("Android requires the legacy error callback")
            override fun onError(utteranceId: String?) { main.post { utteranceFailed(utteranceId) } }
            override fun onError(utteranceId: String?, errorCode: Int) {
                main.post { utteranceFailed(utteranceId) }
            }
        })
        _status.value = CoachingAudioStatus.Ready
    }

    private fun canSpeak(): Boolean = !closed && foreground && sessionActive &&
        !muted && preferences.voiceEnabled && _status.value == CoachingAudioStatus.Ready

    private fun pump() {
        main.removeCallbacks(pumpRunnable)
        if (!canSpeak()) {
            pacer.clearPending()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val message = pacer.takeReady(now, isSpeaking = currentUtterance != null)
        if (message == null) {
            if (pacer.hasPending && currentUtterance == null) main.postDelayed(pumpRunnable, 200)
            return
        }
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        hasAudioFocus = true
        val id = "live-coach-${++sequence}"
        currentUtterance = id
        val result = engine?.speak(message.text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result == TextToSpeech.SUCCESS) {
            pacer.markSpoken(message, now)
        } else {
            utteranceFailed(id)
        }
    }

    private fun utteranceFinished(id: String?) {
        if (id != currentUtterance) return
        currentUtterance = null
        abandonFocus()
        pump()
    }

    private fun utteranceFailed(id: String?) {
        if (id != currentUtterance) return
        _status.value = CoachingAudioStatus.Unavailable
        stopNow()
    }

    private fun stopNow() {
        main.removeCallbacks(pumpRunnable)
        pacer.clearPending()
        currentUtterance = null
        engine?.stop()
        abandonFocus()
    }

    private fun abandonFocus() {
        if (hasAudioFocus) audioManager.abandonAudioFocusRequest(focusRequest)
        hasAudioFocus = false
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    companion object {
        /** Guard startActivity with resolveActivity or catch ActivityNotFoundException. */
        fun settingsIntent(): Intent = Intent("com.android.settings.TTS_SETTINGS")
    }
}
