package com.volleyballlab.player.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises typed history routes across the real nested training back stack. */
class SessionSummaryNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var controller: NavHostController

    private fun player() {
        compose.setContent {
            controller = rememberNavController()
            NavHost(controller, startDestination = HomeRoute) {
                composable<HomeRoute> { Text("Home") }
                composable<ProgressRoute> { Text("Progress") }
                composable<SessionSummaryRoute> { Text("Session summary") }
                navigation<TrainGraph>(startDestination = SkillSelectRoute) {
                    composable<SkillSelectRoute> { Text("Train") }
                    composable<SessionResultRoute> { Text("Full feedback") }
                }
            }
        }
        compose.waitForIdle()
    }

    private inline fun <reified T : Any> expectDestination() {
        compose.runOnIdle {
            assertTrue(controller.currentDestination?.hasRoute<T>() == true)
        }
    }

    private fun expectSummary(sessionId: String, fromProgress: Boolean) {
        expectDestination<SessionSummaryRoute>()
        compose.runOnIdle {
            val route = requireNotNull(controller.currentBackStackEntry)
                .toRoute<SessionSummaryRoute>()
            assertEquals(sessionId, route.sessionId)
            assertEquals(fromProgress, route.fromProgress)
        }
    }

    private fun openFullFeedback() {
        compose.runOnIdle {
            val summary = requireNotNull(controller.currentBackStackEntry)
                .toRoute<SessionSummaryRoute>()
            controller.navigate(
                SessionResultRoute(
                    sessionId = summary.sessionId,
                    returnToSummary = true,
                    summaryFromProgress = summary.fromProgress,
                ),
            )
        }
    }

    private fun expectFeedback(sessionId: String, fromProgress: Boolean) {
        expectDestination<SessionResultRoute>()
        compose.runOnIdle {
            val route = requireNotNull(controller.currentBackStackEntry)
                .toRoute<SessionResultRoute>()
            assertEquals(sessionId, route.sessionId)
            assertTrue(route.returnToSummary)
            assertEquals(fromProgress, route.summaryFromProgress)
        }
    }

    private fun finishFeedback() {
        compose.runOnIdle {
            val feedback = requireNotNull(controller.currentBackStackEntry)
                .toRoute<SessionResultRoute>()
            assertTrue(
                "The matching summary must remain beneath full feedback",
                controller.popBackStack(
                    SessionSummaryRoute(feedback.sessionId, feedback.summaryFromProgress),
                    inclusive = false,
                ),
            )
        }
    }

    @Test
    fun homeSummaryReturnsToSameSessionThenHomeAfterFullFeedback() {
        val sessionId = "home-history-session-42"
        player()
        compose.runOnIdle { controller.navigate(SessionSummaryRoute(sessionId)) }
        expectSummary(sessionId, fromProgress = false)

        openFullFeedback()
        expectFeedback(sessionId, fromProgress = false)
        finishFeedback()
        expectSummary(sessionId, fromProgress = false)

        compose.runOnIdle { assertTrue(controller.popBackStack()) }
        expectDestination<HomeRoute>()
        // The training graph entered for feedback must not remain beneath Home.
        compose.runOnIdle { assertFalse(controller.popBackStack()) }
    }

    @Test
    fun progressSummaryPreservesSessionAndOriginAfterFullFeedback() {
        val sessionId = "progress-history-session-87"
        player()
        compose.runOnIdle { controller.navigateToTopLevel(TopLevelDestination.Progress) }
        expectDestination<ProgressRoute>()
        compose.runOnIdle {
            controller.navigate(SessionSummaryRoute(sessionId, fromProgress = true))
        }
        expectSummary(sessionId, fromProgress = true)

        openFullFeedback()
        expectFeedback(sessionId, fromProgress = true)
        finishFeedback()
        expectSummary(sessionId, fromProgress = true)

        compose.runOnIdle { assertTrue(controller.popBackStack()) }
        expectDestination<ProgressRoute>()
        compose.runOnIdle { controller.navigateToTopLevel(TopLevelDestination.Home) }
        expectDestination<HomeRoute>()
        compose.runOnIdle { assertFalse(controller.popBackStack()) }
    }

    @Test
    fun trainTabFromHomeHistoryFeedbackOpensTraining() {
        val sessionId = "home-history-train-switch"
        player()
        compose.runOnIdle { controller.navigate(SessionSummaryRoute(sessionId)) }
        openFullFeedback()
        expectFeedback(sessionId, fromProgress = false)

        // Feedback belongs to TrainGraph, but its selected tab is Home. Train
        // must open its skill picker rather than treating this as a reselection.
        compose.runOnIdle { controller.navigateToTopLevel(TopLevelDestination.Train) }
        expectDestination<SkillSelectRoute>()
        compose.runOnIdle { controller.navigateToTopLevel(TopLevelDestination.Home) }
        expectDestination<HomeRoute>()
        compose.runOnIdle { assertFalse(controller.popBackStack()) }
    }

    @Test
    fun trainTabFromProgressHistoryFeedbackRestoresCleanHistoryOrigin() {
        val sessionId = "progress-history-train-switch"
        player()
        compose.runOnIdle { controller.navigateToTopLevel(TopLevelDestination.Progress) }
        compose.runOnIdle {
            controller.navigate(SessionSummaryRoute(sessionId, fromProgress = true))
        }
        openFullFeedback()
        expectFeedback(sessionId, fromProgress = true)

        compose.runOnIdle { controller.navigateToTopLevel(TopLevelDestination.Train) }
        expectDestination<SkillSelectRoute>()
        // Returning to Progress restores its summary, not the feedback screen
        // that happened to live inside the training graph.
        compose.runOnIdle { controller.navigateToTopLevel(TopLevelDestination.Progress) }
        expectSummary(sessionId, fromProgress = true)
        compose.runOnIdle { assertTrue(controller.popBackStack()) }
        expectDestination<ProgressRoute>()
        compose.runOnIdle { controller.navigateToTopLevel(TopLevelDestination.Home) }
        expectDestination<HomeRoute>()
        compose.runOnIdle { assertFalse(controller.popBackStack()) }
    }
}
