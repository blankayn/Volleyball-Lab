package com.volleyballlab.player.domain.model

import org.junit.Assert.*
import org.junit.Test

class PushNotificationTest {
    private val payload = mapOf(
        "uid" to "player", "type" to "feedback", "eventId" to "feedback:note",
        "title" to "Coach feedback", "body" to "Keep practicing.", "createdAt" to "1000",
        "referenceId" to "session",
    )

    @Test fun `a queued push for another account or signed out user is rejected`() {
        assertNull(NotificationEvents.push(payload, "other"))
        assertNull(NotificationEvents.push(payload, null))
        assertNull(NotificationEvents.push(payload - "uid", "player"))
    }

    @Test fun `push retains the cloud event identity and refresh preserves read delivery state`() {
        val push = requireNotNull(NotificationEvents.push(payload, "player"))
        assertEquals("feedback:note", push.id)
        assertEquals("session", push.referenceId)
        assertEquals(TrainingNotificationType.FeedbackDelivered, push.type)
        assertEquals(1000L, push.createdAtEpochMs)
        val saved = push.copy(isRead = true, delivered = true)
        assertEquals(listOf(saved), NotificationEvents.merge(listOf(saved), listOf(push, push)))
    }

    @Test fun `malformed and unsupported pushes never enter the inbox`() {
        listOf("createdAt" to "bad", "createdAt" to "-1", "eventId" to "", "title" to " ",
            "body" to "", "type" to "unknown").forEach { (key, value) ->
            assertNull(NotificationEvents.push(payload + (key to value), "player"))
        }
        assertNull(NotificationEvents.push(payload - "eventId", "player"))
    }

    @Test fun `coach approval and closure use the same identity as backend delivery`() {
        val pending = RosterMember("player", "Athlete", null, MembershipStatus.Pending, 1000)
        assertNull(NotificationEvents.connection("coach", pending))
        val approved = requireNotNull(NotificationEvents.connection("coach", pending.copy(
            status = MembershipStatus.Active, decidedAtEpochMs = 2000)))
        assertEquals("connection:coach:1000:Active:2000", approved.id)
        assertEquals("Coach request approved", approved.title)
        val ended = requireNotNull(NotificationEvents.connection("coach", pending.copy(
            status = MembershipStatus.Ended, decidedAtEpochMs = 2000)))
        assertEquals("connection:coach:1000:Ended:2000", ended.id)
        assertEquals("Coach connection ended", ended.title)
        assertEquals(TrainingNotificationType.CoachConnection, ended.type)
        assertNull(NotificationEvents.connection("coach", pending.copy(status = MembershipStatus.Active)))
    }
}
