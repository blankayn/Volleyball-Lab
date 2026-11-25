package com.volleyballlab.player.domain.repository

import kotlinx.coroutines.flow.Flow

/** The arm the athlete hits with. Saved by name, so entries are never renamed. */
enum class DominantArm { Right, Left }

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
    /** Which arm faces the camera in a side view. Chosen on the session setup screen. */
    val dominantArm: DominantArm = DominantArm.Right,
)

interface CoachingPreferencesRepository {
    val preferences: Flow<CoachingPreferences>
    suspend fun setVoiceEnabled(enabled: Boolean)
    suspend fun setHapticsEnabled(enabled: Boolean)
    suspend fun setPoseGuideEnabled(enabled: Boolean)
    suspend fun setDetailedAiCoachingEnabled(enabled: Boolean)
    suspend fun setDominantArm(arm: DominantArm)
}
