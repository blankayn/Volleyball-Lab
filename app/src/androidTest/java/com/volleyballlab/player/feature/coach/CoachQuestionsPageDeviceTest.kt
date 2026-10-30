package com.volleyballlab.player.feature.coach

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
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
import com.volleyballlab.player.feature.coachmode.CoachCard
import com.volleyballlab.player.ui.theme.LocalReduceMotion
import com.volleyballlab.player.ui.theme.VolleyballLabTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CoachQuestionsPageDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var backs = 0
    private var responses = 0

    private fun showPage(scale: Float) {
        compose.setContent {
            VolleyballLabTheme {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, scale), LocalReduceMotion provides true) {
                    CoachQuestionsPage({ backs++ }, PaddingValues(), Modifier.width(320.dp).testTag("questions-proof")) {
                        item { Text("Needs reply · 2", style = MaterialTheme.typography.labelLarge) }
                        items(2) { index ->
                            CoachCard(modifier = Modifier.heightIn(min = 180.dp), padding = 24.dp) {
                                Text(if (index == 0) "Getting started" else "Session question", style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.height(12.dp))
                                Text("Player ${index + 1}", style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.height(12.dp))
                                Text(if (index == 0) "Which drill should I practise before my first recorded session?"
                                    else "How can I keep a more consistent platform when receiving?")
                                Spacer(Modifier.height(16.dp))
                                TextButton(onClick = { responses++ }) { Text("Respond to player ${index + 1}") }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun headerIsCenteredAndStaysAboveStackedCardsWhenScrolling() {
        showPage(1f)
        val page = compose.onNodeWithTag("questions-proof").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Player questions").fetchSemanticsNode().boundsInRoot
        assertEquals(page.center.x, title.center.x, 1f)
        val first = compose.onNodeWithText("Player 1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("Player 2").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(first.bottom < second.top)
        screenshot("coach-player-questions.png")
        compose.onNodeWithText("Respond to player 2").performScrollTo().performClick()
        assertEquals(title.top, compose.onNodeWithText("Player questions").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithContentDescription("Back").assertWidthIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, backs); assertEquals(1, responses) }
    }

    @Test fun largeTextKeepsBackAndTheSecondCardActionReachable() {
        showPage(1.6f)
        compose.onNodeWithText("Respond to player 2").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Player questions").assertIsDisplayed()
        screenshot("coach-player-questions-large-text.png")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(1, backs); assertEquals(1, responses) }
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onNodeWithTag("questions-proof").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
