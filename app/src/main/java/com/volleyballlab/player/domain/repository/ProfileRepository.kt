package com.volleyballlab.player.domain.repository

import com.volleyballlab.player.domain.model.AthleteProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

/**
 * The athlete's own details.
 *
 * Identity and address sync through a private Firestore document. Training
 * preferences and device photo paths stay in the account-scoped local store.
 */
interface ProfileRepository {
    val syncStatus: Flow<ProfileSyncStatus> get() = flowOf(ProfileSyncStatus.Saved)
    suspend fun retrySync() {}
    suspend fun current(): AthleteProfile = profile.first()

    /** The current profile. Emits again on every edit. */
    val profile: Flow<AthleteProfile>
    val syncError: Flow<String?> get() = flowOf(null)

    suspend fun save(profile: AthleteProfile)
    suspend fun update(transform: (AthleteProfile) -> AthleteProfile) = save(transform(profile.first()))
    suspend fun seedAccountName(name: String) = update { if (it.hasName) it else it.copy(name = name) }
}

enum class ProfileSyncStatus { Checking, Saved, WaitingForConnection, Saving, Failed }
