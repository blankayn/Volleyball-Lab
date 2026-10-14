package com.volleyballlab.player.data.profile

import com.volleyballlab.player.domain.model.AthleteProfile
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Cloud identity/address combined with the existing local training preferences. */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncedProfileRepository(
    private val local: ScopedProfileStore,
    private val auth: AuthRepository,
    private val cloud: ProfileCloudStore,
    scope: CoroutineScope,
) : ProfileRepository {
    private val latest = java.util.concurrent.ConcurrentHashMap<String, Map<String, Any?>>()
    private val edits = Mutex()
    private val statuses = MutableStateFlow<Map<String, com.volleyballlab.player.domain.repository.ProfileSyncStatus>>(emptyMap())
    override val syncStatus = combine(auth.currentUser, statuses, cloud.errors, cloud.pending) { user, states, errors, pending ->
        if (errors[user?.uid] != null) com.volleyballlab.player.domain.repository.ProfileSyncStatus.Failed
        else if (states[user?.uid] != com.volleyballlab.player.domain.repository.ProfileSyncStatus.WaitingForConnection && user?.uid in pending)
            com.volleyballlab.player.domain.repository.ProfileSyncStatus.Saving
        else states[user?.uid] ?: com.volleyballlab.player.domain.repository.ProfileSyncStatus.Checking
    }
    override suspend fun retrySync() { auth.currentUid?.let { cloud.retry(it) } }
    override suspend fun current(): AthleteProfile {
        val uid = auth.currentUid ?: return AthleteProfile.Anonymous
        return ProfileCloudSchema.overlay(local.profileFor(uid).first(), latest[uid].orEmpty())
    }
    override val syncError = combine(auth.currentUser, cloud.errors) { user, failures -> failures[user?.uid] }
    override val profile = auth.currentUser.map { it?.uid }.distinctUntilChanged().flatMapLatest { uid ->
        if (uid == null) flowOf(AthleteProfile.Anonymous) else {
            var migrated = false
            val remote = cloud.snapshots(uid).onEach { snapshot ->
                statuses.update { it + (uid to when {
                    snapshot.fromCache -> com.volleyballlab.player.domain.repository.ProfileSyncStatus.WaitingForConnection
                    snapshot.pendingWrites -> com.volleyballlab.player.domain.repository.ProfileSyncStatus.Saving
                    else -> com.volleyballlab.player.domain.repository.ProfileSyncStatus.Saved
                }) }
                latest[uid] = snapshot.fields
                if (!snapshot.fromCache && !migrated) {
                    cloud.fillMissing(uid, ProfileCloudSchema.seed(local.profileFor(uid).first()))
                    migrated = true
                }
            }.retryWhen { _, attempt -> delay((1_000L * (attempt + 1)).coerceAtMost(30_000)); true }
                .map { it.fields }.onStart { emit(latest[uid].orEmpty()) }
            combine(local.profileFor(uid), remote) { cached, fields -> ProfileCloudSchema.overlay(cached, fields) }
        }
    }.stateIn(scope, SharingStarted.Eagerly, AthleteProfile.Anonymous)

    override suspend fun save(profile: AthleteProfile) = update { profile }
    override suspend fun update(transform: (AthleteProfile) -> AthleteProfile) {
        val uid = auth.currentUid ?: return
        edits.withLock {
            var patch: Map<String, Any?> = emptyMap()
            local.updateFor(uid) { cached ->
                val before = ProfileCloudSchema.overlay(cached, latest[uid].orEmpty())
                val after = transform(before)
                patch = ProfileCloudSchema.patch(before, after)
                after
            }
            if (patch.isNotEmpty() && auth.currentUid == uid) {
                latest[uid] = latest[uid].orEmpty() + patch
                cloud.enqueue(uid, patch)
            }
        }
    }
    override suspend fun seedAccountName(name: String) {
        val uid = auth.currentUid ?: return
        // Auth's display name is a fallback, never an edit to newer cloud fields.
        local.updateFor(uid) { if (it.hasName) it else it.copy(name = name) }
    }
}
