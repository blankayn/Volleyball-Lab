package com.volleyballlab.player.data.coaching

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.volleyballlab.player.data.UserScope
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.CoachingPreferences
import com.volleyballlab.player.domain.repository.CoachingPreferencesRepository
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine

private val Context.coachingPreferencesStore by preferencesDataStore(name = "coaching_preferences")

class DataStoreCoachingPreferencesRepository(
    context: Context,
    private val authRepository: AuthRepository,
) : CoachingPreferencesRepository {
    private val store = context.applicationContext.coachingPreferencesStore

    override val preferences = combine(
        authRepository.currentUser,
        store.data.catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        },
    ) { user, stored ->
        val scope = UserScope.of(user?.uid)
        if (scope == null) CoachingPreferences() else CoachingPreferences(
            voiceEnabled = stored[scope.boolean("voice_enabled")] ?: true,
            hapticsEnabled = stored[scope.boolean("haptics_enabled")] ?: true,
            poseGuideEnabled = stored[scope.boolean("pose_guide_enabled")] ?: false,
            detailedAiCoachingEnabled = stored[scope.boolean("detailed_ai_enabled")] ?: false,
        )
    }

    override suspend fun setVoiceEnabled(enabled: Boolean) = save("voice_enabled", enabled)

    override suspend fun setHapticsEnabled(enabled: Boolean) = save("haptics_enabled", enabled)
    override suspend fun setPoseGuideEnabled(enabled: Boolean) = save("pose_guide_enabled", enabled)
    override suspend fun setDetailedAiCoachingEnabled(enabled: Boolean) = save("detailed_ai_enabled", enabled)

    private suspend fun save(name: String, enabled: Boolean) {
        val scope = UserScope.of(authRepository.currentUid) ?: return
        store.edit { it[scope.boolean(name)] = enabled }
    }
}

internal suspend fun clearCoachingAccount(context: Context, uid: String) =
    com.volleyballlab.player.data.account.clearAccountPreferences(context.coachingPreferencesStore, uid)
