package com.volleyballlab.player.data.session

import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.isValidFor
import kotlinx.serialization.json.*

object ProgressExport {
    private val format = Json { prettyPrint = true; encodeDefaults = true }
    fun json(sessions: List<SessionResult>): String = format.encodeToString(
        buildJsonObject {
            put("schemaVersion", 1)
            put("scoreSource", "final_session_analysis")
            put("angleMetricsSource", "live_estimates")
            put("scoreScale", "0_to_100")
            put("sessions", Json.encodeToJsonElement(sessions.map { it.toRecord().copy(videoPath = null) }))
        },
    )
    fun csv(sessions: List<SessionResult>): String = buildString {
        append("session_id,skill,started_at_epoch_ms,duration_ms,completed_reps,valid_final_reps,final_score,live_completed_reps,live_valid_reps\r\n")
        sessions.forEach { s ->
            append(listOf(s.id, s.skill.name, s.startedAtEpochMs, s.durationMs, s.repCount,
                s.reps.count { it.isValidFor(s.skill) }, s.formScore() ?: "", s.angleStages.takeIf { it.isNotEmpty() }?.sumOf { it.liveRepCount } ?: "",
                s.angleStages.takeIf { it.isNotEmpty() }?.sumOf { it.validRepCount } ?: "").joinToString(",") { value ->
                    "\"${value.toString().replace("\"", "\"\"")}\""
                })
            append("\r\n")
        }
    }
}
