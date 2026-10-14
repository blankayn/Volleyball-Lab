package com.volleyballlab.player.data.profile

import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.domain.repository.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSyncTest {
    private val ph = ProfileAddress(AddressOption("PH", "Philippines"), AddressOption("cebu", "Cebu"),
        AddressOption("city", "City of Cebu"), AddressOption("lahug", "Lahug"))
    private val us = ProfileAddress(AddressOption("US", "United States"))
    private val auth = object : AuthRepository {
        override val currentUser = MutableStateFlow<AuthUser?>(AuthUser("a", "a@example.com", "Auth Name"))
        override val currentUid get() = currentUser.value?.uid
        override suspend fun restoreSession() = currentUser.value
        override suspend fun signIn(email: String, password: String, rememberSession: Boolean) = AuthOutcome.Success
        override suspend fun signUp(name: String, email: String, password: String) = AuthOutcome.Success
        override suspend fun signInWithGoogle(idToken: String, rememberSession: Boolean) = AuthOutcome.Success
        override suspend fun sendPasswordReset(email: String) = AuthOutcome.Success
        override suspend fun signOut() { currentUser.value = null }
    }
    private class Local : ScopedProfileStore {
        val rows = mutableMapOf<String, MutableStateFlow<AthleteProfile>>()
        override fun profileFor(uid: String) = rows.getOrPut(uid) { MutableStateFlow(AthleteProfile.Anonymous) }
        override suspend fun updateFor(uid: String, transform: (AthleteProfile) -> AthleteProfile) { profileFor(uid).update(transform) }
    }
    private class Cloud : ProfileCloudStore {
        val rows = mutableMapOf<String, MutableStateFlow<ProfileCloudSnapshot>>()
        val writes = mutableListOf<Pair<String, Map<String, Any?>>>()
        val seeds = mutableListOf<Pair<String, Map<String, Any?>>>()
        override val errors = MutableStateFlow<Map<String, String>>(emptyMap())
        override val pending = MutableStateFlow<Set<String>>(emptySet())
        var retriedUid: String? = null
        override suspend fun retry(uid: String) { retriedUid = uid }
        override fun snapshots(uid: String) = rows.getOrPut(uid) { MutableStateFlow(ProfileCloudSnapshot(emptyMap(), true)) }
        override fun enqueue(uid: String, fields: Map<String, Any?>) {
            writes += uid to fields
            snapshots(uid).update { ProfileCloudSnapshot(it.fields + fields, true) }
        }
        override fun fillMissing(uid: String, fields: Map<String, Any?>) {
            seeds += uid to fields
            snapshots(uid).update { old -> ProfileCloudSnapshot(old.fields + fields.filterKeys { !old.fields.containsKey(it) }, false) }
        }
    }
    @Test fun freshDeviceRestoresCloudAddressAndKeepsLocalPreferencesAndPhoto() = runTest {
        val local = Local(); val cloud = Cloud()
        local.updateFor("a") { it.copy(position = CourtPosition.Libero, photoPath = "local.jpg") }
        cloud.snapshots("a").value = ProfileCloudSnapshot(ProfileCloudSchema.fields(AthleteProfile(name = "Kirt", address = ph)), false)
        val repo = SyncedProfileRepository(local, auth, cloud, backgroundScope); runCurrent()
        assertEquals(ph, repo.profile.value.address)
        assertEquals(CourtPosition.Libero, repo.profile.value.position)
        assertEquals("local.jpg", repo.profile.value.photoPath)
        assertTrue(cloud.writes.isEmpty())
        auth.currentUser.value = AuthUser("b", "b@example.com", "B"); runCurrent()
        assertNull(repo.profile.value.address)
        cloud.snapshots("a").value = ProfileCloudSnapshot(ProfileCloudSchema.fields(AthleteProfile(address = us)), false); runCurrent()
        assertNull(repo.profile.value.address)
    }
    @Test fun syncStatusReportsCachePendingSuccessAndFailureWithScopedRetry() = runTest {
        val cloud = Cloud(); val repo = SyncedProfileRepository(Local(), auth, cloud, backgroundScope)
        runCurrent()
        assertEquals(ProfileSyncStatus.WaitingForConnection, repo.syncStatus.first())
        cloud.snapshots("a").value = ProfileCloudSnapshot(emptyMap(), false); runCurrent()
        cloud.snapshots("a").value = ProfileCloudSnapshot(emptyMap(), false, true); runCurrent()
        assertEquals(ProfileSyncStatus.Saving, repo.syncStatus.first())
        cloud.snapshots("a").value = ProfileCloudSnapshot(emptyMap(), false); runCurrent()
        assertEquals(ProfileSyncStatus.Saved, repo.syncStatus.first())
        cloud.pending.value = setOf("a")
        assertEquals(ProfileSyncStatus.Saving, repo.syncStatus.first())
        cloud.errors.value = mapOf("a" to "Failed")
        assertEquals(ProfileSyncStatus.Failed, repo.syncStatus.first())
        repo.retrySync(); assertEquals("a", cloud.retriedUid)
        auth.currentUser.value = AuthUser("b", "b@example.com", "B"); runCurrent()
        assertEquals(ProfileSyncStatus.WaitingForConnection, repo.syncStatus.first())
        repo.retrySync(); assertEquals("b", cloud.retriedUid)
    }
    @Test fun legacyAddressMigratesOnlyAfterServerResponseWithoutReplacingOtherDeviceValues() = runTest {
        val local = Local(); val cloud = Cloud()
        local.updateFor("a") { it.copy(name = "Old local", address = ph) }
        val repo = SyncedProfileRepository(local, auth, cloud, backgroundScope); runCurrent()
        assertTrue(cloud.seeds.isEmpty())
        cloud.snapshots("a").value = ProfileCloudSnapshot(mapOf("name" to "New cloud"), false); runCurrent()
        assertEquals(ph, repo.profile.value.address)
        assertEquals("New cloud", repo.profile.value.name)
        assertEquals(1, cloud.seeds.size)
    }
    @Test fun signupQueuesNameAndAddressOfflineAndNameEditDoesNotRewriteAddress() = runTest {
        val local = Local(); val cloud = Cloud()
        val repo = SyncedProfileRepository(local, auth, cloud, backgroundScope); runCurrent()
        repo.update { it.copy(name = "Kirt Magno", firstName = "Kirt", lastName = "Magno", address = ph) }; runCurrent()
        assertEquals("a", cloud.writes.single().first)
        assertEquals(ph, repo.profile.value.address)
        repo.update { it.copy(name = "Kirt Updated") }; runCurrent()
        assertEquals(setOf("name"), cloud.writes.last().second.keys)
        repo.update { it.copy(address = us) }; runCurrent()
        assertEquals(us, repo.profile.value.address)
        assertNull(repo.profile.value.address?.barangay)
        assertFalse(cloud.writes.last().second.containsKey("role"))
    }
    @Test fun authFallbackCannotOverwriteCloudNameAndFailuresBelongToCurrentAccountOnly() = runTest {
        val local = Local(); val cloud = Cloud()
        cloud.snapshots("a").value = ProfileCloudSnapshot(mapOf("name" to "New cloud", "firstName" to "New"), false)
        val repo = SyncedProfileRepository(local, auth, cloud, backgroundScope); runCurrent()
        repo.seedAccountName("Old auth"); runCurrent()
        assertEquals("New cloud", repo.profile.value.name)
        assertTrue(cloud.writes.isEmpty())
        cloud.errors.value = mapOf("a" to "Failed")
        assertEquals("Failed", repo.syncError.first())
        auth.currentUser.value = AuthUser("b", "b@example.com", "B"); runCurrent()
        assertNull(repo.syncError.first())
        auth.signOut(); runCurrent()
        repo.update { it.copy(address = ph) }
        assertEquals(AthleteProfile.Anonymous, repo.profile.value)
        assertTrue(cloud.writes.isEmpty())
    }
}
