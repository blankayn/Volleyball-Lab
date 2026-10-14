package com.volleyballlab.player.data.drill

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.volleyballlab.player.data.UserScope
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.DrillPracticeRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.drillPracticeStore by preferencesDataStore("drill_practice")

class DataStoreDrillPracticeRepository(
    private val store: DataStore<Preferences>,
    private val auth: AuthRepository,
) : DrillPracticeRepository {
    constructor(context: Context, auth: AuthRepository) : this(context.drillPracticeStore, auth)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    override val history = combine(auth.currentUser, store.data) { user, data ->
        UserScope.of(user?.uid)?.let { decodeHistory(data[it.string("history")]) } ?: emptyList()
    }
    override val draft = combine(auth.currentUser, store.data) { user, data ->
        UserScope.of(user?.uid)?.let { decodeDraft(data[it.string("draft")]) }
    }
    override suspend fun draftFor(uid: String): DrillPracticeDraft? {
        checkAccount(uid)
        return decodeDraft(store.data.first()[requireNotNull(UserScope.of(uid)).string("draft")])
    }
    override suspend fun checkpoint(uid: String, draft: DrillPracticeDraft) {
        checkAccount(uid)
        val scope = requireNotNull(UserScope.of(uid))
        store.edit { data ->
            checkAccount(uid)
            // A retried callback after Finish must not resurrect a completed draft.
            if (decodeHistory(data[scope.string("history")]).none { it.id == draft.id }) {
                data[scope.string("draft")] = json.encodeToString(draft)
            }
        }
    }
    override suspend fun finish(uid: String, record: DrillPracticeRecord) {
        checkAccount(uid)
        require(record.sets.isNotEmpty())
        val scope = requireNotNull(UserScope.of(uid))
        store.edit { data ->
            checkAccount(uid)
            val key = scope.string("history")
            val history = decodeHistory(data[key])
            if (history.none { it.id == record.id }) data[key] = json.encodeToString((history + record).sortedByDescending { it.startedAtEpochMs })
            if (decodeDraft(data[scope.string("draft")])?.id == record.id) data.remove(scope.string("draft"))
        }
    }
    override suspend fun discard(uid: String, workoutId: String) {
        checkAccount(uid)
        val scope = requireNotNull(UserScope.of(uid))
        store.edit { data ->
            checkAccount(uid)
            if (decodeDraft(data[scope.string("draft")])?.id == workoutId) data.remove(scope.string("draft"))
        }
    }
    private fun checkAccount(uid: String) { check(auth.currentUid == uid) { "Your account changed. Return to Train and sign in again." } }
    private fun decodeHistory(raw: String?): List<DrillPracticeRecord> = if (raw == null) emptyList() else json.decodeFromString(raw)
    private fun decodeDraft(raw: String?): DrillPracticeDraft? = raw?.let { json.decodeFromString(it) }
}

internal suspend fun clearDrillAccount(context: Context, uid: String) =
    com.volleyballlab.player.data.account.clearAccountPreferences(context.drillPracticeStore, uid)
