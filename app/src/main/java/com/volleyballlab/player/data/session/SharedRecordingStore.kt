package com.volleyballlab.player.data.session

import android.content.Context
import android.net.Uri
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.volleyballlab.player.data.cloud.CloudSchema
import com.volleyballlab.player.data.cloud.awaitCloud
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.pose.review.VideoReview
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Private SDK transfers, without public download URLs or token-bearing links. */
class SharedRecordingStore(
    private val context: Context,
    private val auth: AuthRepository,
    private val db: FirebaseFirestore,
    private val cache: VideoReviewCache,
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun upload(session: SessionResult, uid: String) {
        val path = session.videoPath ?: return
        check(auth.currentUid == uid) { "Your account changed. Sign in again." }
        val video = File(path)
        check(video.isFile) { "This recording is no longer on this phone." }
        require(video.length() in 1..MAX_VIDEO_BYTES) { "The video is too large to send. Record a shorter session." }
        // New captures already have feedback. Legacy recordings require playback analysis first.
        val review = cache.read(path, session.skill)
            ?: com.volleyballlab.player.pose.review.RecordedVideoAnalyzer(context).analyse(path, session.skill) {}.also {
                cache.write(path, session.skill, it)
            }
        val reviewBytes = withContext(Dispatchers.IO) { json.encodeToString(review).toByteArray() }
        require(reviewBytes.size <= MAX_REVIEW_BYTES)
        val ref = db.collection(CloudSchema.SESSIONS).document(session.id)
        if (!ref.get(Source.SERVER).awaitCloud().exists())
            ref.set(CloudSchema.sessionDoc(session, uid, emptyList(), System.currentTimeMillis())).awaitCloud()
        val prefix = "recordings/$uid/${session.id}"
        storage.reference.child("$prefix/video.mp4").putFile(Uri.fromFile(video),
            StorageMetadata.Builder().setContentType("video/mp4").build()).awaitCloud()
        check(auth.currentUid == uid)
        storage.reference.child("$prefix/review.json").putBytes(reviewBytes,
            StorageMetadata.Builder().setContentType("application/json").build()).awaitCloud()
        check(auth.currentUid == uid)
        // The ready marker is written only after both objects completed. Retry uses the same paths.
        db.collection("recordings").document(session.id).set(mapOf("ownerUid" to uid,
            "sessionId" to session.id, "version" to 1, "videoBytes" to video.length(),
            "reviewBytes" to reviewBytes.size, "ready" to true)).awaitCloud()
    }

    suspend fun download(session: SessionResult, ownerUid: String): String? {
        val viewer = checkNotNull(auth.currentUid)
        val marker = db.collection("recordings").document(session.id).get(Source.SERVER).awaitCloud()
        if (!marker.exists()) return null
        check(marker.getString("ownerUid") == ownerUid && marker.getBoolean("ready") == true)
        // Server authorization is checked even when this device has a cached copy.
        val row = db.collection(CloudSchema.SESSIONS).document(session.id).get(Source.SERVER).awaitCloud()
        check(row.getString("ownerUid") == ownerUid &&
            (viewer == ownerUid || viewer in (row.get("sharedWith") as? List<*>).orEmpty()))
        require(ownerUid.matches(SAFE_ID) && session.id.matches(SAFE_ID) && viewer.matches(SAFE_ID))
        val local = File(context.cacheDir, "shared_recordings/$viewer/$ownerUid/${session.id}.mp4")
        val prefix = "recordings/$ownerUid/${session.id}"
        withContext(Dispatchers.IO) { local.parentFile?.mkdirs() }
        if (!local.isFile || cache.read(local.path, session.skill) == null) {
            val temporary = File(local.parentFile, "${session.id}.partial")
            try {
                val metadata = storage.reference.child("$prefix/video.mp4").metadata.awaitCloud()
                require(metadata.sizeBytes in 1..MAX_VIDEO_BYTES)
                storage.reference.child("$prefix/video.mp4").getFile(temporary).awaitCloud()
                val bytes = storage.reference.child("$prefix/review.json").getBytes(MAX_REVIEW_BYTES.toLong()).awaitCloud()
                val review = withContext(Dispatchers.IO) { json.decodeFromString<VideoReview>(bytes.decodeToString()) }
                require(review.width > 0 && review.height > 0)
                check(auth.currentUid == viewer)
                withContext(Dispatchers.IO) { check(temporary.renameTo(local)) }
                cache.write(local.path, session.skill, review)
            } finally { withContext(Dispatchers.IO) { temporary.delete() } }
        }
        check(auth.currentUid == viewer)
        return local.path
    }

    companion object {
        const val MAX_VIDEO_BYTES = 100L * 1024 * 1024
        const val MAX_REVIEW_BYTES = 10 * 1024 * 1024
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
