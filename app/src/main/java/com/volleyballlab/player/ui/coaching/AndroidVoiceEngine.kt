package com.volleyballlab.player.ui.coaching

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

internal class AndroidVoiceEngine(
    private val context: Context,
    private val main: Handler,
    private val attributes: AudioAttributes,
    private val done: (String) -> Unit,
    private val failed: (String) -> Unit,
) : VoiceEngine {
    private var tts: TextToSpeech? = null
    override fun initialize(onReady: (Boolean) -> Unit) {
        tts = TextToSpeech(context) { result -> main.post {
            val voice = tts ?: return@post
            try {
                if (result != TextToSpeech.SUCCESS || voice.setLanguage(Locale.US) < 0) {
                    onReady(false); return@post
                }
                // The installed default is valid even when voice enumeration is empty.
                runCatching { voice.voices.orEmpty().filter {
                    it.locale.language == "en" && !it.isNetworkConnectionRequired &&
                        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
                // US English first, then quality, then name: the same coach on every
                // phone and after every voice-data update.
                }.sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale == Locale.US }
                    .thenByDescending { it.quality }.thenBy { it.name }).firstOrNull { runCatching { voice.setVoice(it) == TextToSpeech.SUCCESS }.getOrDefault(false) } }
                voice.setAudioAttributes(attributes)
                voice.setSpeechRate(0.98f)
                voice.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) { utteranceId?.let { main.post { done(it) } } }
                    @Deprecated("Platform callback")
                    override fun onError(utteranceId: String?) { utteranceId?.let { main.post { failed(it) } } }
                    override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let { main.post { failed(it) } } }
                })
                onReady(true)
            } catch (_: RuntimeException) { onReady(false) }
        } }
    }
    override fun speak(text: String, utteranceId: String, flush: Boolean, style: SpeechStyle): Boolean {
        val voice = tts ?: return false
        // Starting values, to be tuned by ear on a real phone.
        val (rate, pitch) = when (style) {
            SpeechStyle.Count -> 1.08f to 1.00f
            SpeechStyle.Praise -> 1.02f to 1.06f
            SpeechStyle.Correction -> 0.92f to 0.97f
            SpeechStyle.Guidance -> 0.96f to 1.00f
            SpeechStyle.Urgent -> 1.05f to 1.00f
        }
        voice.setSpeechRate(rate)
        voice.setPitch(pitch)
        return voice.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, utteranceId) == TextToSpeech.SUCCESS
    }
    override fun stop() { tts?.stop() }
    override fun shutdown() { tts?.shutdown(); tts = null }
}
