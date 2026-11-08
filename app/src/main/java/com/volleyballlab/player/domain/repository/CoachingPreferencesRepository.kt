package com.volleyballlab.player.domain.repository

import kotlinx.coroutines.flow.Flow

/** Local preferences belonging to the signed-in account. */
data class CoachingPreferences(
    val voiceEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val poseGuideEnabled: Boolean = false,
    val detailedAiCoachingEnabled: Boolean = false,
)

interface CoachingPreferencesRepository {
    val preferences: Flow<CoachingPreferences>
    suspend fun setVoiceEnabled(enabled: Boolean)
    suspend fun setHapticsEnabled(enabled: Boolean)
    suspend fun setPoseGuideEnabled(enabled: Boolean)
    suspend fun setDetailedAiCoachingEnabled(enabled: Boolean)
}
