package com.volleyballlab.player.data.session

import com.google.firebase.auth.FirebaseAuth
import com.volleyballlab.player.BuildConfig
import com.volleyballlab.player.data.cloud.awaitCloud
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The app sends Firebase identity to our Worker; R2 credentials never enter the APK. */
class RecordingCloudClient(
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val endpoint: String = BuildConfig.RECORDING_STORAGE_URL,
) {
    suspend fun uploadVideo(sessionId: String, uid: String, video: File) =
        transfer(sessionId, uid, "video.mp4", "PUT", "video/mp4", video.length(), { video.inputStream() }) { }

    suspend fun uploadReview(sessionId: String, uid: String, review: ByteArray) =
        transfer(sessionId, uid, "review.json", "PUT", "application/json", review.size.toLong(), { review.inputStream() }) { }

    suspend fun complete(sessionId: String, uid: String) =
        transfer(sessionId, uid, "complete", "POST") { }

    suspend fun checkAccess(sessionId: String, uid: String) =
        transfer(sessionId, uid, "video.mp4", "HEAD") { }

    suspend fun downloadVideo(sessionId: String, uid: String, destination: File) =
        transfer(sessionId, uid, "video.mp4", "GET") { input ->
            destination.outputStream().use { output -> copyBounded(input, SharedRecordingStore.MAX_VIDEO_BYTES) { bytes, count -> output.write(bytes, 0, count) } }
        }

    suspend fun downloadReview(sessionId: String, uid: String): ByteArray =
        transfer(sessionId, uid, "review.json", "GET") { input ->
            java.io.ByteArrayOutputStream().use { output ->
                copyBounded(input, SharedRecordingStore.MAX_REVIEW_BYTES.toLong()) { bytes, count -> output.write(bytes, 0, count) }
                output.toByteArray()
            }
        }

    private suspend fun <T> transfer(
        sessionId: String, uid: String, file: String, method: String,
        contentType: String? = null, length: Long = 0,
        source: (() -> InputStream)? = null, receive: (InputStream) -> T,
    ): T {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val base = URI(endpoint)
        check(base.scheme == "https" && !base.host.isNullOrBlank() && base.userInfo == null && base.query == null && base.fragment == null) {
            "Cloud recording storage is not configured."
        }
        val user = checkNotNull(firebaseAuth.currentUser) { "Sign in to send or view recordings." }
        check(user.uid == uid) { "Your account changed. Sign in again." }
        val token = checkNotNull(user.getIdToken(false).awaitCloud().token)
        return withContext(Dispatchers.IO) {
            check(firebaseAuth.currentUser?.uid == uid) { "Your account changed. Sign in again." }
            val connection = URI("${endpoint.trimEnd('/')}/recordings/$sessionId/$file").toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000
                connection.readTimeout = 120_000
                connection.setRequestProperty("Authorization", "Bearer $token")
                if (source != null) {
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(length)
                    connection.setRequestProperty("Content-Type", contentType)
                    source().use { input -> connection.outputStream.use { output -> input.copyTo(output) } }
                }
                val status = connection.responseCode
                if (status !in 200..299) {
                    val message = connection.errorStream?.use { input ->
                        val bytes = ByteArray(4096)
                        val count = input.read(bytes)
                        runCatching { JSONObject(String(bytes, 0, count.coerceAtLeast(0), Charsets.UTF_8)).getJSONObject("error").getString("message") }.getOrNull()
                    }
                    throw RecordingTransferException(status, message ?: "Recording transfer failed. Please try again.")
                }
                connection.inputStream.use { input ->
                    val result = receive(input)
                    check(firebaseAuth.currentUser?.uid == uid) { "Your account changed. Sign in again." }
                    result
                }
            } finally { connection.disconnect() }
        }
    }

    private fun copyBounded(input: InputStream, maximum: Long, write: (ByteArray, Int) -> Unit) {
        val bytes = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(bytes)
            if (count < 0) break
            total += count
            check(total <= maximum) { "This cloud recording is too large to download." }
            write(bytes, count)
        }
        check(total > 0) { "This cloud recording is empty. Try again." }
    }
}

class RecordingTransferException(val status: Int, message: String) : java.io.IOException(message)
