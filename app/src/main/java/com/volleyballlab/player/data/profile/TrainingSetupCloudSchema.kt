package com.volleyballlab.player.data.profile

import com.volleyballlab.player.domain.model.SavedTrainingSetup
import kotlinx.serialization.json.*

/** The same bounded shape is used for private Firestore and the local cache. */
object TrainingSetupCloudSchema {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun fields(value: SavedTrainingSetup): Map<String, Any?> =
        json.encodeToJsonElement(value).jsonObject.mapValues { toCloud(it.value) }
    fun read(value: Any?): SavedTrainingSetup? = runCatching {
        json.decodeFromJsonElement<SavedTrainingSetup>(toJson(value)).takeIf { it.version == 1 && it.hasOnboarded }
    }.getOrNull()
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
        else -> error("Unsupported training preference")
    }
}
