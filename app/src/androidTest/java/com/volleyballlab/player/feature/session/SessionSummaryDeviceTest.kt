package com.volleyballlab.player.feature.session

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.ui.theme.LocalReduceMotion
import com.volleyballlab.player.ui.theme.VolleyballLabTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Verifies the student-facing saved-session flow without changing account or session data. */
class SessionSummaryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun recentSessionOpensSummaryWithMeasuredFeedbackAndReturns() {
        val session = fixture()
        var selected by mutableStateOf(false)
        var feedbackOpened = 0
        compose.setContent {
            VolleyballLabTheme {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    if (selected) SessionSummaryContent(session, "Forearm passing practice",
                        onBack = { selected = false }, onOpenFeedback = { feedbackOpened++ },
                        contentPadding = PaddingValues(12.dp))
                    else Box(Modifier.fillMaxSize().padding(20.dp)) {
                        RecentSessionSummaryCard(session, { selected = true })
                    }
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.session_card_view_summary)).performClick()
        compose.onNodeWithText(context.getString(R.string.session_summary_title)).assertIsDisplayed()
        compose.onAllNodesWithText("72%").assertCountEquals(2)
        capture("session-summary-device.png")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(context.getString(R.string.cue_pass_knees_tip)))
        compose.onNodeWithText(context.getString(R.string.cue_pass_knees_tip)).assertIsDisplayed()
        capture("session-summary-feedback-device.png")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(context.getString(R.string.session_summary_full_feedback)))
        compose.onNodeWithText(context.getString(R.string.session_summary_full_feedback)).performClick()
        compose.runOnIdle { assertEquals(1, feedbackOpened) }
        compose.onNode(hasScrollAction()).performScrollToIndex(0)
        compose.onNodeWithText(context.getString(R.string.session_summary_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.session_card_view_summary)).assertIsDisplayed()
    }

    @Test fun unreadableSessionShowsNoEstimateAndNoInventedCorrection() {
        show(fixture(unmeasured = true))
        compose.onNodeWithText(context.getString(R.string.session_summary_unassessed)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.session_summary_no_estimate)).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(context.getString(R.string.session_summary_improve)))
        compose.onNodeWithText(context.getString(R.string.session_summary_priority)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.cue_pass_knees_tip)).assertDoesNotExist()
    }

    private fun show(session: SessionResult) {
        compose.setContent {
            VolleyballLabTheme {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    SessionSummaryContent(session, "Forearm passing practice", {}, {}, PaddingValues(12.dp))
                }
            }
        }
    }

    private fun fixture(unmeasured: Boolean = false): SessionResult = SessionResult(
        id = "summary-preview", skill = Skill.Receiving, drillId = "receive-preview",
        startedAtEpochMs = 1_791_000_000_000L, durationMs = 42_000L,
        reps = List(4) { index ->
            RepResult(index, index * 1000L, index * 1000L + 500L, index * 1000L + 900L,
                listOf(CueId.PassPlatformStraight to .95f, CueId.PassKneeFlexion to .35f,
                    CueId.PassTrunkAngle to .80f, CueId.PassShouldersLevel to .82f).map { (cue, score) ->
                    CueScore(cue, if (unmeasured) null else 140f, if (unmeasured) null else score)
                })
        },
    )

    private fun capture(name: String) {
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
