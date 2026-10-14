package com.volleyballlab.player.data.cloud

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The Firestore equivalent of the Task bridge in `FirebaseAuthRepository`:
 * failure resumes with the exception, cancellation cancels, exactly once.
 */
suspend fun <T> Task<T>.awaitCloud(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val failure = task.exception
        when {
            failure != null -> continuation.resumeWithException(failure)
            task.isCanceled -> continuation.cancel()
            else -> continuation.resume(task.result)
        }
    }
}

/**
 * A query as a cold flow of snapshots.
 *
 * Errors are swallowed into an empty snapshot rather than thrown: a lost
 * connection must degrade the coach screens to their empty states, not crash
 * them. Firestore's own offline cache still serves reads while offline, so in
 * practice this path is for permission errors and a backend that was never
 * provisioned — both of which the empty states already explain.
 */
fun Query.snapshots(): Flow<QuerySnapshot> = callbackFlow {
    val registration = addSnapshotListener { snapshot, _ ->
        if (snapshot != null) trySend(snapshot)
    }
    awaitClose { registration.remove() }
}

/** As [snapshots], for a single document. Null while the doc does not exist. */
fun DocumentReference.snapshots(): Flow<DocumentSnapshot?> = callbackFlow {
    val registration = addSnapshotListener { snapshot, _ -> trySend(snapshot) }
    awaitClose { registration.remove() }
}
