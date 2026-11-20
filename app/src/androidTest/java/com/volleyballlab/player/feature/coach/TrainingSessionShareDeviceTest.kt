package com.volleyballlab.player.feature.coach

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TrainingSessionShareDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun earlierSessionCanBeSelectedWithoutSharingLatest() {
        val latest = SessionResult("latest", Skill.Setting, "setting", 2000, 1000, emptyList())
        val older = latest.copy(id = "older", startedAtEpochMs = 1000)
        val selected = mutableListOf<String>()
        compose.setContent {
            Column {
                SendSessionCard(listOf(latest, older), noSessions = false, sending = false, error = null) { selected += it }
            }
        }
        compose.onAllNodesWithText("Send")[1].performClick()
        compose.runOnIdle { assertEquals(listOf("older"), selected) }
    }
}
