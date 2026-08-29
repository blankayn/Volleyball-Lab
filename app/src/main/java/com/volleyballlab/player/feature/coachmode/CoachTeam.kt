package com.volleyballlab.player.feature.coachmode

import com.volleyballlab.player.domain.model.CoachAssignment
import com.volleyballlab.player.domain.model.CoachNote
import com.volleyballlab.player.domain.model.CoachProfile
import com.volleyballlab.player.domain.model.MembershipStatus
import com.volleyballlab.player.domain.model.PlayerSnapshot
import com.volleyballlab.player.domain.model.RosterMember
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.SessionSubmission
import com.volleyballlab.player.domain.model.TeamSummary
import com.volleyballlab.player.domain.repository.CoachRepository
import com.volleyballlab.player.domain.repository.SessionDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * One entry in the review queue, with everything the row needs already resolved.
 *
 * The submission alone is two ids. Pairing it with the member and the session here
 * means the dashboard is not doing lookups inside a `LazyColumn` item, which is
 * where a list starts dropping frames.
 *
 * @param session null when the athlete has since deleted the recording. The row
 *   still appears - the coach was asked to look at something and is entitled to
 *   know it is gone - but it cannot be opened.
 */
data class QueueEntry(
    val submission: SessionSubmission,
    val member: RosterMember?,
    val session: SessionResult?,
)

/**
 * Everything the coach shell knows, in one value.
 *
 * ## Why one state rather than a flow per screen
 *
 * Four of the five coach screens are views of the same three facts - who is on the
 * team, what they have recorded, what is outstanding. Giving each screen its own
 * pipeline would mean four independent recomputations of the same snapshots, and
 * four chances for two screens to disagree about the team's average form because
 * one of them filtered the roster slightly differently.
 *
 * The cost is that every coach screen recomputes when any of it changes. At squad
 * scale - tens of players, hundreds of sessions - that is arithmetic over a list
 * that already fits in memory, and it happens off the main thread in the flow.
 */
data class CoachTeamState(
    val profile: CoachProfile = CoachProfile.Empty,
    val roster: List<RosterMember> = emptyList(),
    val snapshots: List<PlayerSnapshot> = emptyList(),
    val summary: TeamSummary = TeamSummary.Empty,
    val queue: List<QueueEntry> = emptyList(),
    val assignments: List<CoachAssignment> = emptyList(),
    val notes: List<CoachNote> = emptyList(),
    /**
     * Every active player's full history, keyed by uid.
     *
     * Kept alongside [snapshots] because [PlayerSnapshot.recentSessions] is capped
     * at [PlayerSnapshot.HISTORY_SIZE] for the list that renders it, and the review
     * screen must be able to open a session that has since fallen off the end of
     * that list. Resolving through the snapshot would make an old submission read
     * as a deleted one.
     */
    val sessionsByUid: Map<String, List<SessionResult>> = emptyMap(),
    /**
     * True when everything on this screen is [SampleTeam] scaffolding.
     *
     * Screens are *required* to surface this - a Sample chip on every row that
     * came from it, and a plain-words note on the dashboard. The fixture only
     * earns its place by being obviously fake; see [SampleTeam] for the argument.
     */
    val isSample: Boolean = false,
    /** False until the first read of the store lands, so screens can hold still. */
    val loaded: Boolean = false,
) {
    val activeMembers: List<RosterMember> get() = roster.filter { it.isActive }

    /**
     * Requests waiting on the coach, oldest first.
     *
     * Oldest first rather than newest: a request that has been waiting three days
     * should not sink below one that arrived this morning.
     */
    val pendingRequests: List<RosterMember>
        get() = roster.filter { it.isPending }.sortedBy { it.requestedAtEpochMs }

    val pastMembers: List<RosterMember>
        get() = roster.filter { it.status == MembershipStatus.Ended }

    val pendingQueue: List<QueueEntry> get() = queue.filter { it.submission.isPending }

    fun snapshotOf(playerUid: String): PlayerSnapshot? =
        snapshots.firstOrNull { it.member.playerUid == playerUid }

    /** One session of one rostered player, or null if it is no longer stored. */
    fun sessionOf(playerUid: String, sessionId: String): SessionResult? =
        sessionsByUid[playerUid]?.firstOrNull { it.id == sessionId }

    fun assignmentsFor(playerUid: String): List<CoachAssignment> =
        assignments.filter { it.playerUid == playerUid }

    fun notesFor(playerUid: String): List<CoachNote> =
        notes.filter { it.playerUid == playerUid }

    val hasTeam: Boolean get() = roster.isNotEmpty()
}

