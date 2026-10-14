package com.volleyballlab.player.feature.train.capture

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.volleyballlab.player.pose.analysis.LivePostureUi
import com.volleyballlab.player.ui.theme.LocalReduceMotion
import com.volleyballlab.player.ui.theme.VolleyballLabTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CaptureCountdownDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun countdownStaysAtCameraCentreAsDigitsChangeInPortrait() {
        verifyCountdownCentre(300.dp, 520.dp, "countdown-centred-portrait.png")
    }

    @Test fun countdownStaysAtCameraCentreInWideViewfinder() {
        verifyCountdownCentre(320.dp, 240.dp, "countdown-centred-wide.png")
    }

    private fun verifyCountdownCentre(width: Dp, height: Dp, screenshot: String) {
        val seconds = mutableStateOf(3)
        compose.setContent {
            VolleyballLabTheme {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CaptureControls(
                            state = CaptureUiState.CountingDown(seconds.value),
                            livePosture = LivePostureUi.Idle,
                            coachContext = LiveCoachContext(),
                            guidedOptions = GuidedCaptureOptions(),
                            coachingText = null,
                            voiceMuted = false,
                            audioUnavailable = false,
                            onToggleMute = {}, onSpeechSettings = {},
                            onGuidedChanged = {}, onRepTargetChanged = {},
                            onRetryAngle = {}, onSkipAngle = {},
                            onStart = {}, onStop = {}, onCancelCountdown = {},
                            onRetry = {}, onUseDemo = {}, onSwitchCamera = {}, onBack = {},
                            modifier = Modifier.size(width, height).testTag("capture-root"),
                        )
                    }
                }
            }
        }
        for (digit in 3 downTo 1) {
            compose.runOnIdle { seconds.value = digit }
            compose.waitForIdle()
            val viewport = compose.onNodeWithTag("capture-root").fetchSemanticsNode().boundsInRoot
            val number = compose.onNodeWithText(digit.toString()).fetchSemanticsNode().boundsInRoot
            assertEquals("Countdown horizontal centre", viewport.center.x, number.center.x, 1f)
            assertEquals("Countdown vertical centre", viewport.center.y, number.center.y, 1f)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, screenshot).outputStream().use {
            compose.onNodeWithTag("capture-root").captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
