package com.volleyballlab.player.data.session

import com.volleyballlab.player.domain.model.SessionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** App-lifetime, ordered best-effort uploads; result navigation only awaits local storage. */
internal class SessionCloudMirror(
    private val cloud: SessionCloudStore,
    private val currentUid: () -> String?,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val operations = Channel<Pair<String, suspend () -> Unit>>(Channel.UNLIMITED)

    init {
        scope.launch {
            for ((ownerUid, operation) in operations) {
                if (currentUid() != ownerUid) continue
                try { operation() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Local results remain available for explicit coach submission. */ }
            }
        }
    }

    fun upload(ownerUid: String, session: SessionResult) = enqueue(ownerUid) { cloud.upload(ownerUid, session) }
    fun delete(ownerUid: String, sessionId: String) = enqueue(ownerUid) { cloud.delete(ownerUid, sessionId) }

    private fun enqueue(ownerUid: String, operation: suspend () -> Unit) {
        operations.trySend(ownerUid to operation)
    }
}
