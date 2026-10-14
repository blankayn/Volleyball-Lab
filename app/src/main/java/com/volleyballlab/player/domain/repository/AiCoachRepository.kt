package com.volleyballlab.player.domain.repository

import com.volleyballlab.player.domain.model.AiCoachMessage
import com.volleyballlab.player.domain.model.AiCoachRequest

fun interface AiCoachRepository {
    /** Returns null when cloud generation or grounding validation is unavailable. */
    suspend fun generate(request: AiCoachRequest): AiCoachMessage?
}
