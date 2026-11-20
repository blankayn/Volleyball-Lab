package com.volleyballlab.player.pose.review

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Device-local frames only. Cache keys include source identity, not just its path. */
object ReviewStillLoader {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    suspend fun load(path: String, timeMs: Long): Bitmap? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile) return@withContext null
        val key = "$path:${file.length()}:${file.lastModified()}:$timeMs"
        cache.get(key)?.let { return@withContext it }
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(path)
            val frame = if (android.os.Build.VERSION.SDK_INT >= 27)
                reader.getScaledFrameAtTime(timeMs * 1_000, MediaMetadataRetriever.OPTION_CLOSEST, 720, 720)
            else reader.getFrameAtTime(timeMs * 1_000, MediaMetadataRetriever.OPTION_CLOSEST)
            frame?.let {
                val scale = minOf(1f, 720f / maxOf(it.width, it.height))
                val result = if (scale < 1f) Bitmap.createScaledBitmap(it, (it.width * scale).toInt(), (it.height * scale).toInt(), true)
                    .also { _ -> it.recycle() } else it
                cache.put(key, result)
                result
            }
        } catch (_: Exception) { null }
        finally { reader.release() }
    }
}
