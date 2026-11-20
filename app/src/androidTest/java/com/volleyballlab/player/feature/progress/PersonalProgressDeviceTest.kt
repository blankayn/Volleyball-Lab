package com.volleyballlab.player.feature.progress

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.ui.theme.VolleyballLabTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PersonalProgressDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun largeTextKeepsScoresSeparateAndSessionActionReachable() {
        var opened: String? = null
        show(SkillScoreProgress(Skill.Serving, listOf(
            SkillScorePoint("previous", 1_790_000_000_000L, 40),
            SkillScorePoint("latest", 1_790_086_400_000L, 60),
        ), null), scale = 1.6f, openSession = { opened = it })
        val latest = compose.onNodeWithText("Latest score").fetchSemanticsNode().boundsInRoot
        val previous = compose.onNodeWithText("Previous score").fetchSemanticsNode().boundsInRoot
        assertTrue("Score labels must not overlap", latest.right <= previous.left || latest.bottom <= previous.top)
        compose.onNodeWithText("20 points higher than your previous Serving session.").assertExists()
        compose.onNode(hasContentDescription("60 out of 100", substring = true)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("latest", opened) }
    }

    @Test fun genuineZeroHasStartingScoreAndCanOpenItsSession() {
        var opened: String? = null
        show(SkillScoreProgress(Skill.Serving, listOf(SkillScorePoint("zero", 1_790_000_000_000L, 0)), null),
            openSession = { opened = it })
        compose.onNodeWithText("Your starting score").assertIsDisplayed()
        compose.onNodeWithText("0/100").assertIsDisplayed()
        compose.onNode(hasContentDescription("0 out of 100", substring = true)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("zero", opened) }
    }

    @Test fun emptySkillOffersTrainingAndAllFiveSkillSelectors() {
        var starts = 0
        var selected: Skill? = null
        show(SkillScoreProgress(Skill.Serving, emptyList(), null), selectSkill = { selected = it },
            startTraining = { starts++ })
        listOf("Serving", "Receiving", "Setting", "Spiking", "Blocking").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("Setting").performClick()
        compose.runOnIdle { assertEquals(Skill.Setting, selected) }
        compose.onNodeWithText("Record your first session").performClick()
        compose.runOnIdle { assertEquals(1, starts) }
        compose.onNodeWithText("No scored Serving sessions yet").assertIsDisplayed()
    }

    private fun show(
        progress: SkillScoreProgress,
        scale: Float = 1f,
        selectSkill: (Skill) -> Unit = {},
        openSession: (String) -> Unit = {},
        startTraining: () -> Unit = {},
    ) {
        compose.setContent { VolleyballLabTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                    PersonalProgressCard(progress, selectSkill, openSession, startTraining)
                }
            }
        } }
        compose.waitForIdle()
    }
}
