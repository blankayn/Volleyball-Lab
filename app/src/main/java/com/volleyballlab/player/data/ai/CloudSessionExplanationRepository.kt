package com.volleyballlab.player.data.ai

import com.google.firebase.functions.FirebaseFunctions
import com.volleyballlab.player.data.cloud.awaitCloud
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

data class SessionExplanationPoint(val label: String, val explanation: String, val measuredReps: Int, val score: Int?, val tip: String)
data class SessionExplanationResult(
    val status: String,
    val overview: String = "",
    val strengths: List<SessionExplanationPoint> = emptyList(),
    val priorities: List<SessionExplanationPoint> = emptyList(),
    val limitedCueCount: Int = 0,
)

/** Decodes a server-owned explanation. Numeric feedback is displayed separately from prose. */
internal object SessionExplanationDecoder {
    fun decode(value: Any?): SessionExplanationResult {
        val result = requireNotNull(value as? Map<*, *>)
        val status = result["status"] as? String
        require(status in setOf("ready", "empty", "insufficient"))
        val evidence = requireNotNull(result["evidence"] as? Map<*, *>)
        val limited = (evidence["limitedCues"] as? List<*>)?.size ?: 0
        if (status != "ready") return SessionExplanationResult(requireNotNull(status), limitedCueCount = limited)
        val cues = (evidence["cues"] as? List<*>)?.mapNotNull { it as? Map<*, *> }?.associateBy { it["id"] as? String } ?: emptyMap()
        val explanation = requireNotNull(result["explanation"] as? Map<*, *>)
        fun prose(value: Any?, max: Int): String {
            val text = requireNotNull(value as? String).trim()
            require(text.isNotEmpty() && text.length <= max && text.none(Char::isDigit))
            return text
        }
        fun points(key: String): List<SessionExplanationPoint> {
            val entries = requireNotNull(explanation[key] as? List<*>)
            require(entries.size <= 2)
            return entries.map { item ->
                val row = requireNotNull(item as? Map<*, *>)
                val cue = requireNotNull(cues[row["cueId"]])
                val count = (cue["measuredReps"] as? Number)?.toInt() ?: 0
                require(count in 3..1000)
                val score = (cue["score"] as? Number)?.toInt()
                require(score == null || score in 0..100)
                SessionExplanationPoint(requireNotNull(cue["label"] as? String).take(80), prose(row["explanation"], 280), count, score, requireNotNull(cue["tip"] as? String).take(240))
            }
        }
        return SessionExplanationResult("ready", prose(explanation["overview"], 400), points("strengths"), points("priorities"), limited)
    }
}

class CloudSessionExplanationRepository {
    suspend fun load(sessionId: String, generate: Boolean): SessionExplanationResult = withTimeout(65_000L) {
        val callable = FirebaseFunctions.getInstance("asia-southeast1").getHttpsCallable("explainTrainingSession")
        callable.setTimeout(65, TimeUnit.SECONDS)
        SessionExplanationDecoder.decode(callable.call(mapOf("sessionId" to sessionId, "generate" to generate)).awaitCloud().data)
    }
}
