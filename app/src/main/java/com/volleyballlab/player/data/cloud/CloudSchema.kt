package com.volleyballlab.player.data.cloud

import com.volleyballlab.player.domain.model.AssignmentStatus
import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.CoachAssignment
import com.volleyballlab.player.domain.model.CoachNote
import com.volleyballlab.player.domain.model.CoachPreview
import com.volleyballlab.player.domain.model.CoachProfile
import com.volleyballlab.player.domain.model.CoachSpecialisation
import com.volleyballlab.player.domain.model.CourtPosition
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

/**
 * The Firestore layout both clients share. Field-for-field with
 * `docs/coach-cloud-contract.md` — the doc explains *why*, this file is the
 * *how* for the app side. The web portal mirrors it in `coach-web/src/lib/cloud.ts`;
 * changing one without the other breaks cross-client reads, so both files name
 * this contract in their headers.
 *
 * Every mapper here takes a plain `Map<String, Any?>` — the shape
 * `DocumentSnapshot.data` already returns — rather than a Firestore type, so
 * this file is pure JVM and unit-testable with no Android runtime. Unknown enum
 * names degrade to the same defaults the DataStore records use, never a throw:
 * a row written by a newer client must not break this one.
 */
object CloudSchema {

    // ------------------------------------------------------------------
    // Collections
    // ------------------------------------------------------------------
    const val USERS = "users"
    const val COACHES = "coaches"
    const val DIRECTORY = "coachDirectory"
    const val ROSTER = "roster"
    const val MEMBERS = "members"
    const val ASSIGNMENTS = "assignments"
    const val SUBMISSIONS = "submissions"
    const val NOTES = "notes"
    const val SESSIONS = "sessions"

    fun submissionId(coachUid: String, sessionId: String) = "${coachUid}_${sessionId}"

    // ------------------------------------------------------------------
    // Primitives
    // ------------------------------------------------------------------
    fun str(map: Map<String, Any?>, key: String): String = map[key] as? String ?: ""

    fun strOrNull(map: Map<String, Any?>, key: String): String? = map[key] as? String

