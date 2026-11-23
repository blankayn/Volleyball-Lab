package com.volleyballlab.player.data.coach

import com.google.firebase.firestore.*
import com.volleyballlab.player.data.cloud.awaitCloud
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.domain.repository.AuthRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay

/** Training questions and verified reviews shared by Android and the coach portal. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoachingHubRepository(private val db: FirebaseFirestore, private val auth: AuthRepository) {
    private val refresh = MutableStateFlow(0)
    private val failures = MutableStateFlow<Map<String, String>>(emptyMap())
    val error: Flow<String?> = combine(auth.currentUser, failures) { user, errors ->
        errors.entries.firstOrNull { it.key.startsWith("${user?.uid}|") }?.value
    }
    fun retry() { failures.value = emptyMap(); refresh.value++ }

    private fun Query.observe(): Flow<List<DocumentSnapshot>> {
        val key = "${auth.currentUid}|${hashCode()}"
        return refresh.flatMapLatest {
        callbackFlow {
            val registration = addSnapshotListener { snapshot, error ->
                if (error != null) close(error) else if (snapshot != null) trySend(snapshot.documents)
            }
            awaitClose { registration.remove() }
        }.recoverCoachFlow(emptyList(), {
            failures.update { it + (key to "Coaching information could not sync. Check your connection and retry.") }
        }, { failures.update { it - key } })
        }
    }

    private fun mine(collection: String, field: String) = auth.currentUser.flatMapLatest { user ->
        if (user == null) flowOf(emptyList()) else db.collection(collection).whereEqualTo(field, user.uid).observe()
    }
    val playerQuestions: Flow<List<CoachingQuestion>> = mine("coachingQuestions", "playerUid").map { it.mapNotNull(::question).sortedByDescending { it.createdAt } }
    val coachQuestions: Flow<List<CoachingQuestion>> = mine("coachingQuestions", "coachUid").map { it.mapNotNull(::question).sortedBy { it.createdAt } }
    val playerChat: Flow<List<CoachingChatMessage>> = mine("coachingMessages", "playerUid").map { it.mapNotNull(::chatMessage) }
    val coachChat: Flow<List<CoachingChatMessage>> = mine("coachingMessages", "coachUid").map { it.mapNotNull(::chatMessage) }
    val playerReads: Flow<List<CoachingRead>> = mine("coachingReads", "playerUid").map { it.mapNotNull(::read) }
    val coachReads: Flow<List<CoachingRead>> = mine("coachingReads", "coachUid").map { it.mapNotNull(::read) }
    val myReviews: Flow<List<CoachReview>> = mine("coachReviews", "playerUid").map { it.mapNotNull(::review) }

    fun summary(coachUid: String): Flow<CoachPublicSummary> = combine(
        presence(coachUid),
        db.collection("coachReviews").whereEqualTo("coachUid", coachUid).observe(),
        db.collection("coachResponseTimes").whereEqualTo("coachUid", coachUid).observe(),
    ) { availability, reviews, timings ->
        CoachPublicSummary(
            availability = availability,
            reviews = reviews.mapNotNull(::review).sortedByDescending { it.createdAt },
            responseTimesMs = timings.sortedByDescending { it.getTimestamp("answeredAt")?.toDate()?.time ?: 0 }.take(20).mapNotNull {
                val asked = it.getTimestamp("askedAt")?.toDate()?.time
                val answered = it.getTimestamp("answeredAt")?.toDate()?.time
                if (asked != null && answered != null && answered >= asked) answered - asked else null
            },
        )
    }

    /** Sends to the open chat, or answers the question that is waiting for this coach. */
    suspend fun send(coachUid: String, conversation: CoachingConversation, text: String) {
        val uid = requireNotNull(auth.currentUid) { "Sign in again to send a message." }
        require(uid == coachUid || uid == conversation.playerUid)
        require(text.trim().length in 1..1000) { "Keep your message between 1 and 1,000 characters." }
        require(!hasCoachingContact(text)) { "Contact details and links are not supported." }
        val waiting = conversation.waitingQuestion
        if (uid == coachUid && waiting != null) { respond(waiting, text); return }
        val ref = db.collection("coachingMessages").document()
        ref.set(mapOf("id" to ref.id, "coachUid" to coachUid, "playerUid" to conversation.playerUid, "senderUid" to uid,
            "text" to text.trim(), "createdAt" to FieldValue.serverTimestamp())).awaitCloud()
    }

    suspend fun markRead(coachUid: String, conversation: CoachingConversation, forCoach: Boolean) {
        conversation.questions.forEach { if (it.unread(forCoach) > 0) markRead(it, forCoach) }
        val at = conversation.unreadChat(forCoach).maxOfOrNull { it.createdAt } ?: return
        db.collection("coachingReads").document("${coachUid}_${conversation.playerUid}").set(mapOf(
            "coachUid" to coachUid, "playerUid" to conversation.playerUid,
            (if (forCoach) "coachReadAt" else "playerReadAt") to com.google.firebase.Timestamp(java.util.Date(at)),
        ), SetOptions.merge()).awaitCloud()
    }

    private suspend fun answer(question: CoachingQuestion, text: String) {
        require(auth.currentUid == question.coachUid) { "Only this coach can answer." }
        require(text.trim().length in 1..1000) { "Keep your answer between 1 and 1,000 characters." }
        require(!hasCoachingContact(text)) { "Contact details and links are not supported." }
        require(question.answer == null && question.createdAt > 0) { "This question has already been answered or is still syncing." }
        db.runTransaction { transaction ->
            val ref = db.collection("coachingQuestions").document(question.id)
            val existing = transaction.get(ref)
            require(existing.getString("answer") == null) { "This question has already been answered." }
            transaction.update(ref, mapOf("answer" to text.trim(), "answeredAt" to FieldValue.serverTimestamp()))
            transaction.set(db.collection("coachResponseTimes").document(question.id), mapOf(
                "coachUid" to question.coachUid, "askedAt" to requireNotNull(existing.getTimestamp("createdAt")), "answeredAt" to FieldValue.serverTimestamp(),
            ))
        }.awaitCloud()
    }

    private suspend fun respond(question: CoachingQuestion, text: String) {
        if (question.followUpCount == 0) { answer(question, text); return }
        require(auth.currentUid == question.coachUid)
        require(text.trim().length in 1..1000 && !hasCoachingContact(text))
        db.runTransaction { tx ->
            val ref = db.collection("coachingQuestions").document(question.id)
            val row = requireNotNull(this.question(tx.get(ref)))
            require(row.resolvedAt == null && row.awaitingCoach)
            val field = "followUp${row.followUpCount}"
            tx.update(ref, mapOf("$field.answer" to text.trim(), "$field.answeredAt" to FieldValue.serverTimestamp()))
        }.awaitCloud()
    }

    private suspend fun markRead(question: CoachingQuestion, forCoach: Boolean) {
        val at = question.messages.filter { it.fromCoach != forCoach }.maxOfOrNull { it.at } ?: return
        if (at <= ((if (forCoach) question.coachReadAt else question.playerReadAt) ?: 0L)) return
        val field = if (forCoach) "coachReadAt" else "playerReadAt"
        db.runTransaction { tx ->
            val ref = db.collection("coachingQuestions").document(question.id)
            val current = tx.get(ref).getTimestamp(field)?.toDate()?.time ?: 0L
            if (at > current) tx.update(ref, field, com.google.firebase.Timestamp(java.util.Date(at)))
        }.awaitCloud()
    }

    suspend fun rate(coachUid: String, sessionId: String, rating: Int, text: String) {
        val uid = requireNotNull(auth.currentUid)
        require(rating in 1..5 && text.trim().length <= 500)
        val id = coachingContextId(coachUid, uid, sessionId)
        val ref = db.collection("coachReviews").document(id)
        db.runTransaction { transaction ->
            val existing = transaction.get(ref)
            transaction.set(ref, mapOf(
                "id" to id, "coachUid" to coachUid, "playerUid" to uid, "sessionId" to sessionId,
                "rating" to rating, "text" to text.trim(),
                "createdAt" to (existing.getTimestamp("createdAt") ?: FieldValue.serverTimestamp()), "updatedAt" to FieldValue.serverTimestamp(),
            ))
        }.awaitCloud()
    }

    private fun presence(coachUid: String): Flow<CoachAvailability?> = refresh.flatMapLatest {
        val key = "${auth.currentUid}|presence-$coachUid"
        val records: Flow<List<CoachPresenceLease>?> = callbackFlow {
            val registration = db.collection("coachPresence").whereEqualTo("coachUid", coachUid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        failures.update { it + (key to "Coach status could not sync. Check your connection and retry.") }
                        trySend(null)
                    } else if (snapshot != null) {
                        failures.update { it - key }
                        val leases = snapshot.documents.mapNotNull { row ->
                            val status = row.getString("status")?.let { value -> CoachAvailability.entries.firstOrNull { it.name == value } }
                            val updated = row.getTimestamp("updatedAt")?.toDate()?.time
                            val expires = row.getTimestamp("expiresAt")?.toDate()?.time
                            if (status != null && updated != null && expires != null) CoachPresenceLease(status, updated, expires) else null
                        }
                        trySend(if (leases.isEmpty() && snapshot.metadata.hasPendingWrites()) null else leases)
                    }
                }
            awaitClose { registration.remove() }
        }
        combine(records, flow { while (true) { emit(System.currentTimeMillis()); delay(CoachPresencePolicy.CHECK_MS) } }) { rows, now ->
            rows?.let { CoachPresencePolicy.resolve(it, now) }
        }
    }

    private fun question(doc: DocumentSnapshot): CoachingQuestion? = runCatching {
        CoachingQuestion(doc.id, doc.getString("coachUid")!!, doc.getString("playerUid")!!, doc.getString("sessionId"),
            CoachingTopic.valueOf(doc.getString("topic")!!), doc.getString("text")!!, doc.getTimestamp("createdAt")?.toDate()?.time ?: 0,
            doc.getString("answer"), doc.getTimestamp("answeredAt")?.toDate()?.time,
            followUp(doc, "followUp1"), followUp(doc, "followUp2"),
            doc.getTimestamp("resolvedAt")?.toDate()?.time, doc.getTimestamp("coachReadAt")?.toDate()?.time,
            doc.getTimestamp("playerReadAt")?.toDate()?.time)
    }.getOrNull()
    private fun followUp(doc: DocumentSnapshot, field: String): CoachingFollowUp? {
        val data = doc.get(field) as? Map<*, *> ?: return null
        fun time(key: String) = (data[key] as? com.google.firebase.Timestamp)?.toDate()?.time
        return CoachingFollowUp(data["text"] as? String ?: return null, time("createdAt") ?: 0L,
            data["answer"] as? String, time("answeredAt"))
    }
    private fun chatMessage(doc: DocumentSnapshot): CoachingChatMessage? = runCatching {
        CoachingChatMessage(doc.id, doc.getString("coachUid")!!, doc.getString("playerUid")!!, doc.getString("senderUid")!!,
            doc.getString("text")!!, doc.getTimestamp("createdAt")?.toDate()?.time ?: 0)
    }.getOrNull()
    private fun read(doc: DocumentSnapshot): CoachingRead? = runCatching {
        CoachingRead(doc.getString("coachUid")!!, doc.getString("playerUid")!!,
            doc.getTimestamp("coachReadAt")?.toDate()?.time ?: 0, doc.getTimestamp("playerReadAt")?.toDate()?.time ?: 0)
    }.getOrNull()
    private fun review(doc: DocumentSnapshot): CoachReview? = runCatching {
        CoachReview(doc.id, doc.getString("coachUid")!!, doc.getString("playerUid")!!, doc.getString("sessionId")!!,
            doc.getLong("rating")!!.toInt(), doc.getString("text").orEmpty(), doc.getTimestamp("createdAt")?.toDate()?.time ?: 0)
    }.getOrNull()
}
