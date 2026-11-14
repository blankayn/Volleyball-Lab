package com.volleyballlab.player.feature.assessment

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import com.volleyballlab.player.data.ai.TrainingSetupPersonalizer
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.domain.repository.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AdaptiveSetupDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private var saved: TrainingSetup? = null
    private fun show() {
        val assessments = object : AssessmentRepository {
            override val assessment = MutableStateFlow(SkillAssessment.Unanswered)
            override val hasOnboarded = MutableStateFlow(false)
            override suspend fun save(assessment: SkillAssessment) {}
            override suspend fun skip() {}
            override suspend fun saveTrainingSetup(setup: TrainingSetup) { saved = setup }
        }
        val profiles = object : ProfileRepository {
            override val profile = MutableStateFlow(AthleteProfile())
            override suspend fun save(profile: AthleteProfile) {}
        }
        val ai = object : TrainingSetupPersonalizer {
            override suspend fun nextCard(answers: SetupAnswers) = SetupFollowUp(Skill.Serving, "serving_focus", "Let's practice serving.", "groq")
            override suspend fun summary(answers: SetupAnswers) = SetupSuggestion(Skill.Serving, "Start with serving practice.", "You chose serving and contact.", "groq")
        }
        val model = AssessmentViewModel(assessments, profiles, ai, { "fixture" })
        store.put("setup", model)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.6f)) {
                MaterialTheme { Box(Modifier.width(320.dp).fillMaxHeight()) { AssessmentScreen({}, PaddingValues(0.dp), model = model) } }
            }
        }
    }
    @After fun clear() { compose.runOnIdle { store.clear() } }
    @Test fun narrowLargeTextSetupHasReachableActionsAndEditableReview() {
        show()
        compose.onNodeWithText("Serving", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Continue with AI").assertIsDisplayed().performClick()
        compose.onNodeWithText("New to volleyball").performScrollTo().performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Contacting the ball").performScrollTo().performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("5 / week").performScrollTo().performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Based on your answers").assertExists()
        assertNull(saved)
        compose.onNodeWithText("Setting", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Save my plan").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(Skill.Setting, saved?.focusSkill); assertEquals(5, saved?.weeklySessionGoal) }
    }
    @Test fun keyboardDoesNotHideTheNextAction() {
        show()
        compose.onNodeWithText("Describe your goal (optional)").performScrollTo().performTextInput("Gusto ko maging consistent sa serve")
        compose.onNodeWithText("Continue with AI").assertIsDisplayed().performClick()
        compose.onNodeWithText("How much have you played?").assertExists()
    }
}
