package com.volleyballlab.player.pose.review

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import com.volleyballlab.player.data.session.VideoReviewCache
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.feature.train.result.AnnotatedSessionVideo
import com.volleyballlab.player.pose.MediaPipePoseDetector
import com.volleyballlab.player.pose.model.PoseFrame
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in device smoke test. Uses a disposable copy of an existing on-device clip. */
class RecordedVideoReviewDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Exercises the new live-pose save path; never runs full MP4 replay inference. */
    @Test fun capturedFeedbackOpensImmediatelyAndSurvivesLegacyCacheRemoval() {
        compose.runOnUiThread { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, "training_videos").listFiles()
            ?.filter { it.extension == "mp4" && it.length() > 0 }?.minByOrNull { it.length() }
        assumeTrue("Requires an existing private training video", source != null)
        val clip = File(context.cacheDir, "captured-review-device-test.mp4")
        source!!.copyTo(clip, overwrite = true)
        var reviewSession by mutableStateOf<SessionResult?>(null)
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                    val session = reviewSession
                    if (session == null) Text("Checking automatic video feedback. Please keep this screen open.")
                    else AnnotatedSessionVideo(session, clip.path)
                }
            }
        }
        compose.waitForIdle()
        try {
            val frames = mutableListOf<PoseFrame>()
            val sensorOrigin = 100_000L
            // Only three fixture images are submitted to the production live-stream detector.
            // This simulates the already-detected camera poses, not a second review analysis.
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) {
                    val detector = MediaPipePoseDetector.create(context)
                    val metadata = MediaMetadataRetriever()
                    try {
                        metadata.setDataSource(clip.path)
                        val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                        for (time in listOf(duration / 4, duration / 2, duration * 3 / 4)) {
                            val decoded = metadata.getFrameAtTime(time * 1_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
                            val bitmap = if (decoded.config == Bitmap.Config.ARGB_8888) decoded
                                else decoded.copy(Bitmap.Config.ARGB_8888, false)!!
                            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                                withTimeoutOrNull(5_000) { detector.poses.first { it.cameraTimestampMs == sensorOrigin + time } }
                            }
                            detector.submit(bitmap, sensorOrigin + time, false)
                            pending.await()?.let(frames::add)
                            if (bitmap !== decoded) bitmap.recycle()
                            decoded.recycle()
                        }
                    } finally { metadata.release(); detector.close() }
                }
            }
            assertTrue("Fixture must contain detected real MediaPipe poses", frames.isNotEmpty())
            val cache = VideoReviewCache(context)
            val started = System.currentTimeMillis()
            val review = runBlocking {
                cache.save(clip.path, Skill.Receiving, CapturedReviewInput(frames, emptyList(), 0, sensorOrigin))
                // Delete only this fixture's disposable cache: durable feedback must remain.
                val key = java.security.MessageDigest.getInstance("SHA-256").digest(clip.path.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                File(context.cacheDir, "video_review/$key.json").delete()
                VideoReviewCache(context).read(clip.path, Skill.Receiving)!!
            }
            val savedMs = System.currentTimeMillis() - started
            assertTrue(review.fromCapture)
            assertEquals(frames.size, review.frames.size)
            assertTrue("Metadata and retained feedback should not take a full video-analysis pass", savedMs < 5_000)
            assertNull(runBlocking { cache.read(clip.path, Skill.Setting) })
            assumeTrue("Test activity must stay in foreground", compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            val displayStarted = System.currentTimeMillis()
            compose.runOnIdle { reviewSession = SessionResult("captured-review-device-test", Skill.Receiving, "test", 0, review.durationMs, emptyList(), clip.path) }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Show skeleton").fetchSemanticsNodes().isNotEmpty() }
            val displayMs = System.currentTimeMillis() - displayStarted
            assertTrue(compose.onAllNodesWithText("Analyzing this older recording", substring = true).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithContentDescription("Recording timeline with correction markers")
                .performSemanticsAction(SemanticsActions.SetProgress) { it(review.frames.first().timestampMs.toFloat()) }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("Tracked body skeleton with highlighted joints for measured form corrections", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Play").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Pause").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Pause").performClick()
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("captured_review_result", "tracked=${frames.size} retained_save_ms=$savedMs ready_ui_ms=$displayMs")
            })
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "captured-review-device.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        } finally { VideoReviewCache.delete(context, clip.path); clip.delete() }
    }

    @Test fun savedRecordingDecodesTracksCachesAndDisplaysSkeleton() {
        compose.runOnUiThread { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, "training_videos").listFiles()
            ?.filter { it.extension == "mp4" && it.length() > 0 }?.minByOrNull { it.length() }
        assumeTrue("Requires a previously recorded private training video", source != null)
        val clip = File(context.cacheDir, "video-review-device-test.mp4")
        source!!.copyTo(clip, overwrite = true)
        var reviewSession by mutableStateOf<SessionResult?>(null)
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                    val session = reviewSession
                    if (session == null) Text("Checking your recording. Please keep this screen open.")
                    else AnnotatedSessionVideo(session, clip.path)
                }
            }
        }
        compose.waitForIdle()
        try {
            val started = System.currentTimeMillis()
            val review = runBlocking { RecordedVideoAnalyzer(context).analyse(clip.path, Skill.Receiving) { } }
            val tracked = review.frames.count { it.points.size == 33 }
            Log.i("VideoReviewDeviceTest", "duration=${review.durationMs} size=${review.width}x${review.height} samples=${review.frames.size} tracked=$tracked issues=${review.issues.size} elapsed=${System.currentTimeMillis() - started}")
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("review_result", "duration=${review.durationMs} size=${review.width}x${review.height} samples=${review.frames.size} tracked=$tracked issues=${review.issues.size} elapsed=${System.currentTimeMillis() - started}")
            })
            assertTrue(review.width > 0 && review.height > 0)
            assertTrue(review.frames.isNotEmpty())
            assertTrue("Existing clip must contain at least one tracked pose", tracked > 0)
            assertTrue(review.frames.zipWithNext().all { (a, b) -> a.timestampMs < b.timestampMs })
            val cache = VideoReviewCache(context)
            runBlocking {
                cache.write(clip.path, Skill.Receiving, review)
                assertEquals(review, cache.read(clip.path, Skill.Receiving))
                assertNull(cache.read(clip.path, Skill.Serving))
            }
            assumeTrue("Leave the test activity in the foreground for the playback check",
                compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            val session = SessionResult("video-review-device-test", Skill.Receiving, "device-test", 0, review.durationMs, emptyList(), clip.path)
            compose.runOnIdle { reviewSession = session }
            compose.waitForIdle()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Show skeleton").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Play").assertExists()
            val poseTime = review.frames.first { it.points.size == 33 }.timestampMs
            compose.onNodeWithContentDescription("Recording timeline with correction markers")
                .performSemanticsAction(SemanticsActions.SetProgress) { it(poseTime.toFloat()) }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription("Tracked body skeleton with highlighted joints for measured form corrections", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Play").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Pause").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Pause").performClick()
            compose.waitForIdle()
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "video-review-device.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        } finally {
            VideoReviewCache.delete(context, clip.path)
            clip.delete()
        }
    }
}
