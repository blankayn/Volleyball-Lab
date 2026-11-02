package com.volleyballlab.player.pose

import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.volleyballlab.player.pose.review.SequentialVideoDecoder
import com.volleyballlab.player.pose.review.RecordedFrameReader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class SequentialVideoDecoderDeviceTest {
    @Test fun productionReaderUsesSequentialSamplingAndRetainsAnExplicitPlatformFallback() {
        val context=InstrumentationRegistry.getInstrumentation().context
        context.assets.openFd("blocking_candidate/public-replay.mp4").use { asset ->
            for (preferSequential in listOf(true,false)) {
                RecordedFrameReader.open(asset.fileDescriptor,asset.startOffset,asset.length,1,480,640,preferSequential).use { reader ->
                    assertEquals(preferSequential,reader.usingSequential)
                    assertTrue(reader.durationMs>0)
                    assertEquals(1L,reader.sampleIntervalMs)
                    val first=requireNotNull(reader.nextFrame())
                    assertEquals(0L,first.timestampMs)
                    val bitmap=requireNotNull(first.bitmap)
                    assertTrue(bitmap.width in 1..480 && bitmap.height in 1..640)
                    bitmap.recycle()
                }
            }
        }
    }

    @Test fun scaledPublicFrameKeepsPlatformAspectAndColors() {
        if (Build.VERSION.SDK_INT < 27) return
        val context = InstrumentationRegistry.getInstrumentation().context
        context.assets.openFd("blocking_candidate/public-replay.mp4").use { asset ->
            val reference = MediaMetadataRetriever()
            try {
                reference.setDataSource(asset.fileDescriptor,asset.startOffset,asset.length)
                val expected = requireNotNull(reference.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST,480,640))
                SequentialVideoDecoder.open(asset.fileDescriptor,asset.startOffset,asset.length,1,480,640).use { decoder ->
                    val actual = requireNotNull(decoder.nextFrame()).bitmap
                    assertEquals(expected.width,actual.width); assertEquals(expected.height,actual.height)
                    var error = 0L
                    for (y in 0 until expected.height) for (x in 0 until expected.width) {
                        val a=expected.getPixel(x,y); val b=actual.getPixel(x,y)
                        for (shift in listOf(0,8,16)) error += abs((a shr shift and 255)-(b shr shift and 255))
                    }
                    val mean=error.toDouble()/(expected.width*expected.height*3)
                    assertTrue("Scaled public pixels differ materially: mean byte error $mean",mean<12)
                    expected.recycle(); actual.recycle()
                }
            } finally { reference.release() }
        }
    }

    @Test fun displayRotationAndColorMatchThePlatformDecoder() {
        val context = InstrumentationRegistry.getInstrumentation().context
        for (rotation in listOf(0,90,180,270)) {
            context.assets.openFd("video_decode/rotation$rotation.mp4").use { asset ->
                val reference = MediaMetadataRetriever()
                try {
                    reference.setDataSource(asset.fileDescriptor, asset.startOffset, asset.length)
                    val expected = requireNotNull(reference.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST))
                    SequentialVideoDecoder.open(asset.fileDescriptor, asset.startOffset, asset.length, 1).use { decoder ->
                        val first = requireNotNull(decoder.nextFrame())
                        assertEquals(expected.width, first.bitmap.width); assertEquals(expected.height, first.bitmap.height)
                        var error = 0L
                        for (y in 0 until expected.height) for (x in 0 until expected.width) {
                            val a = expected.getPixel(x,y); val b = first.bitmap.getPixel(x,y)
                            for (shift in listOf(0,8,16)) error += abs((a shr shift and 255) - (b shr shift and 255))
                        }
                        val mean = error.toDouble() / (expected.width * expected.height * 3)
                        assertTrue("Rotation $rotation or YUV colors differ: mean byte error $mean", mean < 12)
                        first.bitmap.recycle(); expected.recycle()
                        val times = mutableListOf(first.timestampMs)
                        while (true) {
                            val frame = decoder.nextFrame() ?: break
                            times += frame.timestampMs; frame.bitmap.recycle()
                        }
                        assertEquals(10,times.size)
                        assertTrue(times.zipWithNext().all { (a,b) -> b>a })
                    }
                } finally { reference.release() }
            }
        }
    }

    @Test fun missingEncodedFramesKeepTheirRealPresentationGap() {
        val context = InstrumentationRegistry.getInstrumentation().context
        context.assets.openFd("video_decode/missing-frame.mp4").use { asset ->
            SequentialVideoDecoder.open(asset.fileDescriptor, asset.startOffset, asset.length, 1).use { decoder ->
                val times = mutableListOf<Long>()
                while (true) {
                    val frame = decoder.nextFrame() ?: break
                    times += frame.timestampMs; frame.bitmap.recycle()
                }
                assertEquals(9,times.size)
                assertTrue(times.zipWithNext().all { (a,b) -> b>a })
                assertTrue(times.zipWithNext().any { (a,b) -> b-a>=150 })
            }
        }
    }
}
