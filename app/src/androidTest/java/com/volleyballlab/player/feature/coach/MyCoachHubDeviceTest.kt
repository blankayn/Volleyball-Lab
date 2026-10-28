package com.volleyballlab.player.feature.coach

import android.graphics.Bitmap
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.navigation.toRoute
import com.volleyballlab.player.domain.model.CoachLink
import com.volleyballlab.player.domain.model.MembershipStatus
import com.volleyballlab.player.navigation.MyCoachRoute
import com.volleyballlab.player.navigation.MyCoachSectionRoute
import com.volleyballlab.player.navigation.openMyCoachSection
import com.volleyballlab.player.ui.theme.VolleyballLabTheme
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MyCoachHubDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var controller: NavHostController

    private fun showHub(scale: Float = 1f) {
        compose.setContent {
            VolleyballLabTheme {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                    controller = rememberNavController()
                    NavHost(controller, startDestination = MyCoachRoute) {
                        composable<MyCoachRoute> {
                            LazyColumn(Modifier.width(360.dp), contentPadding = PaddingValues(20.dp)) {
                                item { MyCoachHubSections(CoachLinkState(link = CoachLink("coach", "TeamYose", "Yose", MembershipStatus.Active, 1))) { controller.openMyCoachSection(it.name) } }
                            }
                        }
                        composable<MyCoachSectionRoute> { entry ->
                            Column {
                                Text("Full ${entry.toRoute<MyCoachSectionRoute>().section}")
                                TextButton({ controller.popBackStack() }) { Text("Back to My coach") }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun allSectionsAreTogetherAndEachOpensItsPageThenReturnsToTheHub() {
        showHub()
        CoachWorkspaceTab.entries.forEach { section ->
            compose.onNodeWithText("Open ${section.label.lowercase()}").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithText("Full ${section.name}").assertIsDisplayed()
            compose.onNodeWithText("Back to My coach").performClick()
            compose.runOnIdle { assertTrue(controller.currentDestination?.hasRoute<MyCoachRoute>() == true) }
        }
    }

    @Test fun switchingSectionsKeepsOneBackDestination() {
        showHub()
        compose.runOnIdle { controller.openMyCoachSection("Overview"); controller.openMyCoachSection("Training"); controller.openMyCoachSection("Feedback") }
        compose.onNodeWithText("Full Feedback").assertIsDisplayed()
        compose.onNodeWithText("Back to My coach").performClick()
        compose.runOnIdle { assertTrue(controller.currentDestination?.hasRoute<MyCoachRoute>() == true) }
    }

    @Test fun largeTextCanReachFeedbackWithoutHidingTheOtherSections() {
        showHub(1.6f)
        compose.onNodeWithText("Open feedback").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Full Feedback").assertIsDisplayed()
    }

    @Test fun aRealJpegRendersAndMissingOrInvalidPicturesUseInitials() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
        bitmap.recycle()
        val photo = "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        compose.setContent {
            VolleyballLabTheme {
                Column {
                    CoachPortrait(photo, "TY", Modifier.size(72.dp))
                    CoachPortrait(null, "AB", Modifier.size(64.dp))
                    CoachPortrait("invalid", "CD", Modifier.size(64.dp))
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Coach profile photo").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Coach profile photo").assertIsDisplayed()
        compose.onNodeWithText("AB").assertIsDisplayed()
        compose.onNodeWithText("CD").assertIsDisplayed()
    }
}
