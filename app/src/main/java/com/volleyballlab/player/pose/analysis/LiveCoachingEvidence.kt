package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.ExperienceLevel
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.Skill

data class LiveCueMeasurement(val cueId: CueId, val measured: Float)
data class LiveCoachingEvidence(val skill: Skill, val angle: CameraAngle, val reps: List<List<LiveCueMeasurement>>) {
    /** Same eligibility rule as the worker: repeated, visible evidence only. */
    fun focus(): LiveCoachingFocus? = FormCueCatalog.forSkill(skill).mapNotNull { cue ->
        val values = valuesFor(cue.id)
        val misses = values.count { (cue.score(it) ?: 1f) < .8f }
        val average = values.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: return@mapNotNull null
        if (values.size < 3 || misses < 2 || average in cue.target) return@mapNotNull null
        LiveCoachingFocus(cue.id, if (average < cue.target.start) "below" else "above",
            values.map { 1f - (cue.score(it) ?: 1f) }.average().toFloat(), average)
    }.maxByOrNull { it.severity }

    fun valuesFor(cueId: CueId): List<Float> = reps.flatMap { rep -> rep.filter { it.cueId == cueId }.map { it.measured } }

    /** Measured at least three times in this view and never missed. Jump height is never praised. */
    fun held(cueId: CueId): Boolean {
        val cue = FormCueCatalog.forSkill(skill).firstOrNull { it.id == cueId } ?: return false
        val values = valuesFor(cueId)
        return cueId != CueId.SpikeJumpHeight && values.size >= 3 && values.all { (cue.score(it) ?: 0f) >= .8f }
    }
}

data class LiveCoachingFocus(val cueId: CueId, val position: String, val severity: Float, val measured: Float)

enum class LiveAdviceKind { Correction, Praise }

data class LiveCoachingAdvice(
    val cueId: CueId,
    val text: String,
    val aiGenerated: Boolean = false,
    /** Private temporary WAV, deleted by the capture screen's audio controller. */
    val voicePath: String? = null,
    val kind: LiveAdviceKind = LiveAdviceKind.Correction,
)

/**
 * What the cloud coach may know about the turn, beyond the measurements: closed
 * values only. No name, goal text, coach note or past-session fact has a field here.
 */
data class LiveCoachingTurnContext(
    val turn: Turn = Turn.Break,
    val breakIndex: Int = 0,
    val experience: ExperienceLevel? = null,
    val isAthleteFocus: Boolean = false,
    val corrected: Set<CueId> = emptySet(),
) {
    enum class Turn(val wire: String) { Break("break"), Closing("closing") }
}

/** The request and response shapes shared with ai-worker/src/live-coaching.mjs. */
object LiveCoachingWire {
    /** Null when there is nothing the worker would accept: fewer than three completed reps. */
    fun request(evidence: LiveCoachingEvidence, voice: Boolean, context: LiveCoachingTurnContext? = null): Map<String, Any?>? {
        if (evidence.reps.size !in 3..10 || evidence.angle == CameraAngle.Unknown) return null
        // The legacy shape asks only for a correction, so it needs a local focus.
        if (context == null && evidence.focus() == null) return null
        val skillCues = FormCueCatalog.forSkill(evidence.skill).map { it.id }.toSet()
        val base = mapOf<String, Any?>("skill" to evidence.skill.name, "angle" to evidence.angle.name, "voice" to voice,
            "reps" to evidence.reps.map { rep -> rep.map { mapOf("cueId" to it.cueId.name, "measured" to it.measured) } })
        if (context == null) return base
        return base + ("context" to mapOf(
            "v" to 1, "turn" to context.turn.wire, "breakIndex" to context.breakIndex.coerceIn(0, 2),
            "experience" to context.experience?.name, "isAthleteFocus" to context.isAthleteFocus,
            "corrected" to context.corrected.filter { it in skillCues }.map { it.name }.sorted().take(4),
        ))
    }

    /**
     * Accepts the server's cue when it belongs to this skill and this device
     * measured it in this view. Praise also needs the local measurements to agree.
     */
    fun decode(result: Map<*, *>, evidence: LiveCoachingEvidence, isPostureOnly: (String) -> Boolean): LiveCoachingAdvice? {
        if (result["status"] != "ready") return null
        val cueId = CueId.entries.firstOrNull { it.name == result["cueId"] } ?: return null
        if (FormCueCatalog.forSkill(evidence.skill).none { it.id == cueId } || evidence.valuesFor(cueId).isEmpty()) return null
        val kind = when (result["kind"]) {
            null, "correction" -> LiveAdviceKind.Correction
            "praise" -> LiveAdviceKind.Praise
            else -> return null
        }
        if (kind == LiveAdviceKind.Praise && !evidence.held(cueId)) return null
        if (kind == LiveAdviceKind.Correction && evidence.held(cueId)) return null
        val text = (result["text"] as? String)?.trim() ?: return null
        if (text.isBlank() || text.length > 200 || text.any(Char::isDigit) || !isPostureOnly(text)) return null
        return LiveCoachingAdvice(cueId, text, result["source"] == "groq", kind = kind)
    }
}

/** Only measurable, finite values from completed reps enter a break summary. */
class LiveCoachingEvidenceBuffer {
    private val reps = mutableListOf<List<LiveCueMeasurement>>()
    fun add(assessment: LiveRepAssessment) {
        if (reps.size >= 10) return
        reps += assessment.statuses.filter { it.score?.isFinite() == true && it.score in 0f..1f && it.measured?.isFinite() == true }
            .map { LiveCueMeasurement(it.cue.id, requireNotNull(it.measured)) }
    }
    fun snapshot(skill: Skill, angle: CameraAngle) = LiveCoachingEvidence(skill, angle, reps.map { it.toList() })
    fun reset() = reps.clear()
}
