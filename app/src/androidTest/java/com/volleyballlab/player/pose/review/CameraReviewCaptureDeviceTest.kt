package com.volleyballlab.player.pose.review

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.volleyballlab.player.data.session.VideoReviewCache
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.feature.train.capture.CameraPreview
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Real hardware binding, shared crop, recorder clock and metadata-only feedback save. */
class CameraReviewCaptureDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun cameraRecordingRetainsFeedbackWithoutReplayInference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        compose.runOnUiThread { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val sessionId = "capture-test-${UUID.randomUUID()}"
        val expectedFile = File(context.filesDir, "training_videos/$sessionId.mp4")
        var recordingId by mutableStateOf<String?>(null)
        val frameSize = AtomicReference<Pair<Int, Int>>()
        val latestCameraMs = AtomicLong(0)
        val recordedMs = AtomicLong(0)
        val finalized = CompletableDeferred<String?>()
        val clock = RecordingVideoClock()
        compose.setContent {
            CameraPreview(CameraSelector.LENS_FACING_FRONT,
                onFrame = { bitmap, timestamp, _ ->
                    frameSize.set(bitmap.width to bitmap.height)
                    latestCameraMs.set(timestamp)
                    bitmap.recycle()
                }, recordingId = recordingId,
                onVideoFinalized = { id, path -> if (id == sessionId) finalized.complete(path) },
                onVideoClock = { _, camera, duration, start ->
                    if (start) clock.started(camera) else clock.observe(camera, duration)
                    recordedMs.set(duration)
                }, modifier = Modifier.fillMaxSize())
        }
        try {
            compose.waitUntil(20_000) { latestCameraMs.get() > 0 }
            compose.runOnIdle { recordingId = sessionId }
            compose.waitUntil(15_000) { recordedMs.get() >= 2_500 }
            compose.runOnIdle { recordingId = null }
            compose.waitForIdle()
            val path = runBlocking { withTimeout(15_000) { finalized.await() } }
            assertNotNull("Three-use-case camera binding must support recording", path)
            val metadata = MediaMetadataRetriever()
            val size: Pair<Int, Int>
            val duration: Long
            try {
                metadata.setDataSource(path)
                duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                val width = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
                val height = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
                val rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)!!.toInt()
                size = if (rotation == 90 || rotation == 270) height to width else width to height
            } finally { metadata.release() }
            val analysisSize = frameSize.get()!!
            assertEquals(size.first.toFloat() / size.second, analysisSize.first.toFloat() / analysisSize.second, .02f)
            val origin = compose.runOnIdle { clock.origin() }
            assertNotNull(origin)
            assertTrue(latestCameraMs.get() - origin!! >= recordedMs.get() - 500)
            val started = System.currentTimeMillis()
            val review = runBlocking {
                val store = VideoReviewCache(context)
                // An empty view must also retain its no-pose outcome without starting a second model pass.
                store.save(path!!, Skill.Receiving, CapturedReviewInput(emptyList(), emptyList(), 0, origin))
                store.read(path, Skill.Receiving)!!
            }
            assertTrue(review.fromCapture)
            assertEquals(duration, review.durationMs)
            assertEquals(size.first, review.width)
            assertEquals(size.second, review.height)
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("camera_capture_result", "duration=$duration video=$size analysis=$analysisSize retained_save_ms=${System.currentTimeMillis() - started}")
            })
        } finally {
            VideoReviewCache.delete(context, expectedFile.path)
            expectedFile.delete()
        }
    }
}
