package com.volleyballlab.player.pose.review

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.FileDescriptor

/** Sequential SDR decoder: never seeks/redecodes the same GOP for each pose. */
class SequentialVideoDecoder private constructor(
    private val extractor: MediaExtractor,
    private val codec: MediaCodec,
    private val rotation: Int,
    private val maxWidth: Int,
    private val maxHeight: Int,
    val durationMs: Long,
    private val sampleIntervalMs: Long,
) : AutoCloseable {
    data class Frame(val timestampMs: Long, val bitmap: Bitmap)
    private val info = MediaCodec.BufferInfo()
    private var inputEnded = false
    private var outputEnded = false
    private var nextSampleMs = 0L
    private var previousTimestampUs = -1L
    private var colorStandard = 0
    private var fullRange = false
    private var useBt709 = false

    fun nextFrame(): Frame? {
        var progressedAt = SystemClock.elapsedRealtime()
        while (!outputEnded) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Video decode cancelled")
            if (!inputEnded) {
                val input = codec.dequeueInputBuffer(0)
                if (input >= 0) {
                    val buffer = checkNotNull(codec.getInputBuffer(input)); buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    progressedAt = SystemClock.elapsedRealtime()
                }
            }
            when (val output = codec.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    colorStandard = if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD))
                        format.getInteger(MediaFormat.KEY_COLOR_STANDARD) else 0
                    fullRange = format.containsKey(MediaFormat.KEY_COLOR_RANGE) &&
                        format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
                    useBt709 = requireNotNull(SdrColorStandard.bt709(colorStandard)) { "Unsupported SDR color standard $colorStandard" }
                    progressedAt = SystemClock.elapsedRealtime()
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> check(SystemClock.elapsedRealtime() - progressedAt < 5_000) { "Video decoder stalled" }
                else -> if (output >= 0) {
                    progressedAt = SystemClock.elapsedRealtime()
                    try {
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (info.size <= 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) continue
                        val timestampUs = info.presentationTimeUs
                        check(timestampUs >= previousTimestampUs) { "Video presentation timestamps reset" }
                        previousTimestampUs = timestampUs
                        val timestampMs = (timestampUs / 1000).coerceAtLeast(0)
                        if (timestampMs < nextSampleMs) continue
                        nextSampleMs = timestampMs + sampleIntervalMs - timestampMs % sampleIntervalMs
                        val image = checkNotNull(codec.getOutputImage(output)) { "Decoder has no readable YUV image" }
                        try {
                            require(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3) { "Unsupported decoded pixel format" }
                            val crop = image.cropRect
                            val planes = image.planes.map { Yuv420Rgb.Plane(it.buffer, it.rowStride, it.pixelStride) }
                            val rgb = Yuv420Rgb.convert(planes[0], planes[1], planes[2], crop.left, crop.top,
                                crop.width(), crop.height(), rotation, maxWidth, maxHeight,
                                useBt709, fullRange)
                            return Frame(timestampMs, Bitmap.createBitmap(rgb.argb, rgb.width, rgb.height, Bitmap.Config.ARGB_8888))
                        } finally { image.close() }
                    } finally { codec.releaseOutputBuffer(output, false) }
                }
            }
        }
        return null
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        extractor.release()
    }

    companion object {
        fun open(path: String, sampleIntervalMs: Long, maxWidth: Int = 640, maxHeight: Int = 640): SequentialVideoDecoder =
            create(sampleIntervalMs, maxWidth, maxHeight) { it.setDataSource(path) }

        fun open(descriptor: FileDescriptor, offset: Long, length: Long, sampleIntervalMs: Long,
                 maxWidth: Int = 640, maxHeight: Int = 640): SequentialVideoDecoder =
            create(sampleIntervalMs, maxWidth, maxHeight) { it.setDataSource(descriptor, offset, length) }

        private fun create(interval: Long, maxWidth: Int, maxHeight: Int, source: (MediaExtractor) -> Unit): SequentialVideoDecoder {
            require(interval > 0 && maxWidth > 0 && maxHeight > 0)
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                source(extractor)
                val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
                require(rotation in listOf(0,90,180,270)) { "Unsupported display rotation" }
                if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                    require(format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) !in listOf(MediaFormat.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_TRANSFER_HLG)) { "HDR needs platform tone mapping" }
                }
                format.setInteger(MediaFormat.KEY_ROTATION, 0)
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                val duration = format.getLong(MediaFormat.KEY_DURATION) / 1000
                require(duration > 0) { "Video has no positive duration" }
                codec = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
                codec.configure(format, null, null, 0); codec.start()
                return SequentialVideoDecoder(extractor, codec, rotation, maxWidth, maxHeight, duration, interval)
            } catch (error: Throwable) {
                runCatching { codec?.release() }; extractor.release(); throw error
            }
        }
    }
}
