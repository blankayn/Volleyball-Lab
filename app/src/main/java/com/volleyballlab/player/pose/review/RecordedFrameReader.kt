package com.volleyballlab.player.pose.review

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.FileDescriptor

/** Owns video metadata, sequential sampling and a platform fallback before any frame is emitted. */
class RecordedFrameReader private constructor(
    private val retriever: MediaMetadataRetriever,
    private val sequential: SequentialVideoDecoder?,
    private var pending: SequentialVideoDecoder.Frame?,
    val durationMs: Long,
    val sampleIntervalMs: Long,
    private val maxWidth: Int,
    private val maxHeight: Int,
) : AutoCloseable {
    data class Frame(val timestampMs: Long, val bitmap: Bitmap?)
    val usingSequential: Boolean get() = sequential != null
    private var fallbackTimeMs = 0L

    fun nextFrame(): Frame? {
        if (sequential != null) {
            val frame = pending?.also { pending = null } ?: sequential.nextFrame() ?: return null
            return Frame(frame.timestampMs,frame.bitmap)
        }
        if (fallbackTimeMs >= durationMs) return null
        val time = fallbackTimeMs
        fallbackTimeMs += sampleIntervalMs
        val bitmap = if (Build.VERSION.SDK_INT >= 27) {
            retriever.getScaledFrameAtTime(time * 1000,MediaMetadataRetriever.OPTION_CLOSEST,maxWidth,maxHeight)
        } else {
            retriever.getFrameAtTime(time * 1000,MediaMetadataRetriever.OPTION_CLOSEST)?.let { source ->
                val scale = minOf(1.0,maxWidth.toDouble()/source.width,maxHeight.toDouble()/source.height)
                if (scale == 1.0) source else Bitmap.createScaledBitmap(source,
                    (source.width * scale).toInt().coerceAtLeast(1),
                    (source.height * scale).toInt().coerceAtLeast(1),true).also { source.recycle() }
            }
        }
        return Frame(time,bitmap)
    }

    override fun close() {
        pending?.bitmap?.recycle(); pending = null
        try { sequential?.close() } finally { retriever.release() }
    }

    companion object {
        fun open(path: String, preferSequential: Boolean = true): RecordedFrameReader =
            create(preferSequential,null,640,640,{ it.setDataSource(path) }) { interval ->
                SequentialVideoDecoder.open(path,interval)
            }

        fun open(descriptor: FileDescriptor, offset: Long, length: Long, intervalMs: Long? = null,
                 maxWidth: Int = 640,maxHeight: Int = 640,preferSequential: Boolean = true): RecordedFrameReader =
            create(preferSequential,intervalMs,maxWidth,maxHeight,
                { it.setDataSource(descriptor,offset,length) }) { interval ->
                SequentialVideoDecoder.open(descriptor,offset,length,interval,maxWidth,maxHeight)
            }

        private fun create(preferSequential: Boolean, intervalOverride: Long?, maxWidth: Int,maxHeight: Int,
                           source: (MediaMetadataRetriever) -> Unit,
                           decoder: (Long) -> SequentialVideoDecoder): RecordedFrameReader {
            require(maxWidth > 0 && maxHeight > 0 && (intervalOverride == null || intervalOverride > 0))
            val retriever = MediaMetadataRetriever()
            var sequential: SequentialVideoDecoder? = null
            var first: SequentialVideoDecoder.Frame? = null
            try {
                source(retriever)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?.takeIf { it > 0 } ?: error("Recording has no duration")
                val interval = intervalOverride ?: maxOf(50L,(duration + 11_999) / 12_000)
                if (preferSequential) {
                    try {
                        sequential = decoder(interval)
                        first = sequential.nextFrame()
                        if (first == null) { sequential.close(); sequential = null }
                    } catch (_: Exception) {
                        // Unsupported initial codec/color/image layout: retain the platform path.
                        // A later decode failure propagates; never stitch two decoders mid-action.
                        sequential?.close(); sequential = null
                    }
                }
                return RecordedFrameReader(retriever,sequential,first,duration,interval,maxWidth,maxHeight)
            } catch (error: Throwable) {
                first?.bitmap?.recycle(); sequential?.close(); retriever.release(); throw error
            }
        }
    }
}
