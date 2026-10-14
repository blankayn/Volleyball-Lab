package com.volleyballlab.player.data.training

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.volleyballlab.player.data.UserScope
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.domain.repository.AuthRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime

private val Context.trainingWorkspace by preferencesDataStore("training_workspace")

class TrainingWorkspaceRepository(private val context: Context, private val auth: AuthRepository) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()
    private val data = context.trainingWorkspace.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
    val schedule = combine(auth.currentUser, data) { user, p ->
        UserScope.of(user?.uid)?.let { decodeSchedule(p[it.string("schedule")]) } ?: emptyList()
    }
    val reports = combine(auth.currentUser, data) { user, p ->
        UserScope.of(user?.uid)?.let { decodeReports(p[it.string("reports")]) } ?: emptyList()
    }
    suspend fun saveSchedule(item: ScheduledTraining) {
        LocalDate.parse(item.date); LocalTime.parse(item.time); require(item.targetReps in 1..100)
        val scope = UserScope.of(auth.currentUid) ?: return
        context.trainingWorkspace.edit { p ->
            val key = scope.string("schedule")
            p[key] = json.encodeToString((decodeSchedule(p[key]).filterNot { it.id == item.id } + item).sortedByDescending { it.date }.take(365))
        }
    }
    suspend fun deleteSchedule(id: String) {
        val scope = UserScope.of(auth.currentUid) ?: return
        context.trainingWorkspace.edit { p -> val key = scope.string("schedule"); p[key] = json.encodeToString(decodeSchedule(p[key]).filterNot { it.id == id }) }
    }
    suspend fun completeSchedule(id: String, completed: Boolean) {
        val scope = UserScope.of(auth.currentUid) ?: return
        context.trainingWorkspace.edit { p -> val key = scope.string("schedule"); p[key] = json.encodeToString(decodeSchedule(p[key]).map { if (it.id == id) it.copy(completed = completed) else it }) }
    }
    /** Serial quota checks; reopening a saved report costs no credits. */
    suspend fun createReport(sessionId: String, plan: UserPlan, generate: suspend () -> SavedDetailedReport?): SavedDetailedReport? = lock.withLock {
        val uid = auth.currentUid ?: return@withLock null
        val scope = UserScope.of(uid) ?: return@withLock null
        val existing = decodeReports(data.first()[scope.string("reports")])
        existing.firstOrNull { it.sessionId == sessionId }?.let { return@withLock it }
        if (!ReportAllowance.canCreate(sessionId, existing, plan)) return@withLock null
        val generated = generate() ?: return@withLock null
        if (auth.currentUid != uid) return@withLock null
        context.trainingWorkspace.edit { p -> p[scope.string("reports")] = json.encodeToString((existing + generated).takeLast(1000)) }
        generated
    }
    private fun decodeSchedule(value: String?): List<ScheduledTraining> = runCatching { json.decodeFromString<List<ScheduledTraining>>(value ?: "[]") }.getOrDefault(emptyList())
    private fun decodeReports(value: String?): List<SavedDetailedReport> = runCatching { json.decodeFromString<List<SavedDetailedReport>>(value ?: "[]") }.getOrDefault(emptyList())
}

internal suspend fun clearTrainingAccount(context: Context, uid: String) =
    com.volleyballlab.player.data.account.clearAccountPreferences(context.trainingWorkspace, uid)
