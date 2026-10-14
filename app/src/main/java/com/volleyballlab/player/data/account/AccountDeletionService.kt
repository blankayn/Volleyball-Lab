package com.volleyballlab.player.data.account

import android.content.Context
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.volleyballlab.player.data.cloud.awaitCloud
import com.volleyballlab.player.data.session.VideoReviewCache
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.ProfileRepository
import com.volleyballlab.player.domain.repository.SessionRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class AccountDeletionService(
    private val context: Context,
    private val auth: AuthRepository,
    private val profiles: ProfileRepository,
    private val sessions: SessionRepository,
) {
    suspend fun delete(expectedUid: String, password: String?, googleToken: String?) {
        val provider = FirebaseAuth.getInstance()
        val user = checkNotNull(provider.currentUser) { "Sign in again to delete your account." }
        val uid = user.uid
        check(uid == expectedUid) { "Your account changed. Confirm deletion again." }
        val previouslyDeleted = runCatching {
            FirebaseFirestore.getInstance().collection("accountDeletions").document(uid)
                .get(Source.SERVER).awaitCloud().getString("status") == "deleted"
        }.getOrDefault(false)
        val credential = if (googleToken != null) GoogleAuthProvider.getCredential(googleToken, null)
            else EmailAuthProvider.getCredential(checkNotNull(user.email), checkNotNull(password))
        if (!previouslyDeleted) {
            user.reauthenticate(credential).awaitCloud()
            user.getIdToken(true).awaitCloud()
        }
        check(auth.currentUid == uid)
        val videos = sessions.sessions.first().mapNotNull { it.videoPath }
        val photo = profiles.current().photoPath
        withContext(NonCancellable) {
            // expectedUid only binds confirmation to the selected account. The server derives ownership from its token.
            if (!previouslyDeleted) {
                val call = FirebaseFunctions.getInstance("asia-southeast1").getHttpsCallable("deleteAccount")
                call.setTimeout(540, java.util.concurrent.TimeUnit.SECONDS)
                val response = call.call(mapOf("confirmation" to "DELETE", "expectedUid" to uid)).awaitCloud()
                check((response.data as? Map<*, *>)?.get("deleted") == true) { "Deletion was not confirmed." }
            }
            clearLocalAccount(context, uid)
            withContext(Dispatchers.IO) {
                val videoRoot = File(context.filesDir, "training_videos").canonicalFile
                videos.forEach { path ->
                    val file = File(path).canonicalFile
                    if (file.parentFile == videoRoot) { VideoReviewCache.delete(context, path); file.delete() }
                }
                photo?.let { path ->
                    val file = File(path).canonicalFile
                    if (file.parentFile == File(context.filesDir, "profile_photos").canonicalFile) file.delete()
                }
                val sharedRoot = File(context.cacheDir, "shared_recordings").canonicalFile
                val shared = File(sharedRoot, uid).canonicalFile
                if (shared.parentFile == sharedRoot) shared.deleteRecursively()
            }
            if (auth.currentUid == uid) auth.signOut()
        }
    }
}
