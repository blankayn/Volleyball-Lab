package com.volleyballlab.player.data.coaching

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.firestore.FirebaseFirestore
import com.volleyballlab.player.data.UserScope
import com.volleyballlab.player.domain.model.AdaptiveMemory
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.RepMark
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.model.TeachingOutcome
import com.volleyballlab.player.domain.repository.AuthRepository
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.adaptiveCoachStore by preferencesDataStore(name = "adaptive_coach")

/**
 * The memory behind the adaptive coach. It stays on the phone, per account.
 *
 * Trends are worked out from saved sessions each time, so they come back on a new phone.
 * Which way of explaining worked, and refused attempts, start again there.
 */
interface AdaptiveCoachRepository {
    val memory: Flow<AdaptiveMemory>
    /** Reps the player's own coach marked. Empty while offline with nothing cached. */
    val marks: Flow<List<RepMark>>
    suspend fun remember(skill: Skill, outcomes: List<TeachingOutcome>, missed: List<Pair<CueId, Long>>)
}

class LocalAdaptiveCoachRepository(
    context: Context,
    private val auth: AuthRepository,
    private val db: FirebaseFirestore,
) : AdaptiveCoachRepository {
    private val store = context.applicationContext.adaptiveCoachStore
    private val json = Json { ignoreUnknownKeys = true }

    override val memory: Flow<AdaptiveMemory> = combine(auth.currentUser,
        store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }) { user, stored ->
        UserScope.of(user?.uid)?.let { decode(stored[it.string(KEY)]) } ?: AdaptiveMemory()
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val marks: Flow<List<RepMark>> = auth.currentUser.map { it?.uid }.distinctUntilChanged()
        .flatMapLatest { uid -> if (uid == null) flowOf(emptyList()) else watchMarks(uid) }

    override suspend fun remember(skill: Skill, outcomes: List<TeachingOutcome>, missed: List<Pair<CueId, Long>>) {
        if (outcomes.isEmpty() && missed.isEmpty()) return
        val scope = UserScope.of(auth.currentUid) ?: return
        store.edit { it[scope.string(KEY)] = json.encodeToString(decode(it[scope.string(KEY)]).remember(skill, outcomes, missed)) }
    }

    /** Anything unreadable is forgotten: the coach then simply starts learning again. */
    private fun decode(value: String?): AdaptiveMemory =
        value?.let { runCatching { json.decodeFromString<AdaptiveMemory>(it) }.getOrNull() } ?: AdaptiveMemory()

    private fun watchMarks(uid: String): Flow<List<RepMark>> = callbackFlow {
        trySend(emptyList())
        val registration = db.collection("repLabels").whereEqualTo("playerUid", uid).addSnapshotListener { snapshot, _ ->
            if (snapshot != null) trySend(snapshot.documents.mapNotNull { RepMarks.of(it.data.orEmpty(), it.getTimestamp("createdAt")?.toDate()?.time) })
        }
        awaitClose { registration.remove() }
    }

    private companion object { const val KEY = "memory" }
}

internal object RepMarks {
    /** Null for a row this version cannot read. [atEpochMs] is null while a write is still on its way. */
    fun of(data: Map<String, Any?>, atEpochMs: Long?): RepMark? {
        val verdict = data["verdict"] as? String
        if (verdict != "Right" && verdict != "Wrong") return null
        val rep = (data["repNumber"] as? Number)?.toInt()?.takeIf { it >= 1 } ?: return null
        val cues = (data["cueIds"] as? List<*>).orEmpty().mapNotNull { name -> CueId.entries.firstOrNull { it.name == name } }
        return RepMark(data["sessionId"] as? String ?: return null, rep, verdict == "Right",
            if (verdict == "Wrong") cues.toSet() else emptySet(), atEpochMs ?: Long.MAX_VALUE)
    }
}

internal suspend fun clearAdaptiveCoachAccount(context: Context, uid: String) =
    com.volleyballlab.player.data.account.clearAccountPreferences(context.adaptiveCoachStore, uid)
