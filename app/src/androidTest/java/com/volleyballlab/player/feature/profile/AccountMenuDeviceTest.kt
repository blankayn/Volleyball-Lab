package com.volleyballlab.player.feature.profile

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.volleyballlab.player.feature.coachmode.CoachWorkspaceIdentity
import com.volleyballlab.player.navigation.ProfileRoute
import com.volleyballlab.player.ui.theme.LocalReduceMotion
import com.volleyballlab.player.ui.theme.VolleyballLabTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AccountMenuDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var controller: NavHostController

    private fun show(coach: Boolean, scale: Float = 1f) {
        compose.setContent {
            VolleyballLabTheme {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, scale), LocalReduceMotion provides true) {
                    Box(Modifier.width(if (scale > 1f) 320.dp else 360.dp)) {
                        controller = rememberNavController()
                        NavHost(controller, startDestination = ProfileRoute) {
                            composable<ProfileRoute> {
                                AccountMenuScreen(coach = coach, onLogout = {}, contentPadding = PaddingValues(12.dp),
                                    banner = { CoachWorkspaceIdentity("Alexandra Rivera", "Club", "AR", if (coach) "Coach profile" else "Student profile") },
                                    sectionContent = { Text("Inline ${it.title}") })
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun studentSectionsExpandInPlace() {
        show(false)
        verifySections(listOf(AccountSection.Personal, AccountSection.Plan, AccountSection.Settings, AccountSection.Help))
    }

    @Test fun coachSectionsExpandInPlace() {
        show(true)
        verifySections(listOf(AccountSection.Personal, AccountSection.Team, AccountSection.Settings, AccountSection.Help))
    }

    private fun verifySections(sections: List<AccountSection>) {
        compose.onNodeWithText("Terms and conditions", ignoreCase = true).assertDoesNotExist()
        val banner = compose.onNodeWithText("Alexandra Rivera").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithText("Personal information").fetchSemanticsNode().boundsInRoot
        assertTrue(banner.bottom <= first.top)
        sections.forEach { section ->
            compose.onNodeWithText(section.title).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithText("Inline ${section.title}").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { assertTrue(controller.currentDestination?.hasRoute<ProfileRoute>() == true) }
            compose.onNodeWithText(section.title).performScrollTo().performClick()
            compose.onNodeWithText("Inline ${section.title}").assertDoesNotExist()
        }
        compose.onNodeWithText("Log out").performScrollTo().assertIsDisplayed()
    }

    @Test fun largeTextCanReachHelp() {
        show(false, 1.6f)
        compose.onNodeWithText("Get help").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Inline Get help").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(controller.currentDestination?.hasRoute<ProfileRoute>() == true) }
    }
}
