package com.volleyballlab.player.data.ai

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.volleyballlab.player.domain.model.AiCoachMessage
import com.volleyballlab.player.domain.model.AiCoachRequest
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.repository.AiCoachRepository
import kotlinx.coroutines.withTimeout

class FirebaseAiCoachRepository(private val context: Context) : AiCoachRepository {

    private val model by lazy {
        val responseSchema = Schema.obj(
            mapOf(
                "focusCueId" to Schema.string(),
                "headline" to Schema.string(),
                "explanation" to Schema.string(),
                "nextAction" to Schema.string(),
            ),
        )
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                temperature = 0.1f
                maxOutputTokens = 220
                responseMimeType = "application/json"
                this.responseSchema = responseSchema
            },
            systemInstruction = content { text(CoachAiContract.SYSTEM_INSTRUCTION) },
        )
    }

    override suspend fun generate(request: AiCoachRequest): AiCoachMessage? {
        val cue = FormCueCatalog.byId(request.focusCueId) ?: return null
        val prompt = CoachAiContract.prompt(
            request = request,
            cueTitle = context.getString(cue.label),
            approvedTip = context.getString(cue.coachingTip),
        )
        val response = withTimeout(REQUEST_TIMEOUT_MS) { model.generateContent(prompt) }
        return response.text?.let { CoachAiContract.parseAndValidate(it, request) }
    }

    private companion object {
        const val MODEL_NAME = "gemini-3.8-flash"
        const val REQUEST_TIMEOUT_MS = 12_000L
    }
}
