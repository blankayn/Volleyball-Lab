package com.volleyballlab.player.data.coach

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.volleyballlab.player.data.UserScope
import com.volleyballlab.player.domain.model.AssignmentStatus
import com.volleyballlab.player.domain.model.CoachAssignment
import com.volleyballlab.player.domain.model.CoachLink
import com.volleyballlab.player.domain.model.CoachNote
import com.volleyballlab.player.domain.model.CoachPreview
import com.volleyballlab.player.domain.model.CoachProfile
import com.volleyballlab.player.domain.model.MembershipStatus
import com.volleyballlab.player.domain.model.RosterMember
import com.volleyballlab.player.domain.model.SessionSubmission
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.CoachRepository
import com.volleyballlab.player.domain.repository.JoinFailure
import com.volleyballlab.player.domain.repository.JoinOutcome
import com.volleyballlab.player.domain.repository.ProfileRepository
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/** One DataStore per file for the process, as the API requires. */
private val Context.coachStore: DataStore<Preferences> by preferencesDataStore(name = "coach_mode")

/**
 * The coach side, persisted locally as JSON blobs in Preferences DataStore.
 *
 * ## Layout of the file
 *
 * Five kinds of key, each scoped to a coach uid through [UserScope]:
 *
 * ```
 * coach_profile:<coachUid>  a CoachProfileRecord
 * roster:<coachUid>         a list of RosterMemberRecord
 * assignments:<coachUid>    a list of AssignmentRecord
 * submissions:<coachUid>    a list of SubmissionRecord
 * notes:<coachUid>          a list of NoteRecord
 * ```
 *
 * There is no separate directory index. The `coach_profile` rows are what players
 * browse, which is one fewer thing to keep in step - an index would be a second
 * copy of the same fact, free to disagree with it.
 *
 * ## Why the player-side flows scan every coach
 *
 * [notesForMe], [assignmentsForMe] and the rest walk every `notes:*` /
 * `assignments:*` key rather than looking up "my coach" first. It is a handful of
 * entries on one phone, and the alternative - a second index from player uid to
 * coach uid - is a denormalisation that can disagree with the roster it was derived
 * from. One source of truth, scanned, beats two that drift.
 *
 * ## The limit, stated where a reader will hit it
 *
 * This is all local. A player can only find a coach if both accounts
 * have been used on this handset. See [CoachRepository] for why that is where the
 * project is, and what replaces this when it is not.
 */
