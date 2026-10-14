package com.volleyballlab.player.data.coach

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import com.volleyballlab.player.data.cloud.CloudSchema
import com.volleyballlab.player.data.cloud.awaitCloud
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
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.CoachRepository
import com.volleyballlab.player.domain.repository.JoinFailure
import com.volleyballlab.player.domain.repository.JoinOutcome
import com.volleyballlab.player.domain.repository.ProfileRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * [CoachRepository] on Cloud Firestore — the shared backend from
 * `docs/coach-cloud-contract.md`, also served by the coach web portal.
 *
 * Same interface, same semantics as [DataStoreCoachRepository]: roster rows are
 * edited rather than deleted, re-submitting a queued session is a no-op,
 * re-submitting a reviewed one re-queues it, and the profile row feeds the
 * directory projection. The differences are all where distribution forces them:
 *
 * - **Reads are live queries, sorted client-side.** Every list filters on one
 *   field and sorts in memory, so no composite index is needed and the web
 *   portal can run the same queries unindexed.
 * - **Failures are visible through syncError.** Rejected listeners emit a
 *   fallback and retry, so the UI can offer recovery instead of loading forever.
 * - **Note + reviewed-stamp is two writes, not one edit.** They go out in
 *   order (note first), so the worst case is a answered queue row rather than
 *   a lost note.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreCoachRepository(
    private val db: FirebaseFirestore,
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    /** Injected so tests can pin it; production passes the system clock. */
    private val now: () -> Long = System::currentTimeMillis,
    private val prepareRecording: suspend (String, String) -> Unit = { _, _ -> },
) : CoachRepository {

    private val issues = MutableStateFlow<Map<String, String>>(emptyMap())
    private val refresh = MutableStateFlow(0)

    override val syncError: Flow<String?> = combine(authRepository.currentUser, issues) { user, failures ->
        failures.entries.firstOrNull { it.key.startsWith("${user?.uid}|") }?.value
    }

    override fun retrySync() {
        issues.value = emptyMap()
        refresh.update { it + 1 }
    }

    private fun issueKey(label: String, uid: String? = authRepository.currentUid) = "$uid|$label"

    private fun reportFailure(key: String, error: Throwable, saving: Boolean = false) {
        if (error is CancellationException) throw error
        val reason = when ((error as? FirebaseFirestoreException)?.code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED -> "Coach access could not be verified. Sign in again or contact the app owner."
            FirebaseFirestoreException.Code.FAILED_PRECONDITION -> "The coach service needs a database update. Contact the app owner."
            else -> "Coach data could not sync. Check your connection and retry."
        }
        issues.update { it + (key to if (saving) "Your change was not confirmed. $reason" else reason) }
        Log.w(TAG, "Coach sync failed: $key", error)
    }

    private fun clearFailure(key: String) { issues.update { it - key } }

    private fun Query.coachDocuments(key: String): Flow<List<DocumentSnapshot>> =
        refresh.flatMapLatest {
            callbackFlow<List<DocumentSnapshot>> {
                val registration = addSnapshotListener { snapshot, failure ->
                    if (failure != null) close(failure)
                    else if (snapshot != null) trySend(snapshot.documents)
                }
                awaitClose { registration.remove() }
            }.recoverCoachFlow(emptyList(), { reportFailure(key, it) }, { clearFailure(key) })
        }

    private fun DocumentReference.coachData(key: String): Flow<Map<String, Any>?> =
        refresh.flatMapLatest {
            callbackFlow<Map<String, Any>?> {
                val registration = addSnapshotListener { snapshot, failure ->
                    if (failure != null) close(failure)
                    else trySend(snapshot?.data)
                }
                awaitClose { registration.remove() }
            }.recoverCoachFlow(null, { reportFailure(key, it) }, { clearFailure(key) })
        }

    // ------------------------------------------------------------------
    // The coach's own account
    // ------------------------------------------------------------------

    override val coachProfile: Flow<CoachProfile> =
        combine(authRepository.currentUser, uidDocFlow(CloudSchema.COACHES)) { _, data ->
            data?.let(CloudSchema::profileOf) ?: CoachProfile.Empty
        }

    override suspend fun saveCoachProfile(profile: CoachProfile) {
        val uid = authRepository.currentUid ?: return
        runCatching {
            db.collection(CloudSchema.COACHES).document(uid)
                .set(CloudSchema.coachDoc(profile, now()), SetOptions.merge()).awaitCloud()
            writeDirectory(uid, profile)
        }.onSuccess { clearFailure(issueKey("save profile", uid)) }
            .onFailure { reportFailure(issueKey("save profile", uid), it, saving = true) }
    }

    override suspend fun publishListing() {
        val uid = authRepository.currentUid ?: return
        runCatching {
            val coaches = db.collection(CloudSchema.COACHES)
            val existing = coaches.document(uid).get().awaitCloud()
            val savedProfile = existing.data?.let(CloudSchema::profileOf) ?: CoachProfile.Empty
            val user = authRepository.currentUser.first()
            if (user?.uid != uid) return@runCatching
            val profile = if (savedProfile.name.isBlank()) savedProfile.copy(
                name = user.displayName.orEmpty().take(CoachProfile.MAX_NAME_LENGTH),
            ) else savedProfile
            if (!existing.exists() || profile != savedProfile) {
                coaches.document(uid).set(CloudSchema.coachDoc(profile, now()), SetOptions.merge()).awaitCloud()
            }
            if (authRepository.currentUid != uid) return@runCatching
            writeDirectory(uid, profile)
        }.onSuccess { clearFailure(issueKey("publish coach", uid)) }
            .onFailure { reportFailure(issueKey("publish coach", uid), it, saving = true) }
    }

    // ------------------------------------------------------------------
    // Roster
    // ------------------------------------------------------------------

    override val roster: Flow<List<RosterMember>> =
        uidCollectionDocs(CloudSchema.ROSTER, CloudSchema.MEMBERS).map { docs ->
            docs.mapNotNull { it.data?.let(CloudSchema::memberOf) }
                .sortedByDescending { it.requestedAtEpochMs }
        }

    override suspend fun approve(playerUid: String) =
        editRoster(playerUid, MembershipStatus.Active)

    override suspend fun decline(playerUid: String) =
        editRoster(playerUid, MembershipStatus.Ended)

    override suspend fun removeFromRoster(playerUid: String) =
        editRoster(playerUid, MembershipStatus.Ended)

    private suspend fun editRoster(playerUid: String, status: MembershipStatus) {
        val coachUid = authRepository.currentUid ?: return
        runCatching {
            db.collection(CloudSchema.ROSTER).document(coachUid)
                .collection(CloudSchema.MEMBERS).document(playerUid)
                .update(mapOf("status" to status.name, "decidedAt" to now())).awaitCloud()
            refreshDirectoryRosterSize(coachUid)
        }.onSuccess { clearFailure(issueKey("roster update", coachUid)) }
            .onFailure { reportFailure(issueKey("roster update", coachUid), it, saving = true) }
    }

    // ------------------------------------------------------------------
    // Assignments
    // ------------------------------------------------------------------

    override val assignments: Flow<List<CoachAssignment>> =
        fieldDocsFlow(CloudSchema.ASSIGNMENTS, "coachUid").map { docs ->
            docs.mapNotNull { it.data?.let(CloudSchema::assignmentOf) }
                .sortedByDescending { it.createdAtEpochMs }
        }

    override suspend fun saveAssignment(assignment: CoachAssignment) {
        val uid = authRepository.currentUid ?: return
        runCatching {
            db.collection(CloudSchema.ASSIGNMENTS).document(assignment.id)
                .set(CloudSchema.assignmentDoc(assignment)).awaitCloud()
        }.onSuccess { clearFailure(issueKey("save assignment", uid)) }
            .onFailure { reportFailure(issueKey("save assignment", uid), it, saving = true) }
    }

    override suspend fun deleteAssignment(id: String) {
        val uid = authRepository.currentUid ?: return
        runCatching {
            db.collection(CloudSchema.ASSIGNMENTS).document(id).delete().awaitCloud()
        }.onSuccess { clearFailure(issueKey("delete assignment", uid)) }
            .onFailure { reportFailure(issueKey("delete assignment", uid), it, saving = true) }
    }

    // ------------------------------------------------------------------
    // Submissions and feedback
    // ------------------------------------------------------------------

    override val submissions: Flow<List<SessionSubmission>> =
        fieldDocsFlow(CloudSchema.SUBMISSIONS, "coachUid").map { docs ->
            docs.map { CloudSchema.submissionOf(it.data ?: emptyMap()) }
                .sortedByDescending { it.submittedAtEpochMs }
        }

    override val notes: Flow<List<CoachNote>> =
        fieldDocsFlow(CloudSchema.NOTES, "coachUid").map { docs ->
            docs.map { CloudSchema.noteOf(it.data ?: emptyMap()) }
                .sortedByDescending { it.createdAtEpochMs }
        }

    override suspend fun postNote(note: CoachNote) {
        val uid = authRepository.currentUid ?: return
        runCatching {
            db.collection(CloudSchema.NOTES).document(note.id)
                .set(CloudSchema.noteDoc(note)).awaitCloud()
            // Stamp the queue row reviewed, same pairing the local store keeps in
            // one edit. Only pending rows move: re-answering an already-reviewed
            // row must not rewrite its history.
            val sessionId = note.sessionId ?: return@runCatching
            val submission = db.collection(CloudSchema.SUBMISSIONS)
                .document(CloudSchema.submissionId(note.coachUid, sessionId))
                .get().awaitCloud()
            val pending = submission.data?.let { CloudSchema.submissionOf(it).isPending } ?: false
            if (pending && authRepository.currentUid == uid) {
                submission.reference.update("reviewedAt", now()).awaitCloud()
            }
        }.onSuccess { clearFailure(issueKey("coach feedback", uid)) }
            .onFailure { reportFailure(issueKey("coach feedback", uid), it, saving = true) }
    }

    // ------------------------------------------------------------------
    // The player's side of the same link
    // ------------------------------------------------------------------

    override val myCoach: Flow<CoachLink?> =
        authRepository.currentUser.flatMapLatest { user ->
            val playerUid = user?.uid?.takeIf { it.isNotBlank() } ?: return@flatMapLatest flowOf(null)
            db.collectionGroup(CloudSchema.MEMBERS)
                .whereEqualTo("playerUid", playerUid)
                .coachDocuments(issueKey("student membership", playerUid))
                .flatMapLatest { docs ->
                    val latest = docs
                        .mapNotNull { doc ->
                            val coachUid = doc.reference.parent.parent?.id ?: return@mapNotNull null
                            coachUid to CloudSchema.memberOf(doc.data ?: emptyMap())
                        }
                        // Ended links are excluded, returning a declined player to
                        // the directory so they can try another coach.
                        .filter { it.second.status != MembershipStatus.Ended }
                        .maxByOrNull { it.second.requestedAtEpochMs }
                        ?: return@flatMapLatest flowOf(null)
                    db.collection(CloudSchema.DIRECTORY).document(latest.first).coachData(issueKey("linked coach", playerUid))
                        .map { coachData ->
                            val profile = coachData?.let(CloudSchema::profileOf)
                                ?: CoachProfile.Empty
                            CoachLink(
                                coachUid = latest.first,
                                coachName = profile.name,
                                teamName = profile.teamName,
                                status = latest.second.status,
                                requestedAtEpochMs = latest.second.requestedAtEpochMs,
                            )
                        }
                }
        }

    override val notesForMe: Flow<List<CoachNote>> =
        playerQueryFlow(CloudSchema.NOTES) { docs ->
            docs.map { CloudSchema.noteOf(it.data ?: emptyMap()) }
                .sortedByDescending { it.createdAtEpochMs }
        }

    override val assignmentsForMe: Flow<List<CoachAssignment>> =
        playerQueryFlow(CloudSchema.ASSIGNMENTS) { docs ->
            docs.mapNotNull { it.data?.let(CloudSchema::assignmentOf) }
                .sortedByDescending { it.createdAtEpochMs }
        }

    override val mySubmissions: Flow<List<SessionSubmission>> =
        playerQueryFlow(CloudSchema.SUBMISSIONS) { docs ->
            docs.map { CloudSchema.submissionOf(it.data ?: emptyMap()) }
                .sortedByDescending { it.submittedAtEpochMs }
        }

    override val coachDirectory: Flow<List<CoachPreview>> =
        authRepository.currentUser.flatMapLatest { user ->
            val uid = user?.uid ?: return@flatMapLatest flowOf(emptyList())
            db.collection(CloudSchema.DIRECTORY).coachDocuments(issueKey("coach directory", uid)).map { docs -> docs
                .filter { it.id != uid }
                .map { CloudSchema.previewOf(it.id, it.data ?: emptyMap()) }
                .sortedWith(compareBy({ it.profile.name.isBlank() }, { it.profile.name.lowercase() }))
            }
        }

    override suspend fun requestJoin(coachUid: String): JoinOutcome {
        val playerUid = authRepository.currentUid
            ?: return JoinOutcome.Failure(JoinFailure.NoSession)
        if (coachUid == playerUid) return JoinOutcome.Failure(JoinFailure.OwnCode)
        return runCatching {
            val memberRef = db.collection(CloudSchema.ROSTER).document(coachUid)
                .collection(CloudSchema.MEMBERS).document(playerUid)
            val existing = memberRef.get().awaitCloud().data?.let(CloudSchema::memberOf)
            if (existing != null && existing.status != MembershipStatus.Ended) {
                return JoinOutcome.Failure(JoinFailure.AlreadyLinked)
            }
            val coachDoc = db.collection(CloudSchema.DIRECTORY).document(coachUid).get().awaitCloud()
            if (!coachDoc.exists()) return JoinOutcome.Failure(JoinFailure.UnknownCoach)
            val coachProfile = coachDoc.data?.let(CloudSchema::profileOf) ?: CoachProfile.Empty

            val profile = profileRepository.profile.first()
            if (authRepository.currentUid != playerUid) return JoinOutcome.Failure(JoinFailure.NoSession)
            val requestedAt = now()
            memberRef.set(
                CloudSchema.memberDoc(
                    RosterMember(
                        playerUid = playerUid,
                        playerName = profile.name,
                        position = profile.position,
                        status = MembershipStatus.Pending,
                        requestedAtEpochMs = requestedAt,
                    ),
                ),
            ).awaitCloud()

            JoinOutcome.Success(
                CoachLink(
                    coachUid = coachUid,
                    coachName = coachProfile.name,
                    teamName = coachProfile.teamName,
                    status = MembershipStatus.Pending,
                    requestedAtEpochMs = requestedAt,
                ),
            )
        }.onSuccess { clearFailure(issueKey("join request", playerUid)) }
            .onFailure { reportFailure(issueKey("join request", playerUid), it, saving = true) }
            .getOrDefault(JoinOutcome.Failure(JoinFailure.Unavailable))
    }

    override suspend fun leaveCoach() {
        val playerUid = authRepository.currentUid ?: return
        val link = myCoach.first() ?: return
        if (authRepository.currentUid != playerUid) return
        runCatching {
            db.collection(CloudSchema.ROSTER).document(link.coachUid)
                .collection(CloudSchema.MEMBERS).document(playerUid)
                .update(mapOf("status" to MembershipStatus.Ended.name, "decidedAt" to now()))
                .awaitCloud()
        }.onSuccess { clearFailure(issueKey("leave coach", playerUid)) }
            .onFailure { reportFailure(issueKey("leave coach", playerUid), it, saving = true) }
    }

    override suspend fun submitSession(sessionId: String) {
        val playerUid = authRepository.currentUid ?: return
        val link = myCoach.first()?.takeIf { it.isActive } ?: return
        if (authRepository.currentUid != playerUid) return
        runCatching {
            prepareRecording(sessionId, playerUid)
            val ref = db.collection(CloudSchema.SUBMISSIONS)
                .document(CloudSchema.submissionId(link.coachUid, sessionId))
            val existing = ref.get().awaitCloud().data?.let(CloudSchema::submissionOf)
            // A queued submission is a no-op; a reviewed one re-queues, asking
            // for a second look after redoing the drill.
            if (existing != null && existing.playerUid == playerUid && existing.isPending) return@runCatching
            if (authRepository.currentUid != playerUid) return@runCatching
            ref.set(
                CloudSchema.submissionDoc(
                    SessionSubmission(
                        sessionId = sessionId,
                        coachUid = link.coachUid,
                        playerUid = playerUid,
                        submittedAtEpochMs = now(),
                    ),
                ),
            ).awaitCloud()
            // The receipt: the coach can now read this one session, and only it.
            db.collection(CloudSchema.SESSIONS).document(sessionId)
                .update("sharedWith", FieldValue.arrayUnion(link.coachUid)).awaitCloud()
        }.onSuccess { clearFailure(issueKey("submit session", playerUid)) }
            .onFailure { reportFailure(issueKey("submit session", playerUid), it, saving = true) }
    }

    override suspend fun submitAssignmentSession(assignmentId: String, session: SessionResult) {
        val playerUid = checkNotNull(authRepository.currentUid) { "Sign in to send your result." }
        val assignmentRef = db.collection(CloudSchema.ASSIGNMENTS).document(assignmentId)
        val assignment = assignmentRef.get(Source.SERVER).awaitCloud().data?.let(CloudSchema::assignmentOf)
            ?: error("This assignment is no longer available.")
        require(assignment.playerUid == playerUid && session.assignmentId == assignmentId && session.skill == assignment.skill) {
            "This recording does not belong to the assignment."
        }
        require(session.repCount > 0) { "Record at least one repetition before submitting." }
        val memberRef = db.collection(CloudSchema.ROSTER).document(assignment.coachUid)
            .collection(CloudSchema.MEMBERS).document(playerUid)
        check(memberRef.get(Source.SERVER).awaitCloud().data?.let(CloudSchema::memberOf)?.isActive == true) {
            "You are no longer connected to this coach."
        }
        check(authRepository.currentUid == playerUid) { "Your account changed. Sign in again." }
        prepareRecording(session.id, playerUid)
        val sessionRef = db.collection(CloudSchema.SESSIONS).document(session.id)
        // A locally saved result can outlive a failed best-effort cloud mirror.
        // Upload it before the transaction: submission rules read this document.
        if (!sessionRef.get(Source.SERVER).awaitCloud().exists()) {
            sessionRef.set(CloudSchema.sessionDoc(session, playerUid, emptyList(), now())).awaitCloud()
        }
        val submissionRef = db.collection(CloudSchema.SUBMISSIONS)
            .document(CloudSchema.submissionId(assignment.coachUid, session.id))
        db.runTransaction { tx ->
            val current = tx.get(assignmentRef).data?.let(CloudSchema::assignmentOf)
                ?: error("This assignment is no longer available.")
            val member = tx.get(memberRef).data?.let(CloudSchema::memberOf)
            val storedSession = tx.get(sessionRef)
            val receipt = tx.get(submissionRef)
            check(authRepository.currentUid == playerUid && current.playerUid == playerUid &&
                current.coachUid == assignment.coachUid && member?.isActive == true) {
                "Your coach connection changed. Return to the Coach tab."
            }
            check(storedSession.getString("ownerUid") == playerUid && storedSession.getString("skill") == current.skill.name) {
                "This recording does not match the assignment."
            }
            // Retry is idempotent, including after the coach already reviewed it.
            if (!receipt.exists()) {
                tx.set(submissionRef, CloudSchema.submissionDoc(SessionSubmission(
                    sessionId = session.id, coachUid = current.coachUid, playerUid = playerUid,
                    submittedAtEpochMs = now(),
                )))
            }
            tx.update(sessionRef, "sharedWith", FieldValue.arrayUnion(current.coachUid))
            tx.update(assignmentRef, "status", AssignmentStatus.Completed.name)
        }.awaitCloud()
    }

    override suspend fun updateAssignmentStatus(id: String, status: AssignmentStatus) {
        val playerUid = authRepository.currentUid ?: return
        runCatching {
            val ref = db.collection(CloudSchema.ASSIGNMENTS).document(id)
            val assignment = ref.get().awaitCloud().data?.let(CloudSchema::assignmentOf)
                ?: return@runCatching
            if (assignment.playerUid != playerUid) return@runCatching
            if (authRepository.currentUid != playerUid) return@runCatching
            ref.update("status", status.name).awaitCloud()
        }.onSuccess { clearFailure(issueKey("assignment status", playerUid)) }
            .onFailure { reportFailure(issueKey("assignment status", playerUid), it, saving = true) }
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    /** The signed-in account's document in a top-level collection. */
    private fun uidDocFlow(collection: String) =
        authRepository.currentUser.flatMapLatest { user ->
            val uid = user?.uid?.takeIf { it.isNotBlank() } ?: return@flatMapLatest flowOf(null)
            db.collection(collection).document(uid).coachData(issueKey(collection, uid))
        }

    /** The signed-in account's subcollection rows (roster members of one coach). */
    private fun uidCollectionDocs(collection: String, subcollection: String) =
        authRepository.currentUser.flatMapLatest { user ->
            val uid = user?.uid?.takeIf { it.isNotBlank() }
                ?: return@flatMapLatest flowOf(emptyList())
            db.collection(collection).document(uid).collection(subcollection)
                .coachDocuments(issueKey("$collection/$subcollection", uid))
        }

    /** One-field equality query scoped to the signed-in account. */
    private fun fieldDocsFlow(collection: String, field: String) =
        authRepository.currentUser.flatMapLatest { user ->
            val uid = user?.uid?.takeIf { it.isNotBlank() }
                ?: return@flatMapLatest flowOf(emptyList())
            db.collection(collection).whereEqualTo(field, uid)
                .coachDocuments(issueKey("$collection/$field", uid))
        }

    private fun <T> playerQueryFlow(
        collection: String,
        read: (List<com.google.firebase.firestore.DocumentSnapshot>) -> List<T>,
    ): Flow<List<T>> = fieldDocsFlow(collection, "playerUid").map(read)

    /** The directory projection, refreshed whenever the roster changes. */
    private suspend fun refreshDirectoryRosterSize(coachUid: String) {
        val members = db.collection(CloudSchema.ROSTER).document(coachUid)
            .collection(CloudSchema.MEMBERS).get().awaitCloud()
            .documents.mapNotNull { it.data?.let(CloudSchema::memberOf) }
        val profile = db.collection(CloudSchema.COACHES).document(coachUid).get().awaitCloud()
            .data?.let(CloudSchema::profileOf) ?: CoachProfile.Empty
        writeDirectory(coachUid, profile, members.count { it.isActive })
    }

    private suspend fun writeDirectory(coachUid: String, profile: CoachProfile, rosterSize: Int? = null) {
        val size = rosterSize ?: db.collection(CloudSchema.ROSTER).document(coachUid)
            .collection(CloudSchema.MEMBERS).get().awaitCloud()
            .documents.mapNotNull { it.data?.let(CloudSchema::memberOf) }
            .count { it.isActive }
        if (authRepository.currentUid != coachUid) return
        db.collection(CloudSchema.DIRECTORY).document(coachUid)
            .set(CloudSchema.directoryDoc(profile, size, now())).awaitCloud()
    }

    private companion object {
        const val TAG = "VbCoachCloud"
    }
}
