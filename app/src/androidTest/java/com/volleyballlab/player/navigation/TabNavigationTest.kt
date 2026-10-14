package com.volleyballlab.player.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Uses the real NavController saved stacks, without auth, camera, or repositories. */
class TabNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var controller: NavHostController

    private fun player(start: Any = HomeRoute) {
        compose.setContent {
            controller = rememberNavController()
            NavHost(controller, startDestination = start) {
                composable<LoginRoute> { Text("Login") }
                composable<AssessmentRoute> { Text("Assessment") }
                composable<HomeRoute> { Text("Home") }
                navigation<TrainGraph>(startDestination = SkillSelectRoute) {
                    composable<SkillSelectRoute> { Text("Train") }
                    composable<DrillLibraryRoute> { Text("Library") }
                    composable<DrillDetailRoute> { Text("Detail") }
                }
                composable<ProgressRoute> { Text("Progress") }
                composable<CoachRoute> { Text("Coach") }
                composable<ProfileRoute> { Text("Profile") }
            }
        }
        compose.waitForIdle()
    }

    private inline fun <reified T : Any> expect() {
        compose.runOnIdle { assertTrue(controller.currentDestination?.hasRoute<T>() == true) }
    }

    @Test fun homeFromTrainReturnsHomeAndTrainRestoresItsDrill() {
        player()
        compose.runOnIdle {
            controller.navigateToTopLevel(TopLevelDestination.Train)
            controller.navigate(DrillLibraryRoute)
            controller.navigate(DrillDetailRoute("serve-standing-float"))
        }
        expect<DrillDetailRoute>()
        compose.runOnIdle { controller.navigateToTopLevel(TopLevelDestination.Home) }
        expect<HomeRoute>()
        compose.runOnIdle { controller.navigateToTopLevel(TopLevelDestination.Home) }
        expect<HomeRoute>()
        compose.runOnIdle { controller.navigateToTopLevel(TopLevelDestination.Train) }
        expect<DrillDetailRoute>()
        // Reselecting Train leaves its current drill in place.
        compose.runOnIdle { controller.navigateToTopLevel(TopLevelDestination.Train) }
        expect<DrillDetailRoute>()
        compose.runOnIdle { controller.navigateToTopLevel(TopLevelDestination.Progress) }
        expect<ProgressRoute>()
        compose.runOnIdle { controller.navigateToTopLevel(TopLevelDestination.Home) }
        expect<HomeRoute>()
    }

    @Test fun everyTabCanReturnHomeAfterLoginStartIsRemoved() {
        player(LoginRoute)
        compose.runOnIdle {
            controller.navigate(HomeRoute) { popUpTo<LoginRoute> { inclusive = true } }
        }
        TopLevelDestination.All.filter { it != TopLevelDestination.Home }.forEach { tab ->
            compose.runOnIdle { controller.navigateToTopLevel(tab) }
            compose.runOnIdle { controller.navigateToTopLevel(TopLevelDestination.Home) }
            expect<HomeRoute>()
        }
        // Auth was removed and tabs have not accumulated beneath Home.
        compose.runOnIdle { assertTrue(!controller.popBackStack()) }
    }

    @Test fun assessmentStartCanReturnHomeFromTrain() {
        player(AssessmentRoute)
        compose.runOnIdle {
            controller.navigate(HomeRoute) { popUpTo<AssessmentRoute> { inclusive = true } }
            controller.navigateToTopLevel(TopLevelDestination.Train)
        }
        expect<SkillSelectRoute>()
        compose.runOnIdle { controller.navigateToTopLevel(TopLevelDestination.Home) }
        expect<HomeRoute>()
    }

    @Test fun coachDashboardDoesNotRestoreRosterButRosterKeepsItsPlayer() {
        compose.setContent {
            controller = rememberNavController()
            NavHost(controller, startDestination = CoachDashboardRoute) {
                composable<CoachDashboardRoute> { Text("Dashboard") }
                composable<CoachRosterRoute> { Text("Roster") }
                composable<CoachPlayerRoute> { Text("Player") }
            }
        }
        compose.runOnIdle {
            controller.navigateToCoachTab(CoachTabDestination.Roster)
            controller.navigate(CoachPlayerRoute("navigation-test-player"))
        }
        compose.runOnIdle { controller.navigateToCoachTab(CoachTabDestination.Dashboard) }
        expect<CoachDashboardRoute>()
        compose.runOnIdle { controller.navigateToCoachTab(CoachTabDestination.Roster) }
        expect<CoachPlayerRoute>()
    }
}
