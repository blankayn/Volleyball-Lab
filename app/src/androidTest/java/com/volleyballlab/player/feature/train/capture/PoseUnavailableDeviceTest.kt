package com.volleyballlab.player.feature.train.capture

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.volleyballlab.player.pose.PoseDetectorUnavailableException
import com.volleyballlab.player.ui.theme.VolleyballLabTheme
import org.junit.Rule
import org.junit.Test

class PoseUnavailableDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun missingModelAndInitializationFailureHaveDifferentExplanations() {
        var reason by mutableStateOf(PoseDetectorUnavailableException.Reason.ModelMissing)
        compose.setContent { VolleyballLabTheme { UnavailableCard(CaptureUiState.Unavailable(reason, false), {}, {}) } }
        compose.onNodeWithText("Pose model not installed").assertIsDisplayed()
        compose.runOnIdle { reason = PoseDetectorUnavailableException.Reason.InitialisationFailed }
        compose.onNodeWithText("Couldn’t start pose tracking").assertIsDisplayed()
        compose.onNodeWithText("Tracking couldn’t start. Try again or restart the app.").assertIsDisplayed()
    }
}
