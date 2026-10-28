package com.volleyballlab.player.data.session

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.volleyballlab.player.data.UserScope
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.SessionDirectory
import com.volleyballlab.player.domain.repository.SessionRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** One DataStore per file for the process, as the API requires. */
private val Context.sessionStore: DataStore<Preferences> by preferencesDataStore(
    name = "training_sessions",
)

/**
 * Sessions persisted as a JSON array in Preferences DataStore.
 *
 * ## Why this, and what it deliberately is not
 *
 * This replaces [InMemorySessionRepository], whose KDoc explains at length why a
 * database was not written: conflict 2 in the capstone document has the network
 * model specifying Cloud Firestore and the data dictionary specifying MySQL, and
 * writing a Room schema would settle that on the team's behalf.
 *
 * That reasoning still holds, which is why this is a serialised blob rather than a
 * schema. There are no tables, no entities, no migrations and no annotation
 * processor - nothing here says anything about how sessions should be *modelled*,
 * only that they should survive the app being killed. A screen that reads
 * `0 minutes, 0 sets` after every cold start is not honest about the athlete's
 * training, and that was the cost of holding out for the schema decision.
 *
 * Both properties the previous implementation promised are kept: [sessions] emits
 * on every change, and [save] needs no network.
 *
 * ## The cost of a blob, stated plainly
 *
 * Preferences DataStore rewrites the whole file on every edit, so saving the
 * hundredth session rewrites the other ninety-nine. At this app's scale - a set
 * takes minutes to record, so sessions arrive at human speed - that is nothing. It
 * would matter at thousands, which is what [MAX_SESSIONS] is for: history is capped
 * at the most recent sessions rather than allowed to grow without bound.
 */
