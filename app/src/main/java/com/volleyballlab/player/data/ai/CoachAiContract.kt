package com.volleyballlab.player.data.ai

import com.volleyballlab.player.domain.model.AiCoachMessage
import com.volleyballlab.player.domain.model.AiCoachRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Keeps Gemini in a writing role. Pose scoring and cue selection happen locally,
 * and any response that changes those facts is discarded.
 */
internal object CoachAiContract {
    const val SYSTEM_INSTRUCTION = """
        You write one concise volleyball coaching note from verified measurements.
        The app's pose engine is the only authority for scores and technique faults.
        Keep focusCueId exactly equal to the supplied focus cue.
        Use only the supplied approved coaching tip as technique guidance.
        Do not invent measurements, angles, percentages, diagnoses, pain, injury,
        medical advice, or facts about the athlete. Do not put digits in prose.
        Return only the JSON object required by the response schema.
    """

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun prompt(
        request: AiCoachRequest,
        cueTitle: String,
        approvedTip: String,
    ): String = """
        Write a supportive, direct note using only this trusted evidence:
        focusCueId=${request.focusCueId.name}
        skill=${request.skill.name}
        scoreOutOfHundred=${request.scoreOutOfHundred}
        measuredReps=${request.measuredReps}
        sessionCount=${request.sessionCount}
        unmeasuredPercent=${request.unmeasuredPercent}
        cueTitle=$cueTitle
        approvedCoachingTip=$approvedTip

        headline: at most $MAX_HEADLINE characters.
        explanation: at most $MAX_EXPLANATION characters; explain why the approved tip helps.
        nextAction: at most $MAX_ACTION characters; one action copied or paraphrased from the approved tip.
        Keep all prose free of digits because the app displays verified numbers separately.
    """.trimIndent()

    fun parseAndValidate(raw: String, request: AiCoachRequest): AiCoachMessage? {
        val decoded = runCatching { json.decodeFromString<WireMessage>(raw.trim()) }.getOrNull()
            ?: return null
        if (decoded.focusCueId != request.focusCueId.name) return null

        val headline = decoded.headline.normalized(MAX_HEADLINE) ?: return null
        val explanation = decoded.explanation.normalized(MAX_EXPLANATION) ?: return null
        val nextAction = decoded.nextAction.normalized(MAX_ACTION) ?: return null
        val prose = listOf(headline, explanation, nextAction)
        if (prose.any { value -> value.any(Char::isDigit) }) return null
        if (prose.any { value -> UNSAFE_TERMS.any { it in value.lowercase() } }) return null

        return AiCoachMessage(
            headline = headline,
            explanation = explanation,
            nextAction = nextAction,
        )
    }

    private fun String.normalized(limit: Int): String? {
        val value = trim().replace(Regex("\\s+"), " ")
        return value.takeIf { it.isNotEmpty() && it.length <= limit }
    }

    const val MAX_HEADLINE = 60
    const val MAX_EXPLANATION = 180
    const val MAX_ACTION = 120

    private val UNSAFE_TERMS = listOf(
        "diagnos",
        "doctor",
        "injury",
        "medical",
        "pain",
    )
}

@Serializable
private data class WireMessage(
    val focusCueId: String,
    val headline: String,
    val explanation: String,
    val nextAction: String,
)
