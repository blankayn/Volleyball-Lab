package com.volleyballlab.player.data.push

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.volleyballlab.player.data.cloud.awaitCloud

class PushTokenRegistrar(
    private val currentUid: () -> String?,
    private val db: FirebaseFirestore,
) {
    suspend fun registerCurrentUser() {
        if (currentUid() == null) return
        FirebaseMessaging.getInstance().register().awaitCloud()
    }

    suspend fun store(uid: String, token: String) {
        if (currentUid() != uid) return
        db.collection(COLLECTION)
            .document(token)
            .set(
                mapOf(
                    "uid" to uid,
                    "token" to token,
                    "platform" to "android",
                    "updatedAt" to System.currentTimeMillis(),
                ),
                SetOptions.merge(),
            )
            .awaitCloud()
    }

    companion object {
        const val COLLECTION = "pushTokens"
    }
}