    fun num(map: Map<String, Any?>, key: String): Long = when (val v = map[key]) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> v.toLong()
        is Float -> v.toLong()
        is Number -> v.toLong()
        else -> 0L
    }

    fun numOrNull(map: Map<String, Any?>, key: String): Long? =
        if (map[key] == null) null else num(map, key)

    fun doubleOrNull(map: Map<String, Any?>, key: String): Double? = when (val v = map[key]) {
        is Double -> v
        is Float -> v.toDouble()
        is Long -> v.toDouble()
        is Int -> v.toDouble()
        is Number -> v.toDouble()
        else -> null
    }

    fun floatOrNull(map: Map<String, Any?>, key: String): Float? = doubleOrNull(map, key)?.toFloat()

    fun bool(map: Map<String, Any?>, key: String, default: Boolean = true): Boolean =
        map[key] as? Boolean ?: default

    @Suppress("UNCHECKED_CAST")
    fun strList(map: Map<String, Any?>, key: String): List<String> =
        (map[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    // ------------------------------------------------------------------
    // users/{uid}
    // ------------------------------------------------------------------
    fun userDoc(role: UserRole, displayName: String, email: String, nowMs: Long): Map<String, Any?> =
        mapOf(
            "role" to role.name,
            "displayName" to displayName,
            "email" to email,
            "createdAt" to nowMs,
            "updatedAt" to nowMs,
        )

    fun userRoleOf(data: Map<String, Any?>?): UserRole? {
        // The contract stores "PLAYER"/"COACH" while the app enum reads
        // Player/Coach, so this maps explicitly rather than by Enum.name — and
        // an unrecognised name still degrades to null ("no cloud row") rather
        // than to Player, which the caller must distinguish from explicit.
        return when ((data?.get("role") as? String)?.uppercase()) {
            "COACH" -> UserRole.Coach
            "PLAYER" -> UserRole.Player
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // coaches/{uid} + coachDirectory/{uid}
    // ------------------------------------------------------------------
    fun coachDoc(profile: CoachProfile, nowMs: Long): Map<String, Any?> = mapOf(
        "name" to profile.name,
        "teamName" to profile.teamName,
        "organisation" to profile.organisation,
        "specialisation" to profile.specialisation?.name,
        "yearsExperience" to profile.yearsExperience,
        "licenceNumber" to profile.licenceNumber,
        "notifySubmissions" to profile.notifySubmissions,
        "notifyWeeklyDigest" to profile.notifyWeeklyDigest,
        "notifySafetyAlerts" to profile.notifySafetyAlerts,
        "updatedAt" to nowMs,
    )

    fun profileOf(data: Map<String, Any?>): CoachProfile = CoachProfile(
        name = str(data, "name"),
        teamName = str(data, "teamName"),
        organisation = str(data, "organisation"),
        specialisation = strOrNull(data, "specialisation")?.let { raw ->
            CoachSpecialisation.entries.firstOrNull { it.name == raw }
        },
        yearsExperience = numOrNull(data, "yearsExperience")?.toInt(),
        licenceNumber = str(data, "licenceNumber"),
        notifySubmissions = bool(data, "notifySubmissions"),
        notifyWeeklyDigest = bool(data, "notifyWeeklyDigest"),
        notifySafetyAlerts = bool(data, "notifySafetyAlerts"),
    )

    /** The public projection: licence and notification preferences stay private. */
    fun directoryDoc(profile: CoachProfile, rosterSize: Int, nowMs: Long): Map<String, Any?> = mapOf(
        "name" to profile.name,
        "teamName" to profile.teamName,
        "organisation" to profile.organisation,
        "specialisation" to profile.specialisation?.name,
        "yearsExperience" to profile.yearsExperience,
        "rosterSize" to rosterSize,
        "updatedAt" to nowMs,
    )

    fun previewOf(coachUid: String, data: Map<String, Any?>): CoachPreview = CoachPreview(
        coachUid = coachUid,
        profile = profileOf(data),
        rosterSize = num(data, "rosterSize").toInt(),
    )

    // ------------------------------------------------------------------
    // roster/{coach}/members/{player}
    // ------------------------------------------------------------------
    fun memberDoc(member: RosterMember): Map<String, Any?> = mapOf(
        "playerUid" to member.playerUid,
        "playerName" to member.playerName,
        "position" to member.position?.name,
        "status" to member.status.name,
        "requestedAt" to member.requestedAtEpochMs,
        "decidedAt" to member.decidedAtEpochMs,
    )

    fun memberOf(data: Map<String, Any?>): RosterMember = RosterMember(
        playerUid = str(data, "playerUid"),
        playerName = str(data, "playerName"),
        position = strOrNull(data, "position")?.let { raw ->
            CourtPosition.entries.firstOrNull { it.name == raw }
        },
        // An unknown status becomes Pending rather than Active: erring toward
        // "not decided yet" cannot open training to a coach, the reverse can.
        status = strOrNull(data, "status")?.let { raw ->
            MembershipStatus.entries.firstOrNull { it.name == raw }
        } ?: MembershipStatus.Pending,
        requestedAtEpochMs = num(data, "requestedAt"),
        decidedAtEpochMs = numOrNull(data, "decidedAt"),
    )

    // ------------------------------------------------------------------
    // assignments/{id}
    // ------------------------------------------------------------------
    fun assignmentDoc(a: CoachAssignment): Map<String, Any?> = mapOf(
        "id" to a.id,
        "coachUid" to a.coachUid,
        "playerUid" to a.playerUid,
        "title" to a.title,
        "skill" to a.skill.name,
        "level" to a.level.name,
        "summary" to a.summary,
        "instructions" to a.instructions,
        "targetSets" to a.targetSets,
        "targetReps" to a.targetReps,
        "dueAt" to a.dueAtEpochMs,
        "status" to a.status.name,
        "createdAt" to a.createdAtEpochMs,
        "libraryDrillId" to a.libraryDrillId,
    )

    /** Null when the skill is unresolvable — without a skill the row cannot be drawn. */
    fun assignmentOf(data: Map<String, Any?>): CoachAssignment? {
        val skill = strOrNull(data, "skill")?.let { raw ->
            Skill.entries.firstOrNull { it.name == raw }
        } ?: return null
        return CoachAssignment(
            id = str(data, "id"),
            coachUid = str(data, "coachUid"),
            playerUid = str(data, "playerUid"),
            title = str(data, "title"),
            skill = skill,
            level = strOrNull(data, "level")?.let { raw ->
                DrillLevel.entries.firstOrNull { it.name == raw }
            } ?: DrillLevel.Beginner,
            summary = str(data, "summary"),
            instructions = str(data, "instructions"),
            targetSets = num(data, "targetSets").toInt().coerceAtLeast(1),
            targetReps = num(data, "targetReps").toInt().coerceAtLeast(1),
            dueAtEpochMs = numOrNull(data, "dueAt"),
            status = strOrNull(data, "status")?.let { raw ->
                AssignmentStatus.entries.firstOrNull { it.name == raw }
            } ?: AssignmentStatus.Pending,
            createdAtEpochMs = num(data, "createdAt"),
            libraryDrillId = strOrNull(data, "libraryDrillId"),
        )
    }

    // ------------------------------------------------------------------
    // submissions/{coach_session}
    // ------------------------------------------------------------------
    fun submissionDoc(s: SessionSubmission): Map<String, Any?> = mapOf(
        "sessionId" to s.sessionId,
        "coachUid" to s.coachUid,
        "playerUid" to s.playerUid,
        "submittedAt" to s.submittedAtEpochMs,
        "reviewedAt" to s.reviewedAtEpochMs,
    )

    fun submissionOf(data: Map<String, Any?>): SessionSubmission = SessionSubmission(
        sessionId = str(data, "sessionId"),
        coachUid = str(data, "coachUid"),
        playerUid = str(data, "playerUid"),
        submittedAtEpochMs = num(data, "submittedAt"),
        reviewedAtEpochMs = numOrNull(data, "reviewedAt"),
    )

    // ------------------------------------------------------------------
    // notes/{id}
    // ------------------------------------------------------------------
    fun noteDoc(n: CoachNote): Map<String, Any?> = mapOf(
        "id" to n.id,
        "coachUid" to n.coachUid,
        "playerUid" to n.playerUid,
        "sessionId" to n.sessionId,
        "text" to n.text,
        "createdAt" to n.createdAtEpochMs,
    )

    fun noteOf(data: Map<String, Any?>): CoachNote = CoachNote(
        id = str(data, "id"),
        coachUid = str(data, "coachUid"),
        playerUid = str(data, "playerUid"),
        sessionId = strOrNull(data, "sessionId"),
        text = str(data, "text"),
        createdAtEpochMs = num(data, "createdAt"),
    )

    // ------------------------------------------------------------------
    // sessions/{id} — scores only; pose data never leaves the phone.
    // ------------------------------------------------------------------
    @Suppress("UNCHECKED_CAST")
    fun sessionDoc(s: SessionResult, ownerUid: String, sharedWith: List<String>, nowMs: Long): Map<String, Any?> =
        mapOf(
            "id" to s.id,
            "ownerUid" to ownerUid,
            "skill" to s.skill.name,
            "drillId" to s.drillId,
            "startedAt" to s.startedAtEpochMs,
            "durationMs" to s.durationMs,
            "repCount" to s.repCount,
            "formScore" to s.formScore(),
            "weakestCue" to s.weakestCue()?.name,
            "angleStages" to s.angleStages.map { stage ->
                mapOf(
                    "stageIndex" to stage.stageIndex,
                    "angle" to stage.angle.name,
                    "startMs" to stage.startMs,
                    "endMs" to stage.endMs,
                    "liveRepCount" to stage.liveRepCount,
                    "validRepCount" to stage.validRepCount,
                    "bestStreak" to stage.bestStreak,
                    "estimatedFormScore" to stage.estimatedFormScore,
                )
            },
            "reps" to s.reps.map { rep ->
                mapOf(
                    "index" to rep.index,
                    "startMs" to rep.startMs,
                    "contactMs" to rep.contactMs,
                    "endMs" to rep.endMs,
                    "cueScores" to rep.cueScores.map { cs ->
                        mapOf(
                            "cueId" to cs.cueId.name,
                            "measured" to cs.measured,
                            "score" to cs.score,
                        )
                    },
                )
            },
            "sharedWith" to sharedWith,
            "createdAt" to s.startedAtEpochMs,
            "updatedAt" to nowMs,
        )

    @Suppress("UNCHECKED_CAST")
    fun sessionOf(data: Map<String, Any?>): SessionResult? {
        val skill = strOrNull(data, "skill")?.let { raw ->
            Skill.entries.firstOrNull { it.name == raw }
        } ?: return null
        val reps = ((data["reps"] as? List<*>) ?: emptyList<Any>()).mapNotNull { raw ->
            val rep = raw as? Map<String, Any?> ?: return@mapNotNull null
            val cues = ((rep["cueScores"] as? List<*>) ?: emptyList<Any>()).mapNotNull { rawCs ->
                val cs = rawCs as? Map<String, Any?> ?: return@mapNotNull null
                val cueId = (cs["cueId"] as? String)?.let { name ->
                    CueId.entries.firstOrNull { it.name == name }
                } ?: return@mapNotNull null
                CueScore(cueId = cueId, measured = floatOrNull(cs, "measured"), score = floatOrNull(cs, "score"))
            }
            RepResult(
                index = num(rep, "index").toInt(),
                startMs = num(rep, "startMs"),
                contactMs = num(rep, "contactMs"),
                endMs = num(rep, "endMs"),
                cueScores = cues,
            )
        }
        return SessionResult(
            id = str(data, "id"),
            skill = skill,
            drillId = str(data, "drillId"),
            startedAtEpochMs = num(data, "startedAt"),
            durationMs = num(data, "durationMs"),
            reps = reps,
            angleStages = ((data["angleStages"] as? List<*>) ?: emptyList<Any>()).mapNotNull { raw ->
                val stage = raw as? Map<String, Any?> ?: return@mapNotNull null
                SessionAngleStage(
                    stageIndex = num(stage, "stageIndex").toInt(),
                    angle = CameraAngle.entries.firstOrNull { it.name == str(stage, "angle") }
                        ?: CameraAngle.Unknown,
                    startMs = num(stage, "startMs"),
                    endMs = num(stage, "endMs"),
                    liveRepCount = num(stage, "liveRepCount").toInt(),
                    validRepCount = num(stage, "validRepCount").toInt(),
                    bestStreak = num(stage, "bestStreak").toInt(),
                    estimatedFormScore = numOrNull(stage, "estimatedFormScore")?.toInt(),
                )
            },
        )
    }
}
