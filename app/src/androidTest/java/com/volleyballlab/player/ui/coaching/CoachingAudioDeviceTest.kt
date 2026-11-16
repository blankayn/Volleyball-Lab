package com.volleyballlab.player.ui.coaching

import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class CoachingAudioDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun repFeedbackUsesOneVoiceLaneAndRespectsMovementAndMute() {
        val spoken = mutableListOf<Pair<String, String>>()
        lateinit var complete: (String) -> Unit
        lateinit var audio: CoachingAudio
        compose.setContent {
            val context = LocalContext.current
            val owner = LocalLifecycleOwner.current
            DisposableEffect(owner) {
                audio = CoachingAudio(context, voiceFactory = { done, _ ->
                    complete = done
                    object : VoiceEngine {
                        override fun initialize(onReady: (Boolean) -> Unit) = onReady(true)
                        override fun speak(text: String, utteranceId: String, flush: Boolean, style: SpeechStyle): Boolean {
                            spoken += text to utteranceId
                            return true
                        }
                        override fun stop() = Unit
                        override fun shutdown() = Unit
                    }
                })
                owner.lifecycle.addObserver(audio)
                audio.setSessionActive(true)
                onDispose { owner.lifecycle.removeObserver(audio); audio.close() }
            }
            Text("Testing personalized rep coaching")
        }
        compose.runOnIdle {
            audio.setCorrectionWindow(false)
            audio.submit("Rep 1. Form target met.", "rep-1", SpeechPriority.RepFeedback)
            assertTrue(spoken.isEmpty())
            audio.setCorrectionWindow(true)
            assertEquals(1, spoken.size)
            audio.submit("Rep 2. Form needs work.", "rep-2", SpeechPriority.RepFeedback)
            assertEquals("A new rep must not interrupt the speaking voice", 1, spoken.size)
            complete(spoken.first().second)
            assertEquals("Rep count must not wait four seconds", 2, spoken.size)
            audio.setMuted(true)
            audio.submit("Rep 3.", "rep-3", SpeechPriority.RepFeedback)
            assertEquals(2, spoken.size)
            audio.setMuted(false)
            audio.submit("Rep 4.", "rep-4", SpeechPriority.RepFeedback)
            assertEquals("Rep 4.", spoken.last().first)
            assertFalse(spoken.any { it.first == "Rep 3." })
        }
    }

    @Test fun queuedFrontCueSpeaksWhenMovementEnds() {
        val spoken = mutableListOf<String>()
        lateinit var audio: CoachingAudio
        compose.setContent {
            val context = LocalContext.current
            val owner = LocalLifecycleOwner.current
            DisposableEffect(owner) {
                audio = CoachingAudio(context, voiceFactory = { _, _ -> object : VoiceEngine {
                    override fun initialize(onReady: (Boolean) -> Unit) = onReady(true)
                    override fun speak(text: String, utteranceId: String, flush: Boolean, style: SpeechStyle): Boolean { spoken += text; return true }
                    override fun stop() = Unit
                    override fun shutdown() = Unit
                } })
                owner.lifecycle.addObserver(audio)
                audio.setSessionActive(true)
                onDispose { owner.lifecycle.removeObserver(audio); audio.close() }
            }
            Text("Testing coach speech")
        }
        compose.runOnIdle {
            audio.setCorrectionWindow(false)
            audio.submit("Extend your arms", "front-cue")
            assertTrue(spoken.isEmpty())
            audio.setCorrectionWindow(false)
            audio.setCorrectionWindow(true)
        }
        compose.waitUntil(3000) { spoken.isNotEmpty() }
        compose.runOnIdle { assertEquals(listOf("Extend your arms"), spoken) }
    }
    @Test fun installedEngineCompletesFrontAndSideSpeech() {
        val completed = java.util.concurrent.atomic.AtomicInteger()
        lateinit var audio: CoachingAudio
        compose.setContent {
            val context = LocalContext.current
            val owner = LocalLifecycleOwner.current
            DisposableEffect(owner) {
                audio = CoachingAudio(context, voiceFactory = { done, failed ->
                    AndroidVoiceEngine(context, android.os.Handler(android.os.Looper.getMainLooper()),
                        android.media.AudioAttributes.Builder()
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build(),
                        { id -> completed.incrementAndGet(); done(id) }, failed)
                })
                owner.lifecycle.addObserver(audio)
                audio.setSessionActive(true)
                onDispose { owner.lifecycle.removeObserver(audio); audio.close() }
            }
            Text("Checking Front and Side voice playback")
        }
        compose.waitUntil(10000) { audio.status.value == CoachingAudioStatus.Ready }
        compose.runOnIdle { audio.playGuidance("Front view voice check. Keep your hands above your forehead.") }
        compose.waitUntil(15000) { completed.get() == 1 }
        compose.runOnIdle {
            audio.stop()
            audio.playGuidance("Side view voice check. Reset your stance before the next movement.")
        }
        compose.waitUntil(15000) { completed.get() == 2 }
    }
}
