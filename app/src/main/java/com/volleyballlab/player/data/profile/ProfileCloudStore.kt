package com.volleyballlab.player.data.profile

import com.volleyballlab.player.domain.model.AthleteProfile
import kotlinx.coroutines.flow.Flow

interface ScopedProfileStore {
    fun profileFor(uid: String): Flow<AthleteProfile>
    suspend fun updateFor(uid: String, transform: (AthleteProfile) -> AthleteProfile)
}
data class ProfileCloudSnapshot(val fields: Map<String, Any?>, val fromCache: Boolean, val pendingWrites: Boolean = false)
interface ProfileCloudStore {
    val pending: Flow<Set<String>> get() = kotlinx.coroutines.flow.flowOf(emptySet())
    suspend fun retry(uid: String) {}
    fun snapshots(uid: String): Flow<ProfileCloudSnapshot>
    /** Queue immediately so offline writes don't block signup. */
    fun enqueue(uid: String, fields: Map<String, Any?>)
    /** Transactionally seed only missing fields, preserving other devices' saved values. */
    fun fillMissing(uid: String, fields: Map<String, Any?>)
    val errors: Flow<Map<String, String>>
}
