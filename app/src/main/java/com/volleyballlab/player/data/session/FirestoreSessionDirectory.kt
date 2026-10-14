package com.volleyballlab.player.data.session

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.volleyballlab.player.data.cloud.CloudSchema
import com.volleyballlab.player.data.cloud.awaitCloud
import com.volleyballlab.player.data.cloud.snapshots
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.SessionDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The player's own sessions, mirrored to the cloud.
 *
 * Called from [DataStoreSessionRepository] after the local write lands, so the
 * on-device history never waits on the network and never fails because of it.
 * Only scores cross the wire — the same receipt the review queue works from —
 * and the share list is preserved across re-saves so an updated session does
 * not silently un-share itself.
 */
interface SessionCloudStore {
    suspend fun upload(ownerUid: String, session: SessionResult)
    suspend fun delete(ownerUid: String, sessionId: String)
}

@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreSessionCloudStore(
    private val db: FirebaseFirestore,
    private val authRepository: AuthRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : SessionCloudStore {

    override suspend fun upload(ownerUid: String, session: SessionResult) {
        if (authRepository.currentUid != ownerUid) return
        try {
            val ref = db.collection(CloudSchema.SESSIONS).document(session.id)
            db.runTransaction { transaction ->
                val existing = transaction.get(ref).data
                // A coach submission may race this background save; preserve its current share list.
                check(authRepository.currentUid == ownerUid) { "Account changed during upload" }
                check(existing == null || existing["ownerUid"] == ownerUid) { "Session owner mismatch" }
                val sharedWith = existing?.let { CloudSchema.strList(it, "sharedWith") } ?: emptyList()
                transaction.set(ref, CloudSchema.sessionDoc(session, ownerUid, sharedWith, now()))
                Unit
            }.awaitCloud()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Log.w(TAG, "Session cloud mirror failed", error) }
    }

    override suspend fun delete(ownerUid: String, sessionId: String) {
        if (authRepository.currentUid != ownerUid) return
        try {
            db.collection(CloudSchema.SESSIONS).document(sessionId).delete().awaitCloud()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Log.w(TAG, "Session cloud delete failed", error) }
    }

    private companion object {
        const val TAG = "VbSessionCloud"
    }
}

/**
 * The coach shell's read path into other accounts' training.
 *
 * A coach sees a named athlete's sessions only when that athlete submitted them
 * — enforced by the rules on `sharedWith`, with the roster check living in the
 * ViewModels as before. An empty list is indistinguishable from "never trained",
 * so this cannot be used to probe whether an account exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreSessionDirectory(
    private val db: FirebaseFirestore,
    private val authRepository: AuthRepository,
) : SessionDirectory {

    override fun sessionsOf(uid: String): Flow<List<SessionResult>> =
        authRepository.currentUser.flatMapLatest { user ->
            val coachUid = user?.uid?.takeIf { it.isNotBlank() }
                ?: return@flatMapLatest flowOf(emptyList())
            // One filter only, on purpose. The rules grant a coach exactly the
            // docs naming them in `sharedWith`, and a query must stay inside
            // what the rules allow — adding an `ownerUid` filter would need a
            // composite index and would fail closed on unshared rows. The uid
            // match happens client-side below.
            db.collection(CloudSchema.SESSIONS)
                .whereArrayContains("sharedWith", coachUid)
                .snapshots()
                .map { snap ->
                    snap.documents
                        .filter { (it.data?.get("ownerUid") as? String) == uid }
                        .mapNotNull { it.data?.let(CloudSchema::sessionOf) }
                        .sortedByDescending { it.startedAtEpochMs }
                }
        }
}
