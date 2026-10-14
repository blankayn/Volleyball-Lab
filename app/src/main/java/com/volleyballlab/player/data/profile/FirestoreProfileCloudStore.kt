package com.volleyballlab.player.data.profile

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FirestoreProfileCloudStore(private val db: FirebaseFirestore) : ProfileCloudStore {
    private data class IssueKey(val uid: String, val operation: String)
    private data class WriteKey(val uid: String, val sequence: Long)
    private data class PendingWrite(val fields: Map<String, Any?>, val failed: Boolean = false)
    private val sequence = java.util.concurrent.atomic.AtomicLong()
    private val writes = MutableStateFlow<Map<WriteKey, PendingWrite>>(emptyMap())
    private val migrations = MutableStateFlow<Set<String>>(emptySet())
    private val issues = MutableStateFlow<Map<IssueKey, String>>(emptyMap())
    private val seeds = java.util.concurrent.ConcurrentHashMap<String, Map<String, Any?>>()
    override val pending = kotlinx.coroutines.flow.combine(writes, migrations) { queued, migrating ->
        queued.filterValues { !it.failed }.keys.map { it.uid }.toSet() + migrating
    }
    override val errors = issues.map { failures -> failures.entries.associate { it.key.uid to it.value } }

    override suspend fun retry(uid: String) {
        val queued = writes.value
        val failed = queued.filter { it.key.uid == uid && it.value.failed }.toSortedMap(compareBy { it.sequence })
        val fields = failed.entries.fold(emptyMap<String, Any?>()) { all, (key, write) ->
            val newer = queued.filter { it.key.uid == uid && it.key.sequence > key.sequence && !it.value.failed }
                .values.flatMap { it.fields.keys }.toSet()
            all + (write.fields - newer)
        }
        if (fields.isNotEmpty()) {
            writes.update { it - failed.keys }
            enqueue(uid, fields)
        }
        seeds[uid]?.let { fillMissing(uid, it) }
    }

    override fun snapshots(uid: String) = callbackFlow {
        val registration = db.collection(ProfileCloudSchema.COLLECTION).document(uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, failure ->
                if (failure != null) { failed(uid, "read", failure); close(failure) }
                else if (snapshot != null) {
                    issues.update { it - IssueKey(uid, "read") }
                    trySend(ProfileCloudSnapshot(snapshot.data.orEmpty(), snapshot.metadata.isFromCache,
                        snapshot.metadata.hasPendingWrites()))
                }
            }
        awaitClose { registration.remove() }
    }

    override fun enqueue(uid: String, fields: Map<String, Any?>) {
        if (fields.isEmpty()) return
        val key = WriteKey(uid, sequence.incrementAndGet())
        writes.update { it + (key to PendingWrite(fields)) }
        val data = fields + ("updatedAt" to FieldValue.serverTimestamp())
        db.collection(ProfileCloudSchema.COLLECTION).document(uid)
            .set(data, SetOptions.mergeFields(data.keys.toList()))
            .addOnSuccessListener {
                writes.update { all ->
                    (all - key).mapValues { (previous, write) ->
                        if (previous.uid == uid && previous.sequence < key.sequence)
                            write.copy(fields = write.fields - fields.keys) else write
                    }.filterValues { it.fields.isNotEmpty() }
                }
                if (writes.value.none { it.key.uid == uid && it.value.failed })
                    issues.update { it - IssueKey(uid, "write") }
            }
            .addOnFailureListener { error ->
                writes.update { all -> all[key]?.let { all + (key to it.copy(failed = true)) } ?: all }
                if (writes.value.containsKey(key)) failed(uid, "write", error)
            }
    }

    override fun fillMissing(uid: String, fields: Map<String, Any?>) {
        if (fields.isEmpty()) return
        migrations.update { it + uid }; seeds[uid] = fields
        val ref = db.collection(ProfileCloudSchema.COLLECTION).document(uid)
        db.runTransaction { transaction ->
            val current = transaction.get(ref)
            val missing = fields.filterKeys { !current.contains(it) }
            if (missing.isNotEmpty()) {
                val data = missing + ("updatedAt" to FieldValue.serverTimestamp())
                transaction.set(ref, data, SetOptions.mergeFields(data.keys.toList()))
            }
        }.addOnSuccessListener {
            seeds.remove(uid, fields); migrations.update { it - uid }
            issues.update { it - IssueKey(uid, "migration") }
        }.addOnFailureListener { migrations.update { pending -> pending - uid }; failed(uid, "migration", it) }
    }

    private fun failed(uid: String, operation: String, error: Exception) {
        issues.update { it + (IssueKey(uid, operation) to "Profile sync could not finish. Check your connection and tap Retry.") }
        Log.w("ProfileSync", "Private profile sync failed", error)
    }
}
