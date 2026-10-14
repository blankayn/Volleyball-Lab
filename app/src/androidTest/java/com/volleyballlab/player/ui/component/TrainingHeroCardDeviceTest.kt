package com.volleyballlab.player.ui.component

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.volleyballlab.player.ui.theme.LocalReduceMotion
import com.volleyballlab.player.ui.theme.VolleyballLabTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Native visual checks for the supplied card reference, including accessibility layout. */
class TrainingHeroCardDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun showCard(fontScale: Float, onClick: () -> Unit = {}) {
        compose.setContent {
            val density = LocalDensity.current.density
            VolleyballLabTheme {
                CompositionLocalProvider(LocalReduceMotion provides true,
                    LocalDensity provides Density(density, fontScale)) {
                    Box(Modifier.padding(12.dp)) {
                        TrainingHeroCard(onClick, Modifier.width(360.dp).testTag("hero"))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun referenceProportionsAndReadableSpacing() {
        var opens = 0
        showCard(1f) { opens++ }
        val card = compose.onNodeWithTag("hero").fetchSemanticsNode().boundsInRoot
        assertEquals(515f / 402f, card.width / card.height, .015f)
        assertHeadlineClearsTrainingPrompt()
        saveScreenshot("training-hero-reference.png")
        compose.onNodeWithTag("hero").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun largeAccessibilityTextDoesNotOverlapTrainingPrompt() {
        showCard(1.6f)
        assertHeadlineClearsTrainingPrompt()
        saveScreenshot("training-hero-large-text.png")
    }

    private fun assertHeadlineClearsTrainingPrompt() {
        val headline = compose.onNodeWithText("Build a stronger\ngame, one rep\nat a time.",
            useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val prompt = compose.onNodeWithText("Start training", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("The headline must leave a clear gap above Start training", headline.bottom < prompt.top)
    }

    private fun saveScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), name)
        output.outputStream().use {
            compose.onNodeWithTag("hero").captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
