package com.volleyballlab.player.domain.repository

import com.volleyballlab.player.domain.model.AssignmentStatus
import com.volleyballlab.player.domain.model.CoachAssignment
import com.volleyballlab.player.domain.model.CoachLink
import com.volleyballlab.player.domain.model.CoachNote
import com.volleyballlab.player.domain.model.CoachPreview
import com.volleyballlab.player.domain.model.CoachProfile
import com.volleyballlab.player.domain.model.MembershipStatus
import com.volleyballlab.player.domain.model.RosterMember
import com.volleyballlab.player.domain.model.SessionSubmission
import com.volleyballlab.player.domain.model.SessionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Why a player's attempt to join a coach did not work. */
enum class JoinFailure {
    /** The player tried to join their own coach account. */
    OwnCode,

    /** The uid no longer names a coach - they signed out of coach mode. */
    UnknownCoach,

    /** There is already a pending or active link with this coach. */
    AlreadyLinked,

    /** Nobody is signed in, so there is no player to link. */
    NoSession,

    /** Coach connection is a Pro feature and this account is on Free. */
    NotPro,

    /** The cloud could not confirm the request; no connection was accepted. */
    Unavailable,
}

/** The result of actually sending the request. */
sealed interface JoinOutcome {
    data class Success(val link: CoachLink) : JoinOutcome
    data class Failure(val reason: JoinFailure) : JoinOutcome
}

/**
 * Everything on the coach side of the app: the team, its roster, the work set for
 * it, and the notes written back.
 *
 * ## One repository rather than four
 *
 * Roster, assignments, submissions and feedback are four tables in the document
 * (15, 16, and 17, plus the submission queue the figures imply) and they could be
 * four interfaces. They are one because every operation on them is scoped by the
 * same coach uid and most screens need two or three at once - the dashboard alone
 * reads the roster, the submission queue and the notes. Four repositories would
 * mean four DataStore reads racing to produce one screen and four places to
 * remember the scoping rule.
 *
 * ## What is deliberately absent: a network
 *
 * Every implementation of this is local to the device. That is a real constraint
 * and it must not be described as anything else: a player can only find a coach
 * whose account has been used on the same handset. The linkage is genuine - real
 * uids, real sessions - it is simply not distributed yet, because conflict 4 in
 * the capstone document has the network model specifying Cloud Firestore and the
 * data dictionary specifying MySQL, and conflict 5 notes coach accounts have no
 * home in the ERD at all. Writing either schema here would settle both on the
 * team's behalf.
 *
 * When the backend lands, this interface is what it implements. Nothing above it
 * changes.
 */
interface CoachRepository {

    /** A readable cloud failure; local implementations have no remote connection. */
    val syncError: Flow<String?> get() = flowOf(null)

    /** Restart reads after a connection failure. Does not approve or resend a request. */
    fun retrySync() = Unit

    // ---- The coach's own account ------------------------------------------

    /** The signed-in coach's profile, or [CoachProfile.Empty] when not a coach. */
    val coachProfile: Flow<CoachProfile>

    suspend fun saveCoachProfile(profile: CoachProfile)

    /**
     * Lists this coach in the directory players browse.
     *
     * Called when the coach shell opens rather than at sign-up, so an account that
     * became a coach through the switch in Profile still becomes findable.
     * Idempotent - it writes an empty profile only if there is none, and never
     * overwrites one the coach has filled in.
     */
    suspend fun publishListing()

    // ---- Roster ------------------------------------------------------------

    /** Everyone who has asked to join or been approved, newest request first. */
    val roster: Flow<List<RosterMember>>

    /** Accepts a pending request. Figure 27. */
    suspend fun approve(playerUid: String)

    /** Declines a pending request. */
    suspend fun decline(playerUid: String)

    /** Ends an active membership. Keeps the row, so history is not rewritten. */
    suspend fun removeFromRoster(playerUid: String)

    // ---- Assignments -------------------------------------------------------

    /** Every assignment this coach has written, newest first. */
    val assignments: Flow<List<CoachAssignment>>

    suspend fun saveAssignment(assignment: CoachAssignment)

    suspend fun deleteAssignment(id: String)

    // ---- Submissions and feedback -----------------------------------------

    /** Sessions players have sent this coach, newest first. */
    val submissions: Flow<List<SessionSubmission>>

    /** Notes this coach has written, newest first. */
    val notes: Flow<List<CoachNote>>

    /**
     * Records a note and marks the matching submission reviewed in one write.
     *
     * One call rather than two because a note that saves while the queue entry
     * stays pending leaves the coach re-reviewing a session they have already
     * answered, and there is no user-visible reason those should ever be apart.
     */
    suspend fun postNote(note: CoachNote)

    // ---- The player's side of the same link --------------------------------

    /** The signed-in player's link to a coach, or null when there is none. */
    val myCoach: Flow<CoachLink?>

    /** Notes addressed to the signed-in player, newest first. */
    val notesForMe: Flow<List<CoachNote>>

    /** Assignments set for the signed-in player, newest first. */
    val assignmentsForMe: Flow<List<CoachAssignment>>

    /** Sessions the signed-in player has submitted, newest first. */
    val mySubmissions: Flow<List<SessionSubmission>>

    /**
     * Every coach a player could ask, excluding the player themselves.
     *
     * Read-only and side-effect free, so browsing leaves no trace on anyone's
     * roster. See [CoachPreview] for what a listing does and does not carry.
     */
    val coachDirectory: Flow<List<CoachPreview>>

    /**
     * Sends the connection request. The membership starts [MembershipStatus.Pending]
     * and only the coach can move it on.
     */
    suspend fun requestJoin(coachUid: String): JoinOutcome

    /**
     * Withdraws a request, or leaves a team already joined.
     *
     * One call for both because they are the same write and the same intent from
     * the athlete's side - "I do not want this link" - and splitting them would
     * only mean two buttons that do the same thing depending on a state the
     * athlete is not thinking about.
     */
    suspend fun leaveCoach()

    suspend fun submitSession(sessionId: String)

    /** Returns only after the result is shared and the assignment is completed.
     * Failures propagate so the result screen can offer a retry.
     */
    suspend fun submitAssignmentSession(assignmentId: String, session: SessionResult)

    suspend fun updateAssignmentStatus(id: String, status: AssignmentStatus)
}
