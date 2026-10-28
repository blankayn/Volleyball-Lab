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

    suspend fun ask(coachUid: String, sessionId: String?, topic: CoachingTopic, text: String) {
        val uid = requireNotNull(auth.currentUid) { "Sign in again to ask a question." }
        require(text.trim().length in 1..300) { "Keep your question between 1 and 300 characters." }
        require(!hasCoachingLink(text)) { "Links are not supported in coaching questions." }
        require(topic.introductory == (sessionId == null)) { "Choose a topic for this training context." }
        val id = coachingContextId(coachUid, uid, sessionId)
        db.collection("coachingQuestions").document(id).set(mapOf(
            "id" to id, "coachUid" to coachUid, "playerUid" to uid, "sessionId" to sessionId,
            "topic" to topic.name, "text" to text.trim(), "createdAt" to FieldValue.serverTimestamp(),
            "answer" to null, "answeredAt" to null,
        )).awaitCloud()
    }

    suspend fun answer(question: CoachingQuestion, text: String) {
        require(auth.currentUid == question.coachUid) { "Only this coach can answer." }
        require(text.trim().length in 1..1000) { "Keep your answer between 1 and 1,000 characters." }
        require(!hasCoachingLink(text)) { "Links are not supported in coaching answers." }
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
            doc.getString("answer"), doc.getTimestamp("answeredAt")?.toDate()?.time)
    }.getOrNull()
    private fun review(doc: DocumentSnapshot): CoachReview? = runCatching {
        CoachReview(doc.id, doc.getString("coachUid")!!, doc.getString("playerUid")!!, doc.getString("sessionId")!!,
            doc.getLong("rating")!!.toInt(), doc.getString("text").orEmpty(), doc.getTimestamp("createdAt")?.toDate()?.time ?: 0)
    }.getOrNull()
}
