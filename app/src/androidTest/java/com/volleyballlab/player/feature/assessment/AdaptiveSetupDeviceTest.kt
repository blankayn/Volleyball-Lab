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
import com.volleyballlab.player.data.ai.AnalystResult
import com.volleyballlab.player.data.assessment.AthleteAssessmentRepository
import com.volleyballlab.player.data.assessment.AthletePlanner
import com.volleyballlab.player.data.assessment.PlannerSources
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.domain.repository.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AdaptiveSetupDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val owner = ViewModelStore()
    private var saved: AssessmentAnswers? = null
    private val drill = Drill("set-wall-reps", Skill.Setting, "Setting Stance", "", 20, 5, DrillLevel.Beginner)
    private fun show() {
        val assessments = object : AssessmentRepository {
            override val assessment = MutableStateFlow(SkillAssessment.Unanswered)
            override val hasOnboarded = MutableStateFlow(false)
            override suspend fun save(assessment: SkillAssessment) {}
            override suspend fun skip() {}
            override suspend fun complete(answers: AssessmentAnswers) { saved = answers }
        }
        val profiles = object : ProfileRepository {
            override val profile = MutableStateFlow(AthleteProfile())
            override suspend fun save(profile: AthleteProfile) {}
        }
        val store = object : AthleteAssessmentRepository {
            override val state = MutableStateFlow<AthleteState?>(AthleteState())
            override suspend fun saveAnswers(uid: String, answers: AssessmentAnswers) { state.value = AthleteState(answers) }
            override suspend fun savePlan(uid: String, answers: AssessmentAnswers, plan: AthletePlan) { state.value = AthleteState(answers, plan) }
        }
        val drills = object : DrillRepository {
            override suspend fun drillsFor(skill: Skill) = if (skill == Skill.Setting) listOf(drill) else emptyList()
            override suspend fun byId(id: String) = drill
        }
        val sources = object : PlannerSources {
            override val uid = "fixture"
            override suspend fun sessions() = emptyList<SessionResult>()
            override suspend fun memory() = AdaptiveMemory()
            override suspend fun marks() = emptyList<RepMark>()
            override suspend fun workouts() = emptyList<DrillPracticeRecord>()
            override suspend fun openDrills() = listOf(drill)
            override suspend fun onPlanned(uid: String, focus: List<Skill>) = Unit
        }
        val analysis = AthleteAnalysis("A plan made from your answers.", focus = listOf(Skill.Setting),
            practices = listOf(PlannedPractice("Hands up", listOf(PlannedDrill(drill.id, 2, 10, "You like setting.")))))
        val model = AssessmentViewModel(assessments, profiles, store, AthletePlanner(store, { AnalystResult.Ready(analysis) }, sources), drills, { "fixture" })
        owner.put("setup", model)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.6f)) {
                MaterialTheme { Box(Modifier.width(320.dp).fillMaxHeight()) { AssessmentScreen({}, PaddingValues(0.dp), model = model) } }
            }
        }
    }
    @After fun clear() { compose.runOnIdle { owner.clear() } }
    private fun pick(label: String) { compose.onNodeWithText(label).performScrollTo().performClick() }
    @Test fun narrowLargeTextAssessmentReachesItsResult() {
        show()
        pick("New to volleyball"); compose.onNodeWithText("Next").performClick()
        pick("Setting"); pick("Be more consistent"); compose.onNodeWithText("Next").performClick()
        pick("5"); compose.onNodeWithText("Next").performClick()
        SkillCheck.forSkill(Skill.Setting).forEach { statement ->
            compose.onNodeWithText(statement.text).assertExists()
            pick("Sometimes"); pick(statement.details.first().text)
            compose.onNodeWithText("Next").assertIsDisplayed().performClick()
        }
        compose.onNodeWithText("Add another skill").performScrollTo().assertIsDisplayed()
        assertNull(saved)
        compose.onNodeWithText("Get my assessment").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("A plan made from your answers.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Setting Stance").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(5, saved?.days); assertEquals(listOf(Skill.Setting), saved?.assessed); assertEquals(4, saved?.details?.size) }
    }
}
