package com.volleyballlab.player.data.cloud

import com.volleyballlab.player.domain.model.AssignmentStatus
import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.CoachAssignment
import com.volleyballlab.player.domain.model.CoachNote
import com.volleyballlab.player.domain.model.CoachProfile
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.CueScore
import com.volleyballlab.player.domain.model.DrillLevel
import com.volleyballlab.player.domain.model.MembershipStatus
import com.volleyballlab.player.domain.model.RepResult
import com.volleyballlab.player.domain.model.RosterMember
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.SessionAngleStage
import com.volleyballlab.player.domain.model.SessionSubmission
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.model.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract both clients share, pinned from the app side.
 *
 * These mappers are what the coach web portal reads, so a round-trip failure
 * here is a cross-client breakage, not a local display bug. Unknown enum names
 * must degrade, never throw: a row written by a newer client must not break
 * this one.
 */
class CloudSchemaTest {

    @Test
    fun `directory projection excludes private licence and notification preferences`() {
        val doc = CloudSchema.directoryDoc(CoachProfile(name = "Coach", organisation = "Club",
            licenceNumber = "Private", notifySubmissions = false), 3, 2000)
        assertEquals("Club", doc["organisation"])
        assertEquals(3, doc["rosterSize"])
        assertTrue(!doc.containsKey("licenceNumber"))
        assertTrue(!doc.containsKey("notifySubmissions"))
        assertTrue(!doc.containsKey("notifyWeeklyDigest"))
        assertTrue(!doc.containsKey("notifySafetyAlerts"))
    }

    @Test
    fun `assignment round-trips`() {
        val assignment = CoachAssignment(
            id = "a1",
            coachUid = "coach",
            playerUid = "player",
            title = "Cut shots",
            skill = Skill.Spiking,
            level = DrillLevel.Intermediate,
            summary = "s",
            instructions = "i",
            targetSets = 3,
            targetReps = 10,
            dueAtEpochMs = 123L,
            status = AssignmentStatus.InProgress,
            createdAtEpochMs = 100L,
        )
        val back = CloudSchema.assignmentOf(CloudSchema.assignmentDoc(assignment))
        assertEquals(assignment, back)
    }

    @Test
    fun `assignment with unknown skill is dropped, unknown level falls back`() {
        val doc = CloudSchema.assignmentDoc(
            CoachAssignment(
                id = "a1", coachUid = "c", playerUid = "p", title = "t",
                skill = Skill.Serving, level = DrillLevel.Beginner, summary = "",
                instructions = "", targetSets = 1, targetReps = 1,
                dueAtEpochMs = null, status = AssignmentStatus.Pending, createdAtEpochMs = 1L,
            ),
        ).toMutableMap()
        doc["skill"] = "Diving"
        assertNull(CloudSchema.assignmentOf(doc))
        doc["skill"] = "Serving"
        doc["level"] = "Expert"
        assertEquals(DrillLevel.Beginner, CloudSchema.assignmentOf(doc)?.level)
    }

    @Test
    fun `member round-trips and unknown status becomes pending`() {
        val member = RosterMember("p", "Maya", null, MembershipStatus.Active, 5L, 6L)
        assertEquals(member, CloudSchema.memberOf(CloudSchema.memberDoc(member)))

        val pending = CloudSchema.memberDoc(member).toMutableMap()
        pending["status"] = "Banned"
        assertEquals(MembershipStatus.Pending, CloudSchema.memberOf(pending).status)
    }

    @Test
    fun `submission and note round-trip`() {
        val submission = SessionSubmission("s1", "c", "p", 10L, null)
        assertEquals(submission, CloudSchema.submissionOf(CloudSchema.submissionDoc(submission)))

        val note = CoachNote("n1", "c", "p", "s1", "Bend the knees.", 11L)
        assertEquals(note, CloudSchema.noteOf(CloudSchema.noteDoc(note)))
    }

