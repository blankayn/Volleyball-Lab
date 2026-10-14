package com.volleyballlab.player.data.session

import android.content.Context
import android.util.AtomicFile
import android.media.MediaMetadataRetriever
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.review.VideoReview
import com.volleyballlab.player.pose.review.CapturedReviewInput
import com.volleyballlab.player.pose.review.CapturedReviewWriter
import com.volleyballlab.player.pose.review.toReview
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Durable capture feedback plus a legacy analysis cache, outside cloud records and backup. */
class VideoReviewCache(private val context: Context) : CapturedReviewWriter {
    @Serializable
    private data class Record(
        val version: Int,
        val length: Long,
        val modified: Long,
        val skill: String,
        val review: VideoReview,
    )

    suspend fun read(path: String, skill: Skill): VideoReview? = withContext(Dispatchers.IO) {
        listOf(true, false).firstNotNullOfOrNull { persistent -> runCatching {
            val source = File(path)
            val record = json.decodeFromString<Record>(AtomicFile(fileFor(context, path, persistent)).openRead().bufferedReader().use { it.readText() })
            record.takeIf {
                it.version == VERSION && it.length == source.length() && it.modified == source.lastModified() &&
                    it.skill == skill.name && it.review.width > 0 && it.review.height > 0
            }?.review
        }.getOrNull() }
    }

    override suspend fun save(path: String, skill: Skill, input: CapturedReviewInput) {
        val review = withContext(Dispatchers.IO) {
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(path)
                val duration = requireNotNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull())
                val rawWidth = requireNotNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull())
                val rawHeight = requireNotNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull())
                val rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val rotated = rotation == 90 || rotation == 270
                input.toReview(duration, if (rotated) rawHeight else rawWidth, if (rotated) rawWidth else rawHeight)
            } finally { metadata.release() }
        }
        write(path, skill, review, persistent = true)
    }

    suspend fun write(path: String, skill: Skill, review: VideoReview, persistent: Boolean = false) = withContext(Dispatchers.IO) {
        val source = File(path)
        val file = fileFor(context, path, persistent)
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(json.encodeToString(Record(VERSION, source.length(), source.lastModified(), skill.name, review)).toByteArray())
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    companion object {
        private const val VERSION = 1
        private val json = Json { ignoreUnknownKeys = true }
        private fun fileFor(context: Context, path: String, persistent: Boolean = false): File {
            val key = MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
            return if (persistent) File(context.filesDir, "video_reviews/$key.json") else File(context.cacheDir, "video_review/$key.json")
        }
        fun delete(context: Context, path: String) {
            AtomicFile(fileFor(context, path)).delete()
            AtomicFile(fileFor(context, path, persistent = true)).delete()
        }
    }
}
