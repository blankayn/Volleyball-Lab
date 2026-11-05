package com.volleyballlab.player.feature.train.capture

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.pose.analysis.LivePostureUi
import com.volleyballlab.player.pose.quality.PoseQuality
import com.volleyballlab.player.ui.theme.LocalReduceMotion
import com.volleyballlab.player.ui.theme.VolleyballLabTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Layout and action checks use a drawn court, without camera or account access. */
class LiveSessionLayoutDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val state = mutableStateOf<CaptureUiState>(rotation())
    private var cameraMounts = 0
    private var cameraDisposals = 0
    private var retries = 0
    private var skips = 0
    private var finishes = 0
    private var mutes = 0
    private var switches = 0

    @Test fun rotationLayoutKeepsCameraUncoveredAndAllActionsVisible() {
        show()
        compose.onNodeWithText("Side View").assertIsDisplayed()
        compose.onNodeWithText("5 / 5 reps").assertIsDisplayed()
        compose.onNodeWithText("Good reps").assertIsDisplayed()
        val camera = compose.onNodeWithTag("capture-viewfinder").fetchSemanticsNode().boundsInRoot
        val image = compose.onNodeWithTag("capture-camera-content").fetchSemanticsNode().boundsInRoot
        assertEquals("Preview must preserve the full capture viewport", 0.75f, image.width / image.height, 0.01f)
        val instruction = compose.onNodeWithText(DIRECTION).fetchSemanticsNode().boundsInRoot
        val finish = compose.onNodeWithText("Finish Session").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Guidance must be below the camera", instruction.top >= camera.bottom)
        assertTrue("Finish must be below guidance", finish.top > instruction.bottom)
        assertTrue("Keep a useful camera viewport", camera.height >= 120 * compose.density.density)
        compose.onNodeWithText("Retry").assertIsDisplayed().performClick()
        compose.onNodeWithText("Skip Angle").assertIsDisplayed().performClick()
        compose.onNodeWithText("Finish Session").performClick()
        compose.onNodeWithContentDescription("Mute voice coaching").performClick()
        compose.runOnIdle {
            assertEquals(1, retries); assertEquals(1, skips)
            assertEquals(1, finishes); assertEquals(1, mutes)
        }
        screenshot("live-session-rotation.png")
    }

    @Test fun compactPhoneWithLargerTextKeepsFinishVisible() {
        show(compact = true, fontScale = 1.3f)
        compose.onNodeWithText("Finish Session").assertIsDisplayed()
        val camera = compose.onNodeWithTag("capture-viewfinder").fetchSemanticsNode().boundsInRoot
        assertTrue("Large text must not collapse the camera", camera.height >= 120 * compose.density.density)
        // Capture the window directly: Compose's region redraw helper times out
        // for this smaller-than-window root on the API 36 emulator.
        screenshot("live-session-compact.png", entireWindow = true)
    }

    @Test fun stateChangesDoNotRemountCameraAndFramingRetainsLensControl() {
        state.value = CaptureUiState.Framing(PoseQuality.NotYetKnown)
        show()
        compose.onNodeWithContentDescription("Switch camera").performClick()
        compose.runOnIdle { assertEquals(1, switches); state.value = CaptureUiState.CountingDown(3) }
        compose.runOnIdle { state.value = rotation().copy(rotation = null) }
        compose.onNodeWithContentDescription("Switch camera").assertDoesNotExist()
        compose.onNodeWithText("Finish Session").assertIsDisplayed()
        screenshot("live-session-recording.png")
        compose.runOnIdle { state.value = rotation() }
        compose.runOnIdle {
            assertEquals("Session updates must retain camera resources", 1, cameraMounts)
            assertEquals(0, cameraDisposals)
        }
    }

    private fun show(compact: Boolean = false, fontScale: Float = 1f) {
        compose.setContent {
            VolleyballLabTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalReduceMotion provides true, LocalDensity provides Density(density.density, fontScale)) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF10151D))) {
                        CaptureControls(
                            state = state.value, livePosture = LivePostureUi.Idle,
                            coachContext = LiveCoachContext(), guidedOptions = GuidedCaptureOptions(),
                            coachingText = null, voiceMuted = false, audioUnavailable = false,
                            onToggleMute = { mutes++ }, onSpeechSettings = {},
                            onGuidedChanged = {}, onRepTargetChanged = {},
                            onRetryAngle = { retries++ }, onSkipAngle = { skips++ },
                            onStart = {}, onStop = { finishes++ }, onCancelCountdown = {},
                            onRetry = {}, onUseDemo = {}, onSwitchCamera = { switches++ }, onBack = {},
                            modifier = (if (compact) Modifier.size(320.dp, 640.dp) else Modifier.fillMaxSize()).testTag("live-session-root"),
                            cameraContent = {
                                DisposableEffect(Unit) { cameraMounts++; onDispose { cameraDisposals++ } }
                                CourtPreview()
                            },
                        )
                    }
                }
            }
        }
    }

    private fun screenshot(name: String, entireWindow: Boolean = false) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bitmap = if (entireWindow) requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            else compose.onNodeWithTag("live-session-root").captureToImage().asAndroidBitmap()
        File(context.cacheDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    companion object {
        private const val DIRECTION = "Turn sideways with your right hitting arm nearest the camera."
        private fun rotation() = CaptureUiState.Recording(
            sessionId = "layout-preview", elapsedMs = 46_000, liveRepCount = 5, quality = PoseQuality.NotYetKnown,
            stageMetrics = LiveCaptureMetrics(reps = 5), totalMetrics = LiveCaptureMetrics(reps = 5),
            rotation = RotationUi(CameraAngle.Side, 1, message = DIRECTION, instruction = DIRECTION),
        )
    }
}

/** A synthetic court and skeleton for layout screenshots, never used in training. */
@androidx.compose.runtime.Composable
private fun CourtPreview() {
    Canvas(Modifier.fillMaxSize().background(Color(0xFF314A45))) {
        val w = size.width
        val h = size.height
        val line = Color(0xFFB4C9BB).copy(alpha = 0.3f)
        drawLine(line, Offset(0f, h * .7f), Offset(w, h * .7f), 3f)
        drawLine(line, Offset(w * .2f, 0f), Offset(0f, h), 3f)
        drawLine(line, Offset(w * .8f, 0f), Offset(w, h), 3f)
        val points = listOf(.4f to .32f, .6f to .32f, .38f to .48f, .62f to .48f,
            .36f to .62f, .64f to .62f, .43f to .59f, .57f to .59f, .37f to .76f,
            .63f to .76f, .34f to .91f, .66f to .91f)
        val edges = listOf(0 to 1, 0 to 2, 2 to 4, 1 to 3, 3 to 5, 0 to 6, 1 to 7,
            6 to 7, 6 to 8, 8 to 10, 7 to 9, 9 to 11)
        fun at(i: Int) = Offset(points[i].first * w, points[i].second * h)
        drawCircle(Color(0xFF627970), w * .055f, Offset(w * .5f, h * .22f))
        edges.forEach { (a, b) -> drawLine(Color.White, at(a), at(b), 3.dp.toPx(), StrokeCap.Round) }
        points.indices.forEach { i -> drawCircle(Color.White, 5.dp.toPx(), at(i)); drawCircle(Color(0xFF8BD3C0), 3.dp.toPx(), at(i)) }
    }
}