    @Test
    fun `session scores round-trip without pose data`() {
        val session = SessionResult(
            id = "s1",
            skill = Skill.Receiving,
            drillId = "d1",
            startedAtEpochMs = 1000L,
            durationMs = 60_000L,
            reps = listOf(
                RepResult(
                    index = 0, startMs = 0L, contactMs = 500L, endMs = 1000L,
                    cueScores = listOf(
                        CueScore(CueId.PassKneeFlexion, 128f, 0.9f),
                        CueScore(CueId.PassPlatformStraight, null, null),
                    ),
                ),
            ),
        )
        val doc = CloudSchema.sessionDoc(session, "player", listOf("coach"), 2000L)
        assertTrue((doc["sharedWith"] as List<*>).contains("coach"))
        // Scores only: no landmark arrays anywhere in the document.
        assertTrue(!doc.toString().contains("visibility"))

        val back = CloudSchema.sessionOf(doc)
        assertEquals(session.id, back?.id)
        assertEquals(session.skill, back?.skill)
        assertEquals(1, back?.reps?.size)
        assertEquals(128f, back?.reps?.get(0)?.cueScores?.get(0)?.measured)
        assertNull(back?.reps?.get(0)?.cueScores?.get(1)?.score)
    }

    @Test
    fun `session with unknown skill is dropped, unknown cues are skipped`() {
        val doc = CloudSchema.sessionDoc(
            SessionResult("s", Skill.Blocking, "d", 1L, 1L, emptyList()),
            "p", emptyList(), 2L,
        ).toMutableMap()
        doc["skill"] = "Diving"
        assertNull(CloudSchema.sessionOf(doc))
    }

    @Test
    fun `guided stage metadata round-trips without frames or video`() {
        val stages = listOf(
            SessionAngleStage(0, CameraAngle.Front, 10_000L, 20_000L, 5, 4, 3, 84),
            SessionAngleStage(1, CameraAngle.Rear, 25_000L, 30_000L, 2, 0, 0, null),
        )
        val session = SessionResult("guided", Skill.Receiving, "d", 1L, 30_000L, emptyList(), angleStages = stages)
        val doc = CloudSchema.sessionDoc(session, "p", emptyList(), 2L)
        assertEquals(stages, CloudSchema.sessionOf(doc)?.angleStages)
        assertTrue(!doc.containsKey("videoPath"))
        assertTrue(!doc.containsKey("frames"))
    }

    @Test
    fun `older cloud sessions have no angle stages and future angles remain readable`() {
        val doc = CloudSchema.sessionDoc(
            SessionResult("old", Skill.Serving, "d", 1L, 1L, emptyList()), "p", emptyList(), 2L,
        ).toMutableMap()
        doc.remove("angleStages")
        assertEquals(emptyList<SessionAngleStage>(), CloudSchema.sessionOf(doc)?.angleStages)
        doc["angleStages"] = listOf(mapOf(
            "stageIndex" to 0L, "angle" to "Diagonal", "startMs" to 10L, "endMs" to 20L,
            "liveRepCount" to 1L, "validRepCount" to 0L, "bestStreak" to 0L,
        ))
        assertEquals(CameraAngle.Unknown, CloudSchema.sessionOf(doc)?.angleStages?.single()?.angle)
        assertNull(CloudSchema.sessionOf(doc)?.angleStages?.single()?.estimatedFormScore)
    }

    @Test
    fun `user role distinguishes missing from unrecognised`() {
        assertNull(CloudSchema.userRoleOf(null))
        assertNull(CloudSchema.userRoleOf(emptyMap()))
        assertNull(CloudSchema.userRoleOf(mapOf("role" to "ADMIN")))
        assertEquals(UserRole.Coach, CloudSchema.userRoleOf(mapOf("role" to "COACH")))
        assertEquals(UserRole.Player, CloudSchema.userRoleOf(mapOf("role" to "PLAYER")))
    }
}
