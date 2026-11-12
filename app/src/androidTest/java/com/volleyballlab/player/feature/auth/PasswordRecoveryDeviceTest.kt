package com.volleyballlab.player.feature.auth

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.volleyballlab.player.data.auth.PasswordRecoveryService
import com.volleyballlab.player.data.auth.RecoveryChallenge
import com.volleyballlab.player.data.auth.VerifiedRecovery
import com.volleyballlab.player.ui.theme.VolleyballLabTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises recovery UI with synthetic codes, without emailing or changing a Firebase account. */
class PasswordRecoveryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun codeVerificationPrecedesPasswordChangeAndActionsHaveRoom() {
        var sentTo = ""
        var reset = false
        val service = object : PasswordRecoveryService {
            override suspend fun sendRecovery(email: String): RecoveryChallenge {
                sentTo = email
                return RecoveryChallenge("test-challenge", 60)
            }
            override suspend fun verifyCode(challenge: RecoveryChallenge, code: String): VerifiedRecovery {
                check(code == "123456")
                return VerifiedRecovery(sentTo, "test-action")
            }
            override suspend fun resetPassword(recovery: VerifiedRecovery, password: String) {
                check(recovery.email == sentTo && password == "NewPassword123!")
                reset = true
            }
        }
        compose.setContent { VolleyballLabTheme { PasswordRecoveryScreen("", {}, recovery = service) } }
        val field = compose.onNodeWithContentDescription("Email")
        val button = compose.onNodeWithText("Send reset code")
        val gap = button.fetchSemanticsNode().boundsInRoot.top - field.fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("The field needs at least 24dp above its primary action", gap >= with(compose.density) { 24.dp.toPx() } - 1)
        capture("recovery-email.png")
        field.performTextInput("athlete@example.com")
        button.performScrollTo().performClick()
        compose.onNodeWithContentDescription("Reset code").assertExists()
        compose.onNodeWithContentDescription("Reset code").performTextInput("12345")
        compose.onNodeWithText("Verify code").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Reset code").performTextReplacement("123456")
        compose.onNodeWithText("Verify code").performScrollTo().assertIsDisplayed()
        capture("recovery-code.png")
        compose.onNodeWithText("Verify code").performClick()
        compose.onNodeWithContentDescription("New password").performScrollTo().performTextInput("NewPassword123!")
        compose.onNodeWithContentDescription("Confirm new password").performScrollTo().performTextInput("Different123!")
        compose.onNodeWithText("Change password").performScrollTo().performClick()
        assertFalse(reset)
        compose.onNodeWithContentDescription("Confirm new password").performScrollTo().performTextReplacement("NewPassword123!")
        compose.onNodeWithText("Change password").performScrollTo().assertIsDisplayed()
        capture("recovery-password.png")
        compose.onNodeWithText("Change password").performClick()
        compose.onNodeWithText("Password changed").assertExists()
        assertTrue(reset)
    }

    @Test fun emailRecoveryRemainsAvailableUntilTheCodeSenderIsReady() {
        var sentTo = ""
        var closed = false
        val service = object : PasswordRecoveryService {
            override val usesEmailCode = false
            override suspend fun sendRecovery(email: String): RecoveryChallenge? {
                sentTo = email
                return null
            }
            override suspend fun verifyCode(challenge: RecoveryChallenge, code: String): VerifiedRecovery =
                error("Email recovery opens its link from the inbox")
            override suspend fun resetPassword(recovery: VerifiedRecovery, password: String) =
                error("Firebase handles the email link")
        }
        compose.setContent { VolleyballLabTheme {
            PasswordRecoveryScreen("athlete@example.com", { closed = true }, recovery = service)
        } }
        compose.onNodeWithText("Send reset email").performClick()
        compose.onNodeWithText("Check your email").assertExists()
        compose.onNodeWithContentDescription("Reset code").assertDoesNotExist()
        assertEquals("athlete@example.com", sentTo)
        compose.onNodeWithText("Back to sign in").performClick()
        assertTrue(closed)
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
