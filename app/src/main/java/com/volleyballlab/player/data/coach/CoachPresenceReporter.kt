package com.volleyballlab.player.data.coach

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.volleyballlab.player.data.cloud.awaitCloud
import com.volleyballlab.player.domain.model.CoachAvailability
import com.volleyballlab.player.domain.model.CoachPresencePolicy
import com.volleyballlab.player.domain.repository.AuthRepository
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** One short-lived lease per app connection, independent of other tabs/devices. */
class CoachPresenceReporter(context: Context, private val db: FirebaseFirestore, private val auth: AuthRepository) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    private var uid: String? = null
    private var ref: com.google.firebase.firestore.DocumentReference? = null
    private var foreground = false
    private var routeBusy = false
    private val guidance = mutableSetOf<String>()
    private var lastInteraction = SystemClock.elapsedRealtime()

    fun foreground(value: Boolean) {
        foreground = value
        if (value) lastInteraction = SystemClock.elapsedRealtime()
        wake.trySend(Unit)
    }
    fun interact() { lastInteraction = SystemClock.elapsedRealtime(); wake.trySend(Unit) }
    fun reviewing(value: Boolean) { routeBusy = value; wake.trySend(Unit) }
    fun writing(key: String, value: Boolean) {
        if (value) guidance.add(key) else guidance.remove(key)
        wake.trySend(Unit)
    }

    fun connect(coachUid: String) {
        if (uid == coachUid) return
        disconnect()
        uid = coachUid
        val sessionRef = db.collection("coachPresence").document(UUID.randomUUID().toString())
        ref = sessionRef
        lastInteraction = SystemClock.elapsedRealtime()
        job = scope.launch {
            var lastStatus: CoachAvailability? = null
            var lastAcknowledged = Long.MIN_VALUE
            var nextAttempt = 0L
            var pruned = false
            while (isActive && auth.currentUid == coachUid) {
                val clock = SystemClock.elapsedRealtime()
                val connected = online()
                val status = CoachPresencePolicy.localStatus(foreground, connected, routeBusy || guidance.isNotEmpty(), clock - lastInteraction)
                if (connected && clock >= nextAttempt && (status != lastStatus || (status != CoachAvailability.Offline && clock - lastAcknowledged >= CoachPresencePolicy.HEARTBEAT_MS))) {
                    try {
                        withTimeout(10_000) {
                            sessionRef.set(mapOf("coachUid" to coachUid, "status" to status.name,
                                "updatedAt" to FieldValue.serverTimestamp(),
                                "expiresAt" to Timestamp(Date(System.currentTimeMillis() + CoachPresencePolicy.LEASE_MS)))).awaitCloud()
                        }
                        lastStatus = status
                        lastAcknowledged = SystemClock.elapsedRealtime()
                        if (!pruned) {
                            pruned = true
                            launch { pruneExpired(coachUid, sessionRef.id) }
                        }
                    } catch (failure: CancellationException) {
                        if (!currentCoroutineContext().isActive) throw failure
                        nextAttempt = SystemClock.elapsedRealtime() + CoachPresencePolicy.CHECK_MS
                    } catch (_: Exception) {
                        // A failed heartbeat never becomes an acknowledged online claim.
                        nextAttempt = SystemClock.elapsedRealtime() + CoachPresencePolicy.CHECK_MS
                    }
                }
                withTimeoutOrNull(CoachPresencePolicy.CHECK_MS) { wake.receive() }
            }
        }
    }

    fun disconnect(): com.google.android.gms.tasks.Task<Void>? {
        job?.cancel(); job = null
        // Deleting this connection cannot clear another device's lease.
        val cleanup = if (uid != null && auth.currentUid == uid) ref?.delete()?.addOnFailureListener { } else null
        uid = null; ref = null; routeBusy = false; guidance.clear()
        return cleanup
    }

    suspend fun beforeSignOut() {
        try { withTimeout(2_000) { disconnect()?.awaitCloud() } }
        catch (failure: CancellationException) { if (!currentCoroutineContext().isActive) throw failure }
        catch (_: Exception) { /* Offline cleanup is bounded by the lease. */ }
    }

    private fun online(): Boolean = runCatching {
        val network = connectivity.activeNetwork ?: return@runCatching false
        connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(false)

    private suspend fun pruneExpired(coachUid: String, currentId: String) {
        try {
            val rows = withTimeout(10_000) { db.collection("coachPresence").whereEqualTo("coachUid", coachUid).limit(50).get(Source.SERVER).awaitCloud() }
            if (auth.currentUid != coachUid) return
            val now = System.currentTimeMillis()
            val expired = rows.documents.filter { it.id != currentId && (it.getTimestamp("expiresAt")?.toDate()?.time ?: Long.MAX_VALUE) <= now }
            if (expired.isNotEmpty()) withTimeout(10_000) { db.batch().apply { expired.forEach { delete(it.reference) } }.commit().awaitCloud() }
        } catch (failure: CancellationException) { if (!currentCoroutineContext().isActive) throw failure }
        catch (_: Exception) { /* Expired records are ignored even if cleanup is unavailable. */ }
    }
}
