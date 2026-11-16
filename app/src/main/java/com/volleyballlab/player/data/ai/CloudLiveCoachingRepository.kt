package com.volleyballlab.player.data.ai

import android.content.Context
import android.util.Base64
import com.google.firebase.functions.FirebaseFunctions
import com.volleyballlab.player.BuildConfig
import com.volleyballlab.player.data.cloud.awaitCloud
import com.volleyballlab.player.pose.analysis.LiveCoachingAdvice
import com.volleyballlab.player.pose.analysis.LiveCoachingEvidence
import com.volleyballlab.player.pose.analysis.LiveCoachingTurnContext
import com.volleyballlab.player.pose.analysis.LiveCoachingWire
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

interface LiveCoachingRepository {
    suspend fun advise(
        evidence: LiveCoachingEvidence,
        voice: Boolean,
        context: LiveCoachingTurnContext? = null,
    ): LiveCoachingAdvice?
    suspend fun previewVoice(): String?
}

/** Authenticated bounded summaries; no camera frames, videos, or API keys. */
class CloudLiveCoachingRepository(context: Context) : LiveCoachingRepository {
    private val cache = File(context.applicationContext.cacheDir, "live-coach-voice")

    override suspend fun advise(
        evidence: LiveCoachingEvidence,
        voice: Boolean,
        context: LiveCoachingTurnContext?,
    ): LiveCoachingAdvice? {
        val request = LiveCoachingWire.request(evidence, voice, context) ?: return null
        val result = call("coachLiveStage", request)
        val advice = LiveCoachingWire.decode(result, evidence, CoachAiContract::isPostureOnly) ?: return null
        return if (voice) advice.copy(voicePath = saveVoice(result["speechBase64"])) else advice
    }

    override suspend fun previewVoice(): String? = saveVoice(call("coachVoicePreview", emptyMap())["speechBase64"])

    private suspend fun call(path: String, data: Map<String, Any?>): Map<*, *> = withTimeout(8_000) {
        val base = BuildConfig.SESSION_AI_URL.takeIf(String::isNotBlank) ?: error("Live coaching is not configured")
        val endpoint = URI(base).resolve("/$path").toURL()
        val callable = FirebaseFunctions.getInstance("asia-southeast1").getHttpsCallableFromUrl(endpoint)
        callable.setTimeout(8, TimeUnit.SECONDS)
        requireNotNull(callable.call(data).awaitCloud().data as? Map<*, *>)
    }

    private suspend fun saveVoice(value: Any?): String? = withContext(Dispatchers.IO) {
        val encoded = value as? String ?: return@withContext null
        if (encoded.length !in 60..2_000_000) return@withContext null
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return@withContext null
        if (bytes.size !in 44..1_500_000 || bytes.copyOfRange(0, 4).decodeToString() != "RIFF" ||
            bytes.copyOfRange(8, 12).decodeToString() != "WAVE") return@withContext null
        cache.mkdirs()
        // Only temporary speech is removed. Retained training recordings are separate.
        cache.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 3_600_000 }?.forEach(File::delete)
        File.createTempFile("coach-", ".wav", cache).apply { writeBytes(bytes) }.absolutePath
    }
}