/**
 * Builds [CoachTeamState] from the repositories.
 *
 * A free function rather than a base ViewModel class. Each coach screen owns a
 * plain ViewModel that calls this and projects what it needs; inheritance would
 * have bought nothing except a place for screens to start reaching into each
 * other's state.
 *
 * @param now injected so tests can pin the clock. Everything time-dependent in the
 *   snapshots - the thirty-day window, overdue assignments, sets this week - reads
 *   it, and a test that cannot pin it can only assert on things that do not move.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun coachTeamState(
    coachRepository: CoachRepository,
    sessionDirectory: SessionDirectory,
    now: () -> Long = System::currentTimeMillis,
): Flow<CoachTeamState> = combine(
    coachRepository.coachProfile,
    coachRepository.roster,
    coachRepository.assignments,
    coachRepository.submissions,
    coachRepository.notes,
) { profile, roster, assignments, submissions, notes ->
    // Bundled into an intermediate so the flatMap below has one thing to switch on.
    // combine() caps out at five sources without a vararg, and the sessions flow
    // depends on the roster, so it could not have been a sixth anyway.
    Bundle(profile, roster, assignments, submissions, notes)
}.flatMapLatest { bundle ->
    // The fixture stands in only for a roster that is genuinely empty, which is
    // what makes it safe: it can never be mixed in beside a real athlete, and it
    // disappears the moment one is added rather than having to be cleaned up.
    val showSample = bundle.roster.isEmpty()

    val uids = bundle.roster.filter { it.isActive }.map { it.playerUid }

    // Every uid here came out of the roster flow above - never from a screen. That
    // is the whole of what keeps SessionDirectory honest; see its KDoc.
    val sessionsFlow: Flow<Map<String, List<SessionResult>>> = if (uids.isEmpty()) {
        // combine() over an empty list never emits, which would leave the coach
        // shell stuck on its loading state forever the moment a roster is empty -
        // which is exactly how every coach account starts.
        flowOf(emptyMap())
    } else {
        combine(uids.map { uid -> sessionDirectory.sessionsOf(uid).map { uid to it } }) {
            it.toMap()
        }
    }

    sessionsFlow.map { realSessions ->
        val timestamp = now()

        // Everything below reads these three rather than the bundle directly, so
        // the fixture flows through the *same* derivations a real squad does -
        // PlayerSnapshot, TeamSummary and SessionReview never learn that they are
        // scoring scaffolding. That is deliberate: a demo that bypassed the
        // arithmetic would be showing numbers nobody computed.
        val roster = if (showSample) SampleTeam.roster(timestamp) else bundle.roster
        val sessionsByUid =
            if (showSample) SampleTeam.sessions(timestamp) else realSessions
        val submissions =
            if (showSample) SampleTeam.submissions(timestamp) else bundle.submissions

        val snapshots = roster.map { member ->
            val sessions = sessionsByUid[member.playerUid].orEmpty()
            PlayerSnapshot.build(
                member = member,
                sessions = sessions,
                openAssignments = bundle.assignments.count {
                    it.playerUid == member.playerUid && it.isOpen
                },
                pendingSubmissions = submissions.count {
                    it.playerUid == member.playerUid && it.isPending
                },
                nowEpochMs = timestamp,
            )
        }

        val byUid = roster.associateBy { it.playerUid }

        CoachTeamState(
            profile = bundle.profile,
            roster = roster,
            snapshots = snapshots,
            summary = TeamSummary.from(
                snapshots = snapshots,
                pendingReviews = submissions.count { it.isPending },
                nowEpochMs = timestamp,
            ),
            queue = submissions.map { submission ->
                QueueEntry(
                    submission = submission,
                    member = byUid[submission.playerUid],
                    session = sessionsByUid[submission.playerUid]
                        ?.firstOrNull { it.id == submission.sessionId },
                )
            },
            assignments = bundle.assignments,
            notes = bundle.notes,
            sessionsByUid = sessionsByUid,
            isSample = showSample,
            loaded = true,
        )
    }
}

/** Carrier for the repository flows, so the sessions flow can depend on them. */
private data class Bundle(
    val profile: CoachProfile,
    val roster: List<RosterMember>,
    val assignments: List<CoachAssignment>,
    val submissions: List<SessionSubmission>,
    val notes: List<CoachNote>,
)
