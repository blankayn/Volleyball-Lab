package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.Skill

data class LiveCueMeasurement(val cueId: CueId, val measured: Float)
data class LiveCoachingEvidence(val skill: Skill, val angle: CameraAngle, val reps: List<List<LiveCueMeasurement>>) {
    /** Same eligibility rule as the worker: repeated, visible evidence only. */
    fun focus(): LiveCoachingFocus? = FormCueCatalog.forSkill(skill).mapNotNull { cue ->
        val values = reps.flatMap { rep -> rep.filter { it.cueId == cue.id }.map { it.measured } }
        val misses = values.count { (cue.score(it) ?: 1f) < .8f }
        val average = values.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: return@mapNotNull null
        if (values.size < 3 || misses < 2 || average in cue.target) return@mapNotNull null
        LiveCoachingFocus(cue.id, if (average < cue.target.start) "below" else "above",
            values.map { 1f - (cue.score(it) ?: 1f) }.average().toFloat(), average)
    }.maxByOrNull { it.severity }
}

data class LiveCoachingFocus(val cueId: CueId, val position: String, val severity: Float, val measured: Float)
data class LiveCoachingAdvice(
    val cueId: CueId,
    val text: String,
    val aiGenerated: Boolean = false,
    /** Private temporary WAV, deleted by the capture screen's audio controller. */
    val voicePath: String? = null,
)

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
