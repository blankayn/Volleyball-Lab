package com.volleyballlab.player.data.assessment

import com.google.firebase.firestore.FirebaseFirestore
import com.volleyballlab.player.data.cloud.awaitCloud
import com.volleyballlab.player.domain.model.AssessmentAnswers
import com.volleyballlab.player.domain.model.AthletePlan
import com.volleyballlab.player.domain.model.AthleteState
import com.volleyballlab.player.domain.repository.AuthRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.longOrNull

/** The athlete's own assessment and plans. Only they can read it. */
interface AthleteAssessmentRepository {
    /** Null until the account's record has been looked up. */
    val state: Flow<AthleteState?>
    suspend fun saveAnswers(uid: String, answers: AssessmentAnswers)
    suspend fun savePlan(uid: String, answers: AssessmentAnswers, plan: AthletePlan)
}

/**
 * `athleteAssessments/{uid}`: the answers and the current plan, with every earlier plan in
 * `plans/{id}` beneath it. Saves are queued by Firestore when offline, so waiting for the
 * server is bounded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreAthleteAssessmentRepository(private val db: FirebaseFirestore, private val auth: AuthRepository) : AthleteAssessmentRepository {
    override val state: Flow<AthleteState?> = auth.currentUser.map { it?.uid }.distinctUntilChanged()
        .flatMapLatest { uid -> if (uid == null) flowOf(null) else watch(uid) }

    private fun watch(uid: String): Flow<AthleteState?> = callbackFlow {
        trySend(null)
        val registration = document(uid).addSnapshotListener { snapshot, error ->
            // An empty cache is not an answer; the server's is.
            if (snapshot != null && error == null && (snapshot.exists() || !snapshot.metadata.isFromCache))
                trySend(AthleteAssessmentSchema.read(snapshot.data.orEmpty()))
        }
        awaitClose { registration.remove() }
    }

    override suspend fun saveAnswers(uid: String, answers: AssessmentAnswers) {
        check(auth.currentUid == uid) { "Account changed." }
        val task = document(uid).set(AthleteAssessmentSchema.fields(answers, null), com.google.firebase.firestore.SetOptions.mergeFields("version", "answers", "updatedAt"))
        withTimeoutOrNull(SAVE_MS) { task.awaitCloud() }
    }

    override suspend fun savePlan(uid: String, answers: AssessmentAnswers, plan: AthletePlan) {
        check(auth.currentUid == uid) { "Account changed." }
        val batch = db.batch()
        batch.set(document(uid), AthleteAssessmentSchema.fields(answers, plan))
        batch.set(document(uid).collection("plans").document(plan.id), AthleteAssessmentSchema.plan(plan))
        val task = batch.commit()
        withTimeoutOrNull(SAVE_MS) { task.awaitCloud() }
    }

    private fun document(uid: String) = db.collection("athleteAssessments").document(uid)

    private companion object { const val SAVE_MS = 8_000L }
}

internal object AthleteAssessmentSchema {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    fun fields(answers: AssessmentAnswers, plan: AthletePlan?): Map<String, Any?> = mapOf(
        "version" to 1L, "answers" to toCloud(json.encodeToJsonElement(answers)),
        "plan" to plan?.let(::plan), "updatedAt" to maxOf(answers.savedAtEpochMs, plan?.createdAtEpochMs ?: 0L),
    )

    @Suppress("UNCHECKED_CAST")
    fun plan(plan: AthletePlan): Map<String, Any?> = toCloud(json.encodeToJsonElement(plan)) as Map<String, Any?>

    /** A part this version cannot read is treated as missing, so the athlete is simply asked again. */
    fun read(data: Map<String, Any?>): AthleteState = AthleteState(
        answers = runCatching { json.decodeFromJsonElement<AssessmentAnswers>(toJson(data["answers"])) }.getOrNull(),
        plan = runCatching { json.decodeFromJsonElement<AthletePlan>(toJson(data["plan"])) }.getOrNull(),
    )

    private fun toCloud(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonObject -> value.mapValues { toCloud(it.value) }
        is JsonArray -> value.map(::toCloud)
        is JsonPrimitive -> if (value.isString) value.content else value.booleanOrNull ?: value.longOrNull ?: value.doubleOrNull
    }

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to toJson(it.value) })
        is List<*> -> JsonArray(value.map(::toJson))
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> error("Unsupported assessment value")
    }
}
