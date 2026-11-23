package com.volleyballlab.player.domain.model

import org.junit.Assert.*
import org.junit.Test

class CoachingInboxTest {
    private fun question(id: String = "q", player: String = "p", at: Long = 100L) =
        CoachingQuestion(id, "coach", player, null, CoachingTopic.FirstDrill, "Which drill first?", at)

    @Test fun unreadPlayersRiseAboveNewerReadConversations() {
        val oldUnread = question(player = "unread", at = 100)
        val newRead = question(player = "read", at = 900).copy(coachReadAt = 900)
        assertEquals(listOf("unread", "read"), coachingInbox(listOf(newRead, oldUnread)).map { it.playerUid })
    }
    @Test fun messagesAreCountedAcrossThreadsAndGroupedOncePerPlayer() {
        val first = question().copy(answer = "Ready position", answeredAt = 200,
            followUp1 = CoachingFollowUp("Should I bend my knees?", 300))
        val second = question("q2", at = 500)
        val inbox = coachingInbox(listOf(first, second))
        assertEquals(1, inbox.size)
        assertEquals(3, inbox.single().unread)
        assertEquals(500L, inbox.single().latest.at)
    }
    @Test fun ownRepliesNeverCountAsUnreadAndLoadingAnOlderSnapshotDoesNotReadANewMessage() {
        val row = question().copy(answer = "Ready position", answeredAt = 200, coachReadAt = 100,
            followUp1 = CoachingFollowUp("Should I bend my knees?", 300))
        assertEquals(1, row.unread(true))
        assertEquals(1, row.unread(false))
        assertEquals(0, row.copy(coachReadAt = 300).unread(true))
        assertEquals(1, row.copy(playerReadAt = 100).unread(false))
    }
    @Test fun threadIsLimitedToAlternatingTrainingTurnsAndResolvedHistoryStillContainsMessages() {
        val waiting = question()
        assertTrue(waiting.awaitingCoach)
        val answered = waiting.copy(answer = "Ready position", answeredAt = 200)
        assertFalse(answered.awaitingCoach)
        val follow = answered.copy(followUp1 = CoachingFollowUp("How low?", 300, "Comfortable stance", 400),
            followUp2 = CoachingFollowUp("How many reps?", 500, "Ten controlled reps", 600))
        assertEquals(2, follow.followUpCount)
        assertEquals(6, follow.messages.size)
        assertFalse(follow.copy(resolvedAt = 700).awaitingCoach)
        assertEquals(6, follow.copy(resolvedAt = 700).messages.size)
    }
    private fun chat(id: String, at: Long, sender: String = "p", player: String = "p") =
        CoachingChatMessage(id, "coach", player, sender, "Message $id", at)
    @Test fun openChatJoinsEarlierQuestionsInOneTimelineAndSendingMessagesStayLast() {
        val row = question(at = 100).copy(answer = "Ready position", answeredAt = 200)
        val conversation = coachingInbox(listOf(row), listOf(chat("b", 0, "coach"), chat("a", 150))).single()
        assertEquals(listOf("Which drill first?", "Message a", "Ready position", "Message b"), conversation.messages.map { it.text })
        assertEquals("Message b", conversation.latest.text)
    }
    @Test fun chatUnreadIsPerSideAndOwnOrUnsavedMessagesNeverCount() {
        val messages = listOf(chat("a", 100), chat("b", 300), chat("c", 400, "coach"), chat("d", 0, "coach"))
        val read = CoachingRead("coach", "p", coachReadAt = 100)
        val conversation = coachingInbox(emptyList(), messages, listOf(read)).single()
        assertEquals(1, conversation.unread(true))
        assertEquals(1, conversation.unread(false))
        assertEquals(0, coachingInbox(emptyList(), messages, listOf(read.copy(coachReadAt = 300))).single().unread)
    }
    @Test fun coachCanStartAChatWithNoEarlierQuestionAndPlayersStayInSeparateConversations() {
        val inbox = coachingInbox(emptyList(), listOf(chat("a", 100, "coach"), chat("b", 200, "other", "other")))
        assertEquals(listOf("other", "p"), inbox.map { it.playerUid })
        assertNull(inbox.last().waitingQuestion)
        assertEquals(emptyList<CoachingMessage>(), coachingConversation("coach", "new", emptyList(), emptyList(), emptyList()).messages)
        assertTrue(coachingConversation("another", "p", emptyList(), listOf(chat("a", 100)), emptyList()).messages.isEmpty())
    }
    @Test fun oldestWaitingQuestionIsAnsweredFirst() {
        val inbox = coachingInbox(listOf(question("new", at = 300), question("old", at = 100), question("done", at = 50).copy(answer = "Yes", answeredAt = 60)))
        assertEquals("old", inbox.single().waitingQuestion?.id)
    }
    @Test fun chatNotificationsAreOnlyForUnreadIncomingMessages() {
        val messages = listOf(chat("a", 100), chat("b", 300), chat("c", 400, "coach"), chat("d", 0))
        val read = listOf(CoachingRead("coach", "p", coachReadAt = 100))
        assertEquals(listOf("chat:b"), NotificationEvents.chat(messages, read, true).map { it.id })
        assertEquals(listOf("chat:c"), NotificationEvents.chat(messages, read, false).map { it.id })
    }
    @Test fun contactPatternsAreRejectedWithoutBlockingOrdinaryTrainingNumbers() {
        listOf("https://example.com", "www.example.com", "example.com", "coach.ph", "training.xyz", "Email player@example.com",
            "DM @player", "Call +63 917 123 4567", "Message on WhatsApp").forEach { assertTrue(it, hasCoachingContact(it)) }
        listOf("3 sets of 10 reps", "Hold for 30 seconds", "Should my elbow be at 90 degrees?").forEach { assertFalse(it, hasCoachingContact(it)) }
    }
    @Test fun upgradingPreservesExistingNotificationIdsAndNewFollowUpsHaveDistinctIds() {
        val initial = question()
        assertEquals("question:q", NotificationEvents.question(initial).id)
        val answered = initial.copy(answer = "Ready position", answeredAt = 200)
        assertEquals("question-answer:q", NotificationEvents.answer(answered)?.id)
        val waiting = answered.copy(followUp1 = CoachingFollowUp("How low?", 300))
        assertEquals("question:q:1", NotificationEvents.question(waiting).id)
        assertEquals("question-answer:q", NotificationEvents.answer(waiting)?.id)
        val replied = waiting.copy(followUp1 = CoachingFollowUp("How low?", 300, "Comfortable stance", 400))
        assertEquals("question-answer:q:reply-0", NotificationEvents.answer(replied)?.id)
    }
}
