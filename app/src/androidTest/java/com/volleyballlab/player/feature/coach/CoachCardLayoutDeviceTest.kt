package com.volleyballlab.player.feature.coach

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.ui.theme.VolleyballLabTheme
import java.io.File
import org.junit.Rule
import org.junit.Test

class CoachCardLayoutDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun compactHubShowsLiveStatusAndPreview() {
        val note = CoachNote("note", "coach", "player", "reviewed", "clear focus next set", 1)
        val state = CoachLinkState(
            link = CoachLink("coach", "Coach", "Team", MembershipStatus.Active, 1),
            submissions = listOf(
                SessionSubmission("reviewed", "coach", "player", 1, 2),
                SessionSubmission("waiting", "coach", "player", 2),
                SessionSubmission("waiting2", "coach", "player", 3),
            ),
            notes = listOf(note),
        )
        compose.setContent { VolleyballLabTheme {
            LazyColumn(Modifier.width(400.dp), contentPadding = PaddingValues(20.dp)) {
                item { MyCoachHubSections(state, {}) {} }
            }
        } }
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("0 active").assertIsDisplayed()
        compose.onNodeWithText("2 pending").assertIsDisplayed()
        compose.onNodeWithText("1 reviewed · clear focus next set").assertIsDisplayed()
        capture("coach-hub-cards.png")
    }

    @Test fun feedbackScoresAndNotesMatchEachSession() {
        showFeedback()
        compose.onNodeWithText("62").assertIsDisplayed()
        compose.onNodeWithText("86").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("With coach for review").assertIsDisplayed()
        capture("coach-feedback-cards.png")
    }

    @Test fun feedbackCardsRemainReachableWithLargeText() {
        showFeedback(1.6f)
        compose.onNodeWithText("86").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("With coach for review").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("62").performScrollTo().assertIsDisplayed()
    }

    private fun showFeedback(scale: Float = 1f) {
        compose.setContent { VolleyballLabTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                LazyColumn(Modifier.width(400.dp).coachPageBackground(), contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    item { CoachSectionTitle("Your progress, with context", "Session feedback, coaching questions, and your reviews", 2) }
                    item {
                        CoachFeedbackSessionCard(
                            SessionSubmission("reviewed", "coach", "player", 1_794_009_900_000, 1_794_529_500_000),
                            session("reviewed", Skill.Setting, .62f),
                            listOf(CoachNote("note", "coach", "player", "reviewed", "clear focus next set", 1_794_529_500_000)),
                        ) {
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton({}) { Text("★★★★  Edit review") }
                                OutlinedButton({}) { Text("Ask question") }
                            }
                        }
                    }
                    item { CoachFeedbackSessionCard(
                        SessionSubmission("waiting", "coach", "player", 1_793_892_780_000),
                        session("waiting", Skill.Serving, .86f), emptyList(),
                    ) }
                }
            }
        } }
    }

    private fun session(id: String, skill: Skill, score: Float) = SessionResult(
        id, skill, "drill", 1_794_009_900_000, 1_000,
        listOf(RepResult(1, 0, 500, 1_000, listOf(CueScore(
            if (skill == Skill.Setting) CueId.SetHandsAboveForehead else CueId.ServeContactArmExtension, 1f, score,
        )))),
    )

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
