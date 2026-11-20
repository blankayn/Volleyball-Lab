package com.volleyballlab.player.domain.repository

import kotlinx.coroutines.flow.Flow

/** Local preferences belonging to the signed-in account. */
data class CoachingPreferences(
    val voiceEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    /**
     * On unless the athlete turns it off. The reference figure is how the app
     * shows where to stand and what a rep is, so it should not have to be found
     * in a settings screen first.
     */
    val poseGuideEnabled: Boolean = true,
    val detailedAiCoachingEnabled: Boolean = false,
)

interface CoachingPreferencesRepository {
    val preferences: Flow<CoachingPreferences>
    suspend fun setVoiceEnabled(enabled: Boolean)
    suspend fun setHapticsEnabled(enabled: Boolean)
    suspend fun setPoseGuideEnabled(enabled: Boolean)
    suspend fun setDetailedAiCoachingEnabled(enabled: Boolean)
}
