package com.volleyballlab.player.data.coach

import com.volleyballlab.player.domain.model.AssignmentStatus
import com.volleyballlab.player.domain.model.CoachAssignment
import com.volleyballlab.player.domain.model.CoachNote
import com.volleyballlab.player.domain.model.CoachProfile
import com.volleyballlab.player.domain.model.CoachSpecialisation
import com.volleyballlab.player.domain.model.CourtPosition
import com.volleyballlab.player.domain.model.DrillLevel
import com.volleyballlab.player.domain.model.MembershipStatus
import com.volleyballlab.player.domain.model.RosterMember
import com.volleyballlab.player.domain.model.SessionSubmission
import com.volleyballlab.player.domain.model.Skill
import kotlinx.serialization.Serializable

/**
 * The on-disk shapes for everything the coach side stores.
 *
 * Same split, and the same reason, as `SessionRecords`: the domain models stay a
 * description of a team, and this file is the only place that says how one is
 * written down. Whichever backend settles conflict 4 replaces this file rather than
 * unpicking the domain.
 *
 * Every enum is stored by [Enum.name], which makes the constant names a wire
 * format, and every read of one is tolerant - an unrecognised value degrades to a
 * sensible default rather than throwing away the whole record. A roster that
 * refuses to load because one member has a status this build has not heard of would
 * cost a coach their entire team for one row.
 */
@Serializable
internal data class CoachProfileRecord(
    val name: String = "",
    val teamName: String = "",
    val organisation: String = "",
    val specialisation: String? = null,
    val yearsExperience: Int? = null,
    val licenceNumber: String = "",
    val notifySubmissions: Boolean = true,
    val notifyWeeklyDigest: Boolean = true,
    val notifySafetyAlerts: Boolean = true,
)

@Serializable
internal data class RosterMemberRecord(
    val playerUid: String,
    val playerName: String = "",
    val position: String? = null,
    val status: String = "",
    val requestedAtEpochMs: Long = 0L,
    val decidedAtEpochMs: Long? = null,
)

@Serializable
internal data class AssignmentRecord(
    val id: String,
    val coachUid: String,
    val playerUid: String,
    val title: String = "",
    val skill: String = "",
    val level: String = "",
    val summary: String = "",
    val instructions: String = "",
    val targetSets: Int = CoachAssignment.DEFAULT_SETS,
    val targetReps: Int = CoachAssignment.DEFAULT_REPS,
    val dueAtEpochMs: Long? = null,
    val status: String = "",
    val createdAtEpochMs: Long = 0L,
    val libraryDrillId: String? = null,
)

@Serializable
internal data class SubmissionRecord(
    val sessionId: String,
    val coachUid: String,
    val playerUid: String,
    val submittedAtEpochMs: Long = 0L,
    val reviewedAtEpochMs: Long? = null,
)

@Serializable
internal data class NoteRecord(
    val id: String,
    val coachUid: String,
    val playerUid: String,
    val sessionId: String? = null,
    val text: String = "",
    val createdAtEpochMs: Long = 0L,
)

// ---------------------------------------------------------------------------
// Domain -> record
// ---------------------------------------------------------------------------

internal fun CoachProfile.toRecord() = CoachProfileRecord(
    name = name,
    teamName = teamName,
    organisation = organisation,
    specialisation = specialisation?.name,
    yearsExperience = yearsExperience,
    licenceNumber = licenceNumber,
    notifySubmissions = notifySubmissions,
    notifyWeeklyDigest = notifyWeeklyDigest,
    notifySafetyAlerts = notifySafetyAlerts,
)

internal fun RosterMember.toRecord() = RosterMemberRecord(
    playerUid = playerUid,
    playerName = playerName,
    position = position?.name,
    status = status.name,
    requestedAtEpochMs = requestedAtEpochMs,
    decidedAtEpochMs = decidedAtEpochMs,
)

