package com.volleyballlab.player.feature.coachmode

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.volleyballlab.player.feature.notifications.NotificationBellButton
import com.volleyballlab.player.R
import com.volleyballlab.player.ui.theme.LocalReduceMotion
import com.volleyballlab.player.ui.theme.VolleyballLabTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Native checks for the detached-badge regression and large-text dashboard layout. */
class CoachWorkspaceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun dashboardPhotoAndWorkspaceFitAtNormalTextSize() {
        compose.setContent {
            VolleyballLabTheme {
                Column(Modifier.width(360.dp).coachWorkspaceBackground().verticalScroll(rememberScrollState())
                    .testTag("dashboard-proof").padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    CoachFeatureCard("Review queue", "1 session waiting for you",
                        "Open a session to review your player’s training.", actionLabel = "Review session",
                        onAction = {}, backgroundPhoto = R.drawable.coach_dashboard_court)
                    CoachDashboardWorkspace({}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("Keep your team moving").assertIsDisplayed()
        compose.onNodeWithText("Player questions").assertIsDisplayed()
        screenshot("dashboard-proof", "coach-dashboard-workspace.png")
    }

    @Test fun dashboardWorkspaceDestinationsRemainReachableWithPhotoBannerAndLargeText() {
        val opened = mutableListOf<String>()
        compose.setContent {
            VolleyballLabTheme {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, 1.6f), LocalReduceMotion provides true) {
                    Column(Modifier.width(320.dp).coachWorkspaceBackground().verticalScroll(rememberScrollState())
                        .testTag("workspace-proof").padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        CoachFeatureCard("Review queue", "1 session waiting for you",
                            "Open a session to review your player’s training.", actionLabel = "Review session",
                            onAction = { opened += "review" }, backgroundPhoto = R.drawable.coach_dashboard_court)
                        CoachDashboardWorkspace({ opened += "players" }, { opened += "assignments" },
                            { opened += "questions" }, { opened += "account" })
                    }
                }
            }
        }
        listOf("Review session", "Players", "Assignments", "Player questions", "Manage coach profile").forEach {
            compose.onNodeWithText(it).performScrollTo().assertHasClickAction().performClick()
        }
        compose.runOnIdle { assertEquals(listOf("review", "players", "assignments", "questions", "account"), opened) }
        compose.onNodeWithText("Review session").performScrollTo()
        screenshot("workspace-proof", "coach-dashboard-workspace-large-text.png")
    }

    @Test fun bellKeepsAccessibleCountAndClickTargetForSingleAndCappedBadges() {
        var clicks = 0
        compose.setContent {
            VolleyballLabTheme {
                Row(Modifier.testTag("bell-proof").padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    NotificationBellButton(1) { clicks++ }
                    NotificationBellButton(123) { clicks++ }
                    NotificationBellButton(0) { clicks++ }
                }
            }
        }
        compose.onNodeWithContentDescription("Notifications, 1 unread").assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("Notifications, 123 unread").assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("Notifications").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(3, clicks) }
        compose.onNodeWithContentDescription("Notifications, 1 unread").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        screenshot("bell-proof", "coach-notification-badges.png")
    }

    @Test fun dashboardStatsStaySeparatedAtNarrowWidthAndLargeText() {
        compose.setContent {
            VolleyballLabTheme {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, 1.6f), LocalReduceMotion provides true) {
                    Column(Modifier.width(320.dp).coachWorkspaceBackground().verticalScroll(rememberScrollState()).testTag("stats-proof").padding(16.dp)) {
                        Text("Coach workspace · large text")
                        Spacer(Modifier.height(16.dp))
                        CoachStatStrip(listOf(
                            CoachStat("12", "Players", Icons.Rounded.Group, CoachAccent.Rust),
                            CoachStat("3", "To review", Icons.Rounded.Group, CoachAccent.Indigo),
                            CoachStat("24", "Sets this week", Icons.Rounded.Group, CoachAccent.Green),
                            CoachStat("81", "Team form", Icons.Rounded.Group, CoachAccent.Violet),
                        ))
                    }
                }
            }
        }
        val players = compose.onNodeWithText("Players", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val reviews = compose.onNodeWithText("To review", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val weekly = compose.onNodeWithText("Sets this week", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val form = compose.onNodeWithText("Team form", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Columns must not overlap", players.right <= reviews.left && weekly.right <= form.left)
        assertTrue("The second row must clear the first", maxOf(players.bottom, reviews.bottom) < minOf(weekly.top, form.top))
        screenshot("stats-proof", "coach-stats-large-text.png")
    }

    private fun screenshot(tag: String, name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