class DataStoreCoachRepository(
    private val context: Context,
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    /** Injected so tests can pin it; production passes the system clock. */
    private val now: () -> Long = System::currentTimeMillis,
) : CoachRepository {

    /**
     * The backing file, read-guarded.
     *
     * Declared before every flow that builds on it, because property initialisers
     * run in declaration order and a flow built above this one would capture null.
     */
    private val storeData: Flow<Preferences> = context.coachStore.data.catch { failure ->
        // An unreadable coach file must not take the app down. The coach sees an
        // empty team, which is wrong but recoverable; a crash loop is neither.
        if (failure is IOException) emit(emptyPreferences()) else throw failure
    }

    // -----------------------------------------------------------------------
    // The coach's own account
    // -----------------------------------------------------------------------

    override val coachProfile: Flow<CoachProfile> =
        combine(authRepository.currentUser, storeData) { user, preferences ->
            val scope = UserScope.of(user?.uid) ?: return@combine CoachProfile.Empty
            preferences[scope.string(KEY_PROFILE)]
                .decodeOne<CoachProfileRecord>()
                ?.toDomain()
                ?: CoachProfile.Empty
        }

    override suspend fun saveCoachProfile(profile: CoachProfile) {
        val uid = authRepository.currentUid ?: return
        val scope = UserScope.of(uid) ?: return
        context.coachStore.edit { preferences ->
            preferences[scope.string(KEY_PROFILE)] = json.encodeToString(profile.toRecord())
        }
    }

    override suspend fun publishListing() {
        val uid = authRepository.currentUid ?: return
        val scope = UserScope.of(uid) ?: return
        context.coachStore.edit { preferences ->
            // Only when absent. The profile row *is* the directory entry, so writing
            // an empty one over a filled-in profile would silently blank a coach out
            // of the listing every time they opened their own dashboard.
            val key = scope.string(KEY_PROFILE)
            if (preferences[key] == null) {
                preferences[key] = json.encodeToString(CoachProfile.Empty.toRecord())
            }
        }
    }

    // -----------------------------------------------------------------------
    // Roster
    // -----------------------------------------------------------------------

    override val roster: Flow<List<RosterMember>> = scopedList { preferences, scope ->
        preferences.rosterOf(scope).sortedByDescending { it.requestedAtEpochMs }
    }

    override suspend fun approve(playerUid: String) =
        editRoster(playerUid) { it.copy(status = MembershipStatus.Active, decidedAtEpochMs = now()) }

    override suspend fun decline(playerUid: String) =
        editRoster(playerUid) { it.copy(status = MembershipStatus.Ended, decidedAtEpochMs = now()) }

    override suspend fun removeFromRoster(playerUid: String) =
        editRoster(playerUid) { it.copy(status = MembershipStatus.Ended, decidedAtEpochMs = now()) }

    /**
     * Rewrites one member of the signed-in coach's roster.
     *
     * The row is edited rather than deleted. A removed player stays as
     * [MembershipStatus.Ended], which is what keeps "this person was on my team and
     * left" from looking identical to "this person was never here", and gives the
     * Past players list something to show.
     */
    private suspend fun editRoster(playerUid: String, transform: (RosterMember) -> RosterMember) {
        val scope = UserScope.of(authRepository.currentUid) ?: return
        context.coachStore.edit { preferences ->
            val updated = preferences.rosterOf(scope).map { member ->
                if (member.playerUid == playerUid) transform(member) else member
            }
            preferences.putRoster(scope, updated)
        }
    }

    // -----------------------------------------------------------------------
    // Assignments
    // -----------------------------------------------------------------------

    override val assignments: Flow<List<CoachAssignment>> = scopedList { preferences, scope ->
        preferences.assignmentsOf(scope).sortedByDescending { it.createdAtEpochMs }
    }

    override suspend fun saveAssignment(assignment: CoachAssignment) {
        val scope = UserScope.of(authRepository.currentUid) ?: return
        context.coachStore.edit { preferences ->
            // Replace by id rather than append, so editing an assignment updates it
            // instead of leaving the player looking at two versions of one drill.
            val updated = preferences.assignmentsOf(scope)
                .filterNot { it.id == assignment.id } + assignment
            preferences.putAssignments(scope, updated)
        }
    }

    override suspend fun deleteAssignment(id: String) {
        val scope = UserScope.of(authRepository.currentUid) ?: return
        context.coachStore.edit { preferences ->
            val remaining = preferences.assignmentsOf(scope).filterNot { it.id == id }
            preferences.putAssignments(scope, remaining)
        }
    }

    // -----------------------------------------------------------------------
    // Submissions and feedback
    // -----------------------------------------------------------------------

    override val submissions: Flow<List<SessionSubmission>> = scopedList { preferences, scope ->
        preferences.submissionsOf(scope).sortedByDescending { it.submittedAtEpochMs }
    }

    override val notes: Flow<List<CoachNote>> = scopedList { preferences, scope ->
        preferences.notesOf(scope).sortedByDescending { it.createdAtEpochMs }
    }

    override suspend fun postNote(note: CoachNote) {
        val scope = UserScope.of(note.coachUid) ?: return
        context.coachStore.edit { preferences ->
            preferences.putNotes(scope, preferences.notesOf(scope) + note)

            // Marking the submission reviewed happens in the same edit as the note,
            // so the queue and the feedback can never disagree about whether this
            // session has been answered.
            val sessionId = note.sessionId ?: return@edit
            val updated = preferences.submissionsOf(scope).map { submission ->
                val matches = submission.sessionId == sessionId &&
                    submission.playerUid == note.playerUid
                if (matches && submission.isPending) {
                    submission.copy(reviewedAtEpochMs = now())
                } else {
                    submission
                }
            }
            preferences.putSubmissions(scope, updated)
        }
    }

    // -----------------------------------------------------------------------
    // The player's side of the same link
    // -----------------------------------------------------------------------

    override val myCoach: Flow<CoachLink?> =
        combine(authRepository.currentUser, storeData) { user, preferences ->
            val playerUid = user?.uid?.takeIf { it.isNotBlank() } ?: return@combine null
            preferences.linksFor(playerUid)
                // Ended links are excluded, which is what returns a declined player
                // to the code field so they can try another. The cost is that being
                // turned down is silent - the card simply goes - and a proper answer
                // needs the Notification entity in Table 18, which nothing builds yet.
                .filter { it.status != MembershipStatus.Ended }
                .maxByOrNull { it.requestedAtEpochMs }
        }

    override val notesForMe: Flow<List<CoachNote>> = playerList { preferences, playerUid ->
        preferences
            .allOf<NoteRecord, CoachNote>(KEY_NOTES) { it.toDomain() }
            .filter { it.playerUid == playerUid }
            .sortedByDescending { it.createdAtEpochMs }
    }

    override val assignmentsForMe: Flow<List<CoachAssignment>> = playerList { prefs, playerUid ->
        prefs
            .allOf<AssignmentRecord, CoachAssignment>(KEY_ASSIGNMENTS) { it.toDomain() }
            .filter { it.playerUid == playerUid }
            .sortedByDescending { it.createdAtEpochMs }
    }

    override val mySubmissions: Flow<List<SessionSubmission>> = playerList { prefs, playerUid ->
        prefs
            .allOf<SubmissionRecord, SessionSubmission>(KEY_SUBMISSIONS) { it.toDomain() }
            .filter { it.playerUid == playerUid }
            .sortedByDescending { it.submittedAtEpochMs }
    }

    /**
     * The coach list, built by walking every `coach_profile:*` key.
     *
     * The profile row *is* the listing - there is no separate directory index to
     * fall out of step with it, which is the same argument the player-side scans
     * make above. The signed-in account is filtered out so a coach browsing from
     * their own player side cannot ask themselves.
     *
     * Sorted by name, with the unnamed sunk to the bottom: a coach who has told
     * nobody who they are is almost never the one being looked for, and sorting
     * them last costs nothing.
     */
    override val coachDirectory: Flow<List<CoachPreview>> =
        combine(authRepository.currentUser, storeData) { user, preferences ->
            val selfUid = user?.uid

            preferences.asMap()
                .filterKeys { it.name.startsWith(KEY_PROFILE + UserScope.SEPARATOR) }
                .mapNotNull { (key, value) ->
                    val coachUid = key.name
                        .substringAfter(UserScope.SEPARATOR)
                        .takeIf { it.isNotBlank() && it != selfUid }
                        ?: return@mapNotNull null
                    val scope = UserScope.of(coachUid) ?: return@mapNotNull null

                    CoachPreview(
                        coachUid = coachUid,
                        profile = (value as? String)
                            .decodeOne<CoachProfileRecord>()
                            ?.toDomain()
                            ?: CoachProfile.Empty,
                        rosterSize = preferences.rosterOf(scope).count { it.isActive },
                    )
                }
                .sortedWith(
                    compareBy(
                        { it.profile.name.isBlank() },
                        { it.profile.name.lowercase() },
                    ),
                )
        }

    override suspend fun requestJoin(coachUid: String): JoinOutcome {
        val playerUid = authRepository.currentUid
            ?: return JoinOutcome.Failure(JoinFailure.NoSession)
        if (coachUid == playerUid) return JoinOutcome.Failure(JoinFailure.OwnCode)

        val coachScope = UserScope.of(coachUid)
            ?: return JoinOutcome.Failure(JoinFailure.UnknownCoach)

        val snapshot = storeData.first()
        // Re-checked rather than trusted from the preview: the coach could have
        // approved someone, or this player could have joined from another screen,
        // in the seconds between reading the profile and pressing send.
        val existing = snapshot.rosterOf(coachScope).firstOrNull { it.playerUid == playerUid }
        if (existing != null && existing.status != MembershipStatus.Ended) {
            return JoinOutcome.Failure(JoinFailure.AlreadyLinked)
        }

        // The name and position are copied from the player's own profile at the
        // moment they ask, which is the narrowest thing that makes the coach's
        // roster readable. See RosterMember for why it is a copy and not a lookup.
        val profile = profileRepository.profile.first()
        val member = RosterMember(
            playerUid = playerUid,
            playerName = profile.name,
            position = profile.position,
            status = MembershipStatus.Pending,
            requestedAtEpochMs = now(),
        )

        context.coachStore.edit { preferences ->
            val updated = preferences.rosterOf(coachScope)
                .filterNot { it.playerUid == playerUid } + member
            preferences.putRoster(coachScope, updated)
        }

        val coachProfile = snapshot[coachScope.string(KEY_PROFILE)]
            .decodeOne<CoachProfileRecord>()
            ?.toDomain()
            ?: CoachProfile.Empty

        return JoinOutcome.Success(
            CoachLink(
                coachUid = coachUid,
                coachName = coachProfile.name,
                teamName = coachProfile.teamName,
                status = MembershipStatus.Pending,
                requestedAtEpochMs = member.requestedAtEpochMs,
            ),
        )
    }

    override suspend fun leaveCoach() {
        val playerUid = authRepository.currentUid ?: return
        val link = myCoach.first() ?: return
        val scope = UserScope.of(link.coachUid) ?: return
        context.coachStore.edit { preferences ->
            val updated = preferences.rosterOf(scope).map { member ->
                if (member.playerUid == playerUid) {
                    member.copy(status = MembershipStatus.Ended, decidedAtEpochMs = now())
                } else {
                    member
                }
            }
            preferences.putRoster(scope, updated)
        }
    }

    override suspend fun submitSession(sessionId: String) {
        val playerUid = authRepository.currentUid ?: return
        // Only an approved player can submit. A pending request is a request, not a
        // membership, and letting it push training at a coach who has not yet said
        // yes would make approval decorative.
        val link = myCoach.first()?.takeIf { it.isActive } ?: return
        val scope = UserScope.of(link.coachUid) ?: return

        context.coachStore.edit { preferences ->
            val current = preferences.submissionsOf(scope)
            // Re-submitting an already-queued session is a no-op rather than a
            // duplicate row, so a double tap cannot put the same set in the queue
            // twice. Re-submitting a *reviewed* one does re-queue it, which is how
            // an athlete asks for a second look after redoing the drill.
            val alreadyQueued = current.any {
                it.sessionId == sessionId && it.playerUid == playerUid && it.isPending
            }
            if (alreadyQueued) return@edit

            val updated = current.filterNot {
                it.sessionId == sessionId && it.playerUid == playerUid
            } + SessionSubmission(
                sessionId = sessionId,
                coachUid = link.coachUid,
                playerUid = playerUid,
                submittedAtEpochMs = now(),
            )
            preferences.putSubmissions(scope, updated)
        }
    }

    override suspend fun submitAssignmentSession(assignmentId: String, session: com.volleyballlab.player.domain.model.SessionResult) {
        val playerUid = checkNotNull(authRepository.currentUid)
        val link = checkNotNull(myCoach.first()?.takeIf { it.isActive })
        val scope = checkNotNull(UserScope.of(link.coachUid))
        context.coachStore.edit { preferences ->
            check(authRepository.currentUid == playerUid)
            val assignment = preferences.assignmentsOf(scope).firstOrNull { it.id == assignmentId }
                ?: error("This assignment is no longer available.")
            require(assignment.playerUid == playerUid && session.assignmentId == assignmentId && session.skill == assignment.skill)
            require(session.repCount > 0)
            val current = preferences.submissionsOf(scope)
            if (current.none { it.sessionId == session.id && it.playerUid == playerUid }) {
                preferences.putSubmissions(scope, current + SessionSubmission(
                    session.id, link.coachUid, playerUid, now(),
                ))
            }
            preferences.putAssignments(scope, preferences.assignmentsOf(scope).map {
                if (it.id == assignmentId) it.copy(status = AssignmentStatus.Completed) else it
            })
        }
    }

    override suspend fun updateAssignmentStatus(id: String, status: AssignmentStatus) {
        val playerUid = authRepository.currentUid ?: return
        val assignment = assignmentsForMe.first().firstOrNull { it.id == id } ?: return
        val scope = UserScope.of(assignment.coachUid) ?: return
        context.coachStore.edit { preferences ->
            val updated = preferences.assignmentsOf(scope).map { candidate ->
                if (candidate.id == id && candidate.playerUid == playerUid) {
                    candidate.copy(status = status)
                } else {
                    candidate
                }
            }
            preferences.putAssignments(scope, updated)
        }
    }

    // -----------------------------------------------------------------------
    // Plumbing
    // -----------------------------------------------------------------------

    /**
     * A list flow over the store, scoped to the signed-in account.
     *
     * Re-reads on every sign-in and sign-out, so switching accounts switches the
     * whole coach view rather than leaving the previous coach's roster on screen.
     * With no session the list is empty - there is nobody for the data to belong to.
     */
    private fun <T> scopedList(read: (Preferences, UserScope) -> List<T>): Flow<List<T>> =
        combine(authRepository.currentUser, storeData) { user, preferences ->
            val scope = UserScope.of(user?.uid) ?: return@combine emptyList()
            read(preferences, scope)
        }

    /** As [scopedList], but the uid is used to search *other* coaches' records. */
    private fun <T> playerList(read: (Preferences, String) -> List<T>): Flow<List<T>> =
        combine(authRepository.currentUser, storeData) { user, preferences ->
            val uid = user?.uid?.takeIf { it.isNotBlank() } ?: return@combine emptyList()
            read(preferences, uid)
        }

    private fun Preferences.rosterOf(scope: UserScope): List<RosterMember> =
        this[scope.string(KEY_ROSTER)].decodeList<RosterMemberRecord>().map { it.toDomain() }

    private fun MutablePreferences.putRoster(scope: UserScope, members: List<RosterMember>) {
        this[scope.string(KEY_ROSTER)] = json.encodeToString(members.map { it.toRecord() })
    }

    private fun Preferences.assignmentsOf(scope: UserScope): List<CoachAssignment> =
        this[scope.string(KEY_ASSIGNMENTS)]
            .decodeList<AssignmentRecord>()
            .mapNotNull { it.toDomain() }

    private fun MutablePreferences.putAssignments(scope: UserScope, items: List<CoachAssignment>) {
        this[scope.string(KEY_ASSIGNMENTS)] = json.encodeToString(
            items.sortedByDescending { it.createdAtEpochMs }.take(MAX_ROWS).map { it.toRecord() },
        )
    }

    private fun Preferences.submissionsOf(scope: UserScope): List<SessionSubmission> =
        this[scope.string(KEY_SUBMISSIONS)].decodeList<SubmissionRecord>().map { it.toDomain() }

    private fun MutablePreferences.putSubmissions(
        scope: UserScope,
        items: List<SessionSubmission>,
    ) {
        this[scope.string(KEY_SUBMISSIONS)] = json.encodeToString(
            items.sortedByDescending { it.submittedAtEpochMs }.take(MAX_ROWS).map { it.toRecord() },
        )
    }

    private fun Preferences.notesOf(scope: UserScope): List<CoachNote> =
        this[scope.string(KEY_NOTES)].decodeList<NoteRecord>().map { it.toDomain() }

    private fun MutablePreferences.putNotes(scope: UserScope, items: List<CoachNote>) {
        this[scope.string(KEY_NOTES)] = json.encodeToString(
            items.sortedByDescending { it.createdAtEpochMs }.take(MAX_ROWS).map { it.toRecord() },
        )
    }

    /**
     * Every record of one kind on the device, across all coaches.
     *
     * Walks the preference map by key prefix rather than by a stored index. See the
     * class KDoc for why an index is the worse option here.
     */
    private inline fun <reified R, T : Any> Preferences.allOf(
        prefix: String,
        toDomain: (R) -> T?,
    ): List<T> = asMap()
        .filterKeys { it.name.startsWith(prefix + UserScope.SEPARATOR) }
        .values
        .filterIsInstance<String>()
        .flatMap { blob -> blob.decodeList<R>() }
        .mapNotNull(toDomain)

    /**
     * Every link involving this player, expressed from the player's point of view.
     *
     * Needs both the roster row - which carries the status - and the coach's own
     * profile, for the team name to put on the card, so it cannot go through
     * [allOf].
     */
    private fun Preferences.linksFor(playerUid: String): List<CoachLink> = asMap()
        .filterKeys { it.name.startsWith(KEY_ROSTER + UserScope.SEPARATOR) }
        .mapNotNull { (key, value) ->
            val coachUid = key.name
                .substringAfter(UserScope.SEPARATOR)
                .takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val blob = value as? String ?: return@mapNotNull null
            val member = blob.decodeList<RosterMemberRecord>()
                .map { it.toDomain() }
                .firstOrNull { it.playerUid == playerUid }
                ?: return@mapNotNull null
            val scope = UserScope.of(coachUid) ?: return@mapNotNull null
            val profile = this[scope.string(KEY_PROFILE)]
                .decodeOne<CoachProfileRecord>()
                ?.toDomain()
                ?: CoachProfile.Empty

            CoachLink(
                coachUid = coachUid,
                coachName = profile.name,
                teamName = profile.teamName,
                status = member.status,
                requestedAtEpochMs = member.requestedAtEpochMs,
            )
        }

    /**
     * Decodes a stored list, treating malformed content as no content.
     *
     * The same bargain the session store makes: a file written by a newer build, or
     * truncated by a device dying mid-write, costs the coach a roster they could not
     * have read anyway but must not cost them a working app. Logged rather than
     * silent, so it shows up in a bug report.
     */
    private inline fun <reified R> String?.decodeList(): List<R> {
        if (isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<R>>(this) }
            .onFailure { Log.w(TAG, "Discarding unreadable coach record list", it) }
            .getOrDefault(emptyList())
    }

    private inline fun <reified R> String?.decodeOne(): R? {
        if (isNullOrBlank()) return null
        return runCatching { json.decodeFromString<R>(this) }
            .onFailure { Log.w(TAG, "Discarding unreadable coach record", it) }
            .getOrNull()
    }


    companion object {
        /** New ids are generated here, not by callers, so uniqueness has one owner. */
        fun newId(): String = UUID.randomUUID().toString()

        const val KEY_PROFILE = "coach_profile"
        const val KEY_ROSTER = "roster"
        const val KEY_ASSIGNMENTS = "assignments"
        const val KEY_SUBMISSIONS = "submissions"
        const val KEY_NOTES = "notes"

        /**
         * Per-coach cap on assignments, submissions and notes.
         *
         * Preferences DataStore rewrites the whole file on every edit, so the cost
         * of a write grows with the list. A squad of twenty submitting a few
         * sessions a week stays far under this; the cap is what stops an unbounded
         * list from making every write slower forever.
         */
        const val MAX_ROWS = 400

        const val TAG = "CoachStore"

        val json = Json { ignoreUnknownKeys = true }
    }
}

internal suspend fun clearCoachAccount(context: Context, uid: String) =
    com.volleyballlab.player.data.account.clearAccountPreferences(context.coachStore, uid)