internal fun CoachAssignment.toRecord() = AssignmentRecord(
    id = id,
    coachUid = coachUid,
    playerUid = playerUid,
    title = title,
    skill = skill.name,
    level = level.name,
    summary = summary,
    instructions = instructions,
    targetSets = targetSets,
    targetReps = targetReps,
    dueAtEpochMs = dueAtEpochMs,
    status = status.name,
    createdAtEpochMs = createdAtEpochMs,
    libraryDrillId = libraryDrillId,
)

internal fun SessionSubmission.toRecord() = SubmissionRecord(
    sessionId = sessionId,
    coachUid = coachUid,
    playerUid = playerUid,
    submittedAtEpochMs = submittedAtEpochMs,
    reviewedAtEpochMs = reviewedAtEpochMs,
)

internal fun CoachNote.toRecord() = NoteRecord(
    id = id,
    coachUid = coachUid,
    playerUid = playerUid,
    sessionId = sessionId,
    text = text,
    createdAtEpochMs = createdAtEpochMs,
)

// ---------------------------------------------------------------------------
// Record -> domain
// ---------------------------------------------------------------------------

internal fun CoachProfileRecord.toDomain() = CoachProfile(
    name = name,
    teamName = teamName,
    organisation = organisation,
    specialisation = specialisation?.let { stored ->
        CoachSpecialisation.entries.firstOrNull { it.name == stored }
    },
    yearsExperience = yearsExperience,
    licenceNumber = licenceNumber,
    notifySubmissions = notifySubmissions,
    notifyWeeklyDigest = notifyWeeklyDigest,
    notifySafetyAlerts = notifySafetyAlerts,
)

internal fun RosterMemberRecord.toDomain() = RosterMember(
    playerUid = playerUid,
    playerName = playerName,
    position = position?.let { stored ->
        CourtPosition.entries.firstOrNull { it.name == stored }
    },
    // An unknown status becomes Pending rather than Active: erring toward "the
    // coach has not decided yet" cannot accidentally open a player's training to a
    // coach, whereas erring toward Active can.
    status = MembershipStatus.entries.firstOrNull { it.name == status }
        ?: MembershipStatus.Pending,
    requestedAtEpochMs = requestedAtEpochMs,
    decidedAtEpochMs = decidedAtEpochMs,
)

/**
 * Reads an assignment back, or null if it names a skill this build does not have.
 *
 * Same rule as a session record: without a resolvable skill the assignment cannot
 * be drawn, filtered or acted on, so it is dropped. A bad *level* is different -
 * the work is still meaningful - so that one falls back rather than failing.
 */
internal fun AssignmentRecord.toDomain(): CoachAssignment? {
    val resolvedSkill = Skill.entries.firstOrNull { it.name == skill } ?: return null
    return CoachAssignment(
        id = id,
        coachUid = coachUid,
        playerUid = playerUid,
        title = title,
        skill = resolvedSkill,
        level = DrillLevel.entries.firstOrNull { it.name == level } ?: DrillLevel.Beginner,
        summary = summary,
        instructions = instructions,
        targetSets = targetSets,
        targetReps = targetReps,
        dueAtEpochMs = dueAtEpochMs,
        status = AssignmentStatus.entries.firstOrNull { it.name == status }
            ?: AssignmentStatus.Pending,
        createdAtEpochMs = createdAtEpochMs,
        libraryDrillId = libraryDrillId,
    )
}

internal fun SubmissionRecord.toDomain() = SessionSubmission(
    sessionId = sessionId,
    coachUid = coachUid,
    playerUid = playerUid,
    submittedAtEpochMs = submittedAtEpochMs,
    reviewedAtEpochMs = reviewedAtEpochMs,
)

internal fun NoteRecord.toDomain() = CoachNote(
    id = id,
    coachUid = coachUid,
    playerUid = playerUid,
    sessionId = sessionId,
    text = text,
    createdAtEpochMs = createdAtEpochMs,
)
