package com.volleyballlab.player.domain.repository

import com.volleyballlab.player.domain.model.AthleteProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

/**
 * The athlete's own details.
 *
 * Identity, address, and confirmed training preferences sync privately. Device
 * photo paths stay in the account-scoped local store.
 */
interface ProfileRepository {
    val syncStatus: Flow<ProfileSyncStatus> get() = flowOf(ProfileSyncStatus.Saved)
    suspend fun retrySync() {}
    /** Bounded first cloud read before choosing onboarding on a fresh installation. */
    suspend fun awaitInitialRestore() {}
    suspend fun current(): AthleteProfile = profile.first()

    /** The current profile. Emits again on every edit. */
    val profile: Flow<AthleteProfile>
    val syncError: Flow<String?> get() = flowOf(null)

    suspend fun save(profile: AthleteProfile)
    suspend fun update(transform: (AthleteProfile) -> AthleteProfile) = save(transform(profile.first()))
    suspend fun seedAccountName(name: String) = update { if (it.hasName) it else it.copy(name = name) }
}

enum class ProfileSyncStatus { Checking, Saved, WaitingForConnection, Saving, Failed }
