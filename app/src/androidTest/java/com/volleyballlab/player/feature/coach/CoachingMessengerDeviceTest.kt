package com.volleyballlab.player.feature.coach

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.ui.theme.VolleyballLabTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CoachingMessengerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun question() = CoachingQuestion("q", "coach", "player", null, CoachingTopic.FirstDrill,
        "Which drill should I practise first?", 100)

    @Test fun inboxShowsUnreadCountAndOpensAPlayerConversation() {
        var opened = false
        val unread = question()
        val read = question().copy(id = "q2", playerUid = "other", createdAt = 900, coachReadAt = 900)
        compose.setContent { VolleyballLabTheme {
            CoachQuestionsPage({}, PaddingValues(), Modifier.width(360.dp).testTag("inbox"), title = "Inbox", unread = 1) {
                items(2) { index ->
                    val row = coachingInbox(listOf(read, unread))[index]
                    CoachingInboxRow(row, if (row.playerUid == "player") "Marcus Sterling" else "Ethan Hunt") { opened = true }
                }
            }
        } }
        val marcus = compose.onNodeWithText("Marcus Sterling").fetchSemanticsNode().boundsInRoot
        val ethan = compose.onNodeWithText("Ethan Hunt").fetchSemanticsNode().boundsInRoot
        assertTrue(marcus.top < ethan.top)
        screenshot("inbox", "restricted-coaching-inbox.png")
        compose.onNodeWithText("Marcus Sterling").performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test fun incomingMessageIsReadOnOpenAndFailedSendKeepsTheDraftWhileContactsDisableSend() {
        var reads = 0
        var sent = ""
        var error by mutableStateOf<String?>(null)
        compose.setContent { VolleyballLabTheme {
            Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()).testTag("thread")) {
                RestrictedCoachingThread(question(), true, true, false, error,
                    onSend = { sent = it; error = "Could not send. Try again." }, onResolve = {}, onRead = { reads++ })
            }
        } }
        compose.runOnIdle { assertEquals(1, reads) }
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("Call 0917 123 4567")
        compose.onNodeWithText("Send training response").performScrollTo().assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("Start with controlled platform drills.")
        compose.onNodeWithText("Send training response").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Start with controlled platform drills.", sent) }
        compose.onNodeWithText("Could not send. Try again.").performScrollTo().assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("Start with controlled platform drills.")
        screenshot("thread", "restricted-coaching-thread.png")
    }

    @Test fun resolvedAndDisconnectedConversationsHaveNoComposer() {
        compose.setContent { VolleyballLabTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RestrictedCoachingThread(question().copy(answer = "Ready position", answeredAt = 200, resolvedAt = 300),
                    false, true, false, null, {}, {}, {})
                RestrictedCoachingThread(question(), true, false, false, null, {}, {}, {})
            }
        } }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Resolved · this question is read only.").assertExists()
        compose.onNodeWithText("Connection ended · this conversation is read only.").assertExists()
    }

    private fun screenshot(tag: String, name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
