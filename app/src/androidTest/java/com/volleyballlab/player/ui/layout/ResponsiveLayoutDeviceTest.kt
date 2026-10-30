package com.volleyballlab.player.ui.layout

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.feature.coach.CoachMetrics
import com.volleyballlab.player.feature.coachmode.*
import com.volleyballlab.player.feature.train.skill.SkillCard
import com.volleyballlab.player.feature.profile.ProfileExportActions
import com.volleyballlab.player.ui.component.LocalScreenEntranceEnabled
import com.volleyballlab.player.ui.theme.LocalReduceMotion
import com.volleyballlab.player.ui.theme.VolleyballLabTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Checks real components with fixture callbacks only; no account writes or real player actions. */
class ResponsiveLayoutDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(width: Int = 320, scale: Float = 1.6f, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            VolleyballLabTheme(darkTheme = dark) {
                val density = if (width >= 600) 1.25f else LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, scale), LocalReduceMotion provides true,
                    LocalScreenEntranceEnabled provides false) {
                    Box(Modifier.width(width.dp).testTag("layout-proof")) { content() }
                }
            }
        }
    }

    @Test fun assignmentSearchPrecedesNewDrillWithMatchingEdgesAndWorkingActions() {
        var query by mutableStateOf("")
        var enabled by mutableStateOf(false)
        var clicks = 0
        show(scale = 1f) {
            Column {
                CoachPageHeader("Assignments", null)
                CoachAssignmentTools(query, { query = it }, enabled, { clicks++ }, Modifier.padding(horizontal = 20.dp))
            }
        }
        val search = compose.onNode(hasSetTextAction())
        val create = compose.onNodeWithText("New drill")
        val searchBounds = search.fetchSemanticsNode().boundsInRoot
        val createBounds = create.fetchSemanticsNode().boundsInRoot
        assertTrue(searchBounds.bottom <= createBounds.top)
        assertEquals(searchBounds.left, createBounds.left, 1f)
        assertEquals(searchBounds.right, createBounds.right, 1f)
        create.assertIsNotEnabled()
        compose.runOnIdle { enabled = true }
        create.performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
        search.performTextInput("float")
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.runOnIdle { assertEquals("", query) }
        compose.activityRule.scenario.onActivity { it.window.decorView.clearFocus() }
        capture("assignments-search-first.png")
    }

    @Test fun targetControlsStackAndRetainTouchTargetsAtLargeText() {
        var sets by mutableIntStateOf(3)
        var reps by mutableIntStateOf(10)
        show { Column(Modifier.padding(20.dp)) { AssignmentTargets(sets, reps, { sets = it }, { reps = it }) } }
        val setsBounds = compose.onNodeWithText("Sets").fetchSemanticsNode().boundsInRoot
        val repsBounds = compose.onNodeWithText("Reps per set").fetchSemanticsNode().boundsInRoot
        assertTrue(setsBounds.bottom < repsBounds.top)
        compose.onNodeWithContentDescription("Increase Sets").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(4, sets) }
        compose.onNodeWithText("4").assertIsDisplayed()
        assertTextFits("10")
        compose.onNodeWithContentDescription("Decrease Reps per set").performClick()
        compose.runOnIdle { assertEquals(9, reps) }
        assertTextFits("4")
        assertTextFits("9")
        capture("assignment-targets-large-text.png")
    }

    @Test fun longStudentHeaderKeepsBadgeBelowNameAndWithinThePage() {
        val name = "Alexandra Marielle Dela Cruz"
        show {
            CoachDetailHeader(name, "Outside hitter", {}, leading = { InitialsAvatar("AC", diameter = 40, accent = CoachAccent.Rust) },
                trailing = { AccentChip("Sample player", CoachAccent.Violet) })
        }
        val title = compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot
        val badge = compose.onNodeWithText("Sample player").fetchSemanticsNode().boundsInRoot
        assertTrue(title.bottom <= badge.top)
        assertTextFits(name)
        assertInsidePage(name)
        assertInsidePage("Sample player")
        capture("player-header-large-text.png")
    }

    @Test fun coachMetricsStackOnANarrowLargeTextPage() {
        show { Column(Modifier.padding(20.dp)) { CoachMetrics(CoachPublicSummary()) } }
        val rating = compose.onNodeWithText("Coach rating").fetchSemanticsNode().boundsInRoot
        val response = compose.onNodeWithText("Typical response").fetchSemanticsNode().boundsInRoot
        assertTrue(rating.bottom < response.top)
        capture("coach-metrics-large-text.png")
        assertTextFits("Typical response")
        assertInsidePage("History building")
        capture("coach-metrics-large-text.png")
    }

    @Test fun coachMetricsUseColumnsWhenThePageHasRoom() {
        show(width = 600) { Column(Modifier.padding(20.dp)) { CoachMetrics(CoachPublicSummary()) } }
        val rating = compose.onNodeWithText("Coach rating").fetchSemanticsNode().boundsInRoot
        val response = compose.onNodeWithText("Typical response").fetchSemanticsNode().boundsInRoot
        assertTrue(rating.right < response.left)
        assertEquals(rating.top, response.top, 1f)
    }

    @Test fun everySkillKeepsItsTitleAndDescriptionInsideAnAdaptiveCard() {
        var skill by mutableStateOf(Skill.Serving)
        show { Column(Modifier.padding(20.dp)) { SkillCard(skill, skill.ordinal + 1, null, true, {}) } }
        Skill.All.forEach { candidate ->
            compose.runOnIdle { skill = candidate }
            val name = compose.activity.getString(candidate.displayName)
            val description = compose.activity.getString(candidate.description)
            assertTextFits(name)
            assertTextFits(description)
            assertInsidePage(name)
            assertInsidePage(description)
        }
        capture("skill-card-large-text.png")
    }

    @Test fun normalPhoneWidthKeepsTheOriginalIllustratedBackground() {
        show(width = 320, scale = 1f) { Column(Modifier.padding(horizontal = 20.dp)) { SkillCard(Skill.Receiving, 2, null, true, {}) } }
        compose.onNodeWithTag("skill-card-background", useUnmergedTree = true).assertIsDisplayed().assertHeightIsEqualTo(164.dp)
        assertInsidePage(compose.activity.getString(Skill.Receiving.displayName))
        assertInsidePage(compose.activity.getString(Skill.Receiving.description))
        capture("skill-card-restored.png")
    }

    @Test fun profileExportActionsStayAlignedAndUsableAtLargeText() {
        var exports = 0
        show { Column(Modifier.padding(40.dp)) { ProfileExportActions(false, { exports++ }, { exports++ }) } }
        val csv = compose.onNodeWithText("Export CSV")
        val json = compose.onNodeWithText("Export JSON")
        val first = csv.fetchSemanticsNode().boundsInRoot
        val second = json.fetchSemanticsNode().boundsInRoot
        assertTrue(first.bottom <= second.top)
        assertEquals(first.left, second.left, 1f)
        assertEquals(first.right, second.right, 1f)
        csv.assertHeightIsAtLeast(48.dp).performClick()
        json.assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(2, exports) }
        assertTextFits("Export CSV")
        assertTextFits("Export JSON")
    }

    @Test fun skillCardRemainsLegibleWithLargeTextAndDarkTheme() {
        show(dark = true) { Column(Modifier.padding(20.dp)) { SkillCard(Skill.Receiving, 2, null, true, {}) } }
        assertTextFits(compose.activity.getString(Skill.Receiving.displayName))
        assertTextFits(compose.activity.getString(Skill.Receiving.description))
        capture("skill-card-dark-theme.png")
    }

    @Test fun playerPageKeepsSessionsAndAssignmentsReachableWithoutATabStrip() {
        val now = System.currentTimeMillis()
        val member = SampleTeam.roster(now).first()
        val sessions = SampleTeam.sessions(now).getValue(member.playerUid)
        val assignment = CoachAssignment("fixture-assignment", "fixture-coach", member.playerUid, "Controlled serving practice", Skill.Serving,
            DrillLevel.Beginner, "Reach high at contact", "", 3, 10, null, AssignmentStatus.Pending, now)
        val state = CoachTeamState(roster = listOf(member), snapshots = listOf(PlayerSnapshot.build(member, sessions, 1, 0, now)),
            assignments = listOf(assignment), loaded = true)
        var assigned: String? = null
        show(scale = 1f) {
            CoachPlayerContent(state, member.playerUid, {}, { _, _ -> }, { assigned = it }, {}, PaddingValues())
        }
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onAllNodes(tab).assertCountEquals(0)
        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasText("Recent sessions"))
        compose.onNodeWithText("Recent sessions").assertIsDisplayed()
        list.performScrollToNode(hasText("Assignments"))
        compose.onNodeWithText("Assignments").assertIsDisplayed()
        list.performScrollToNode(hasText("Controlled serving practice"))
        compose.onNodeWithText("Controlled serving practice").assertIsDisplayed()
        list.performScrollToNode(hasText("Set a drill"))
        compose.onNodeWithText("Set a drill").performClick()
        compose.runOnIdle { assertEquals(member.playerUid, assigned) }
    }

    private fun assertTextFits(text: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("Missing layout for $text", results.isNotEmpty())
        val result = results.first()
        // Paragraphs can retain their wrapping width after text is measured more
        // tightly. Inspect the painted lines, rather than unused paragraph space.
        (0 until result.lineCount).forEach { line ->
            assertTrue("Text extends left: $text", result.getLineLeft(line) >= -1f)
            assertTrue("Text extends right: $text", result.getLineRight(line) <= result.size.width + 1f)
            assertTrue("Text extends below its box: $text", result.getLineBottom(line) <= result.size.height + 1f)
            assertFalse("Text is ellipsized: $text", result.isLineEllipsized(line))
        }
    }

    private fun assertInsidePage(text: String) {
        val child = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val parent = compose.onNodeWithTag("layout-proof").fetchSemanticsNode().boundsInRoot
        assertTrue("Text leaves the page: $text", child.left >= parent.left && child.right <= parent.right && child.bottom <= parent.bottom)
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onNodeWithTag("layout-proof").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