class DataStoreSessionRepository(
    private val context: Context,
    private val authRepository: AuthRepository,
    /**
     * Best-effort cloud mirror. Runs after the local write lands, so history on
     * this phone never waits on the network and never fails because of it. Null
     * in unit tests, where there is no backend.
     */
    private val cloud: SessionCloudStore? = null,
) : SessionRepository, SessionDirectory {

    private val cloudMirror = cloud?.let { SessionCloudMirror(it, { authRepository.currentUid }) }

    /**
     * One athlete's sessions, never the phone's.
     *
     * Scoped by uid because this store used to be device-wide, which meant a second
     * account signing in on the same handset inherited the first athlete's whole
     * training history - and, since the dashboard and the Coach tab reason from it,
     * was then given feedback about someone else's technique.
     */
    override val sessions: Flow<List<SessionResult>> = combine(
        authRepository.currentUser,
        context.sessionStore.data
            // An unreadable file must not take the app down on launch. The athlete
            // loses history they can no longer have either way; a crash loop would
            // additionally cost them the app.
            .catch { failure ->
                if (failure is IOException) emit(emptyPreferences()) else throw failure
            },
    ) { user, preferences ->
        val scope = UserScope.of(user?.uid) ?: return@combine emptyList()
        preferences[scope.string(KEY_SESSIONS)].decodeSessions()
    }

    /**
     * A named account's sessions, for the coach shell.
     *
     * The same file and the same decode, with the scope coming from the argument
     * instead of from the session. Sharing one store rather than replicating a
     * player's history into a coach-owned copy is what keeps the two views of a
     * session honest: a coach reads what the athlete's own app shows, not a
     * snapshot taken at submission time that has since drifted.
     *
     * See [SessionDirectory] for who is allowed to call this and why.
     */
    override fun sessionsOf(uid: String): Flow<List<SessionResult>> =
        context.sessionStore.data
            .catch { failure ->
                if (failure is IOException) emit(emptyPreferences()) else throw failure
            }
            .map { preferences ->
                val scope = UserScope.of(uid) ?: return@map emptyList()
                preferences[scope.string(KEY_SESSIONS)].decodeSessions()
            }

    override suspend fun save(session: SessionResult) {
        val ownerUid = authRepository.currentUid ?: return
        val scope = UserScope.of(ownerUid) ?: return
        val evictedVideos = mutableListOf<String>()
        context.sessionStore.edit { preferences ->
            val current = preferences[scope.string(KEY_SESSIONS)].decodeSessions()
            val all = (current.filterNot { it.id == session.id } + session)
                // Replace by id rather than append, so re-saving a session after a
                // threshold change updates it instead of duplicating it.
                .sortedByDescending { it.startedAtEpochMs }
            val updated = all.take(MAX_SESSIONS)
            evictedVideos += all.drop(MAX_SESSIONS).mapNotNull { it.videoPath }
            preferences[scope.string(KEY_SESSIONS)] =
                json.encodeToString(updated.map { it.toRecord() })
        }
        evictedVideos.forEach(::deletePrivateVideo)
        // After the local write, never before: the phone's history is the source
        // of truth and the cloud row is what the coach portal reads.
        cloudMirror?.upload(ownerUid, session)
    }

    override suspend fun byId(id: String): SessionResult? =
        sessions.first().firstOrNull { it.id == id }

    override suspend fun delete(id: String) {
        val ownerUid = authRepository.currentUid ?: return
        val scope = UserScope.of(ownerUid) ?: return
        var deletedVideo: String? = null
        context.sessionStore.edit { preferences ->
            val key = scope.string(KEY_SESSIONS)
            val sessions = preferences[key].decodeSessions()
            deletedVideo = sessions.firstOrNull { it.id == id }?.videoPath
            val remaining = sessions.filterNot { it.id == id }
            preferences[key] = json.encodeToString(remaining.map { it.toRecord() })
        }
        deletedVideo?.let(::deletePrivateVideo)
        cloudMirror?.delete(ownerUid, id)
    }

    override suspend fun deleteVideo(id: String) {
        val ownerUid = authRepository.currentUid ?: return
        val scope = UserScope.of(ownerUid) ?: return
        var deletedVideo: String? = null
        var updatedSession: SessionResult? = null
        context.sessionStore.edit { preferences ->
            val key = scope.string(KEY_SESSIONS)
            val sessions = preferences[key].decodeSessions()
            val target = sessions.firstOrNull { it.id == id } ?: return@edit
            deletedVideo = target.videoPath
            updatedSession = target.copy(videoPath = null)
            preferences[key] = json.encodeToString(
                sessions.map { if (it.id == id) target.copy(videoPath = null) else it }.map { it.toRecord() },
            )
        }
        deletedVideo?.let(::deletePrivateVideo)
        updatedSession?.let { cloudMirror?.upload(ownerUid, it) }
    }

    private fun deletePrivateVideo(path: String) {
        runCatching {
            val directory = File(context.filesDir, "training_videos").canonicalFile
            val video = File(path).canonicalFile
            if (video.parentFile == directory) {
                VideoReviewCache.delete(context, path)
                video.delete()
            }
        }.onFailure { Log.w(TAG, "Could not delete session video", it) }
    }

    /**
     * Decodes the stored blob, treating malformed content as no content.
     *
     * Returning an empty list rather than throwing is the same bargain the rest of
     * this class makes: a file written by a newer build, or truncated by a device
     * dying mid-write, costs the athlete their history but must not cost them a
     * working app. The failure is logged so it is visible in a bug report rather
     * than silent.
     */
    private fun String?.decodeSessions(): List<SessionResult> {
        if (isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<SessionRecord>>(this) }
            .onFailure { Log.w(TAG, "Discarding unreadable session history", it) }
            .getOrDefault(emptyList())
            .mapNotNull { it.toDomain() }
    }

    private companion object {
        const val KEY_SESSIONS = "sessions"

        /**
         * `ignoreUnknownKeys` so a field added by a later build is skipped rather
         * than failing the whole file, which would throw away every session the
         * athlete has for the sake of one property this build does not understand.
         */
        val json = Json { ignoreUnknownKeys = true }

        /**
         * Enough history that no realistic capstone demo or field test hits it,
         * bounded so the rewrite-everything cost stays bounded too.
         */
        const val MAX_SESSIONS = 200

        const val TAG = "SessionStore"
    }
}

internal suspend fun clearSessionAccount(context: Context, uid: String) =
    com.volleyballlab.player.data.account.clearAccountPreferences(context.sessionStore, uid)
