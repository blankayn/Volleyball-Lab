package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.FormCue
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame

data class CueLiveStatus(
    val cue: FormCue,
    val measured: Float?,
    val score: Float?,
    val affectedPoints: Set<BodyPoint> = AngleCuePolicy.affectedPoints(cue.id),
    val side: Side? = null,
) {
    val isMeasurable: Boolean get() = score != null
}

data class LivePostureUi(
    val displayedCue: FormCue? = null,
    val measured: Float? = null,
    val score: Float? = null,
    /** True only when actual measured evidence passed, never for an empty frame. */
    val isGood: Boolean = false,
    val trackingLost: Boolean = false,
    val modelWarmingUp: Boolean = false,
    val modelUncertain: Boolean = false,
    val statuses: List<CueLiveStatus> = emptyList(),
    val affectedPoints: Set<BodyPoint> = emptySet(),
    val phase: RepPhase? = null,
) {
    val needsCorrection: Boolean get() = displayedCue != null
    companion object { val Idle = LivePostureUi() }
}

data class LiveRepAssessment(
    val score: Float?,
    /** Original catalog weight, without renormalizing around invisible cues. */
    val measurableWeight: Float,
    val isAssessable: Boolean,
    val isGood: Boolean,
    val statuses: List<CueLiveStatus>,
)

/** Phase-specific live measurements and full completed-rep assessment. */
class LivePostureCoach(
    private val skill: Skill,
    private val analyzer: SkillAnalyzer = SkillAnalyzers.forSkill(skill),
    private val cues: List<FormCue> = FormCueCatalog.forSkill(skill),
    private val config: Config = Config(),
    private val receivingEvaluator: ReceivingReadyPostureEvaluator? = null,
) {
    data class Config(
        val showThreshold: Float = 0.80f,
        val hideThreshold: Float = 0.92f,
        val enterFrames: Int = 6,
        val exitFrames: Int = 8,
        val minVisibility: Float = 0.40f,
        val refreshMs: Long = 500L,
        val goodRepScore: Float = 80f,
        val minMeasurableWeight: Float = 0.75f,
    )

    private var displayed: CueLiveStatus? = null
    private var pendingId: CueId? = null
    private var pendingCount = 0
    private var goodStreak = 0
    private var lastEmitMs = Long.MIN_VALUE
    private var lastEmitted = LivePostureUi.Idle
    private var lastFrameTimestampMs: Long? = null
    private var previousAngle: CameraAngle? = null

    fun reset() {
        displayed = null; pendingId = null; pendingCount = 0; goodStreak = 0
        lastEmitMs = Long.MIN_VALUE; lastEmitted = LivePostureUi.Idle
        lastFrameTimestampMs = null; previousAngle = null
        receivingEvaluator?.reset()
    }

    /** Explicitly drops old measurements rather than keeping a stale correction. */
    fun clearStale(): LivePostureUi {
        reset()
        return LivePostureUi(trackingLost = true).also { lastEmitted = it }
    }
    fun trackingLost(): LivePostureUi = clearStale()

    fun update(
        frame: PoseFrame,
        phase: RepPhase? = RepPhase.Preparation,
        angle: CameraAngle = CameraAngle.Front,
    ): LivePostureUi {
        val previous = lastFrameTimestampMs
        if (previous != null && frame.timestampMs - previous > 500L ||
            previousAngle != null && previousAngle != angle) reset()
        lastFrameTimestampMs = frame.timestampMs
        previousAngle = angle
        if (frame.torsoAndLimbVisibility() < config.minVisibility || angle == CameraAngle.Unknown) return clearStale()
        if (phase == null) return lastEmitted.copy(phase = null, trackingLost = false)
        val ready = if (skill == Skill.Receiving && analyzer is ReceiveAnalyzer &&
            angle == CameraAngle.Front && phase == RepPhase.Contact &&
            CameraOrientationDetector().detect(frame).angle == CameraAngle.Front) receivingEvaluator?.onFrame(frame) else null
        val statuses = measureLive(frame, ready, phase, angle)
        if (statuses.none { it.isMeasurable }) {
            // No phase evidence differs from tracking loss. Keep the previous
            // completed-rep correction while the athlete prepares the next rep.
            return lastEmitted.copy(isGood = false, phase = phase, trackingLost = false, modelWarmingUp = ready?.decision == ReceivingReadyDecision.WarmingUp,
                modelUncertain = ready?.decision == ReceivingReadyDecision.Uncertain)
        }
        return judge(statuses, frame.timestampMs, phase, ready)
    }

    /** Only the observed phase is inserted; future contact is never invented. */
    fun measureLive(
        frame: PoseFrame,
        receivingEvaluation: ReceivingReadyEvaluation? = null,
        phase: RepPhase = RepPhase.Preparation,
        angle: CameraAngle = CameraAngle.Front,
    ): List<CueLiveStatus> {
        val window = RepWindow(0, listOf(frame), mapOf(phase to frame), Angles.hipCentreImage(frame).y)
        val statuses = cues.filter { it.phase == phase && it.id !in NON_LIVE_CUES }.map { cue ->
            val measured = if (AngleCuePolicy.isSupported(cue.id, angle, frame)) {
                runCatching { analyzer.measure(cue, window) }.getOrNull()?.takeIf { it.isFinite() }
            } else null
            CueLiveStatus(cue, measured, cue.score(measured), AngleCuePolicy.affectedPoints(cue.id, frame), AngleCuePolicy.measuredSide(cue.id, frame))
        }
        if (skill != Skill.Receiving || analyzer !is ReceiveAnalyzer || angle != CameraAngle.Front) return statuses
        val knee = statuses.firstOrNull { it.cue.id == CueId.PassKneeFlexion }?.measured
        val trunk = statuses.firstOrNull { it.cue.id == CueId.PassTrunkAngle }?.measured
        val issue = receivingEvaluation?.features?.let {
            ReceivingReadyPostureRule.issue(it[0], it[3]) ?: if (it[0] > ReceivingReadyPostureRule.KNEE_FLOOR_DEGREES) ReceivingReadyPostureRule.Issue.Knees else ReceivingReadyPostureRule.Issue.Trunk
        }
        return statuses.map { status ->
            val readinessCue = status.cue.id == CueId.PassKneeFlexion || status.cue.id == CueId.PassTrunkAngle
            val override = when (receivingEvaluation?.decision) {
                ReceivingReadyDecision.Ok -> if (readinessCue) 1f else null
                ReceivingReadyDecision.Fault -> when (status.cue.id) {
                    CueId.PassKneeFlexion -> if (issue == ReceivingReadyPostureRule.Issue.Knees) 0f else 1f
                    CueId.PassTrunkAngle -> if (issue == ReceivingReadyPostureRule.Issue.Trunk) 0f else 1f
                    else -> null
                }
                ReceivingReadyDecision.WarmingUp, ReceivingReadyDecision.Uncertain, ReceivingReadyDecision.InsufficientQuality -> if (readinessCue) Float.NaN else null
                null -> ReceivingReadyPostureRule.scoreOverride(status.cue.id, knee, trunk)
            }
            when {
                status.measured == null -> status
                override == null -> status
                override.isNaN() -> status.copy(score = null)
                else -> status.copy(score = override)
            }
        }
    }

    fun assessCompletedRep(window: RepWindow, angle: CameraAngle = CameraAngle.Front): LiveRepAssessment {
        val result = SessionScorer().scoreRep(window, skill, analyzer, receivingEvaluator, angle)
        val statuses = cues.map { cue ->
            val scored = result.cueScores.first { it.cueId == cue.id }
            val frame = window.frameFor(cue.phase)
            val side = AngleCuePolicy.completedSide(cue.id, window)
            CueLiveStatus(cue, scored.measured, scored.score, AngleCuePolicy.affectedPoints(cue.id, frame, side), side)
        }
        val weight = statuses.filter { it.isMeasurable }.sumOf { it.cue.weight.toDouble() }.toFloat()
        val score = if (weight <= 0f) null else statuses.sumOf { ((it.score ?: 0f) * it.cue.weight).toDouble() }.toFloat() / weight * 100f
        val assessable = score != null && weight + 1e-5f >= config.minMeasurableWeight
        return LiveRepAssessment(score, weight, assessable, assessable && score!! >= config.goodRepScore, statuses)
    }

    fun updateCompletedRep(assessment: LiveRepAssessment, timestampMs: Long): LivePostureUi {
        val worst = assessment.statuses.filter { it.isMeasurable }.minByOrNull { it.score!! }
        displayed = worst?.takeIf { it.score!! < config.showThreshold }
        pendingId = null; pendingCount = 0; goodStreak = 0
        return emit(toUi(assessment.statuses, assessment.isGood, phase = RepPhase.FollowThrough), timestampMs, true)
    }

    private fun judge(statuses: List<CueLiveStatus>, timestamp: Long, phase: RepPhase, ready: ReceivingReadyEvaluation?): LivePostureUi {
        val worst = statuses.filter { it.isMeasurable }.minByOrNull { it.score!! } ?: return lastEmitted
        if (worst.score!! >= config.hideThreshold) {
            goodStreak++; pendingId = null; pendingCount = 0
            // A good preparation cue cannot clear a failed contact cue.
            if (goodStreak >= config.exitFrames && (displayed == null || displayed!!.cue.phase == phase)) displayed = null
        } else if (worst.score < config.showThreshold) {
            goodStreak = 0
            if (displayed?.cue?.id == worst.cue.id) displayed = worst
            else {
                if (pendingId == worst.cue.id) pendingCount++ else { pendingId = worst.cue.id; pendingCount = 1 }
                if (pendingCount >= config.enterFrames || displayed == null && worst.score < 0.35f) {
                    displayed = worst; pendingId = null; pendingCount = 0
                }
            }
        } else { goodStreak = 0; pendingCount = 0; pendingId = null }
        val warming = ready?.decision == ReceivingReadyDecision.WarmingUp
        val uncertain = ready?.decision == ReceivingReadyDecision.Uncertain
        return emit(toUi(statuses, displayed == null && !warming && !uncertain && worst.score >= config.showThreshold, phase, warming, uncertain), timestamp, false)
    }

    private fun toUi(statuses: List<CueLiveStatus>, good: Boolean, phase: RepPhase?, warming: Boolean = false, uncertain: Boolean = false) = LivePostureUi(
        displayedCue = displayed?.cue, measured = displayed?.measured, score = displayed?.score,
        isGood = good, modelWarmingUp = warming, modelUncertain = uncertain, statuses = statuses,
        affectedPoints = displayed?.affectedPoints.orEmpty(), phase = phase,
    )

    private fun emit(next: LivePostureUi, nowMs: Long, force: Boolean): LivePostureUi {
        val identityChanged = next.displayedCue?.id != lastEmitted.displayedCue?.id || next.isGood != lastEmitted.isGood ||
            next.trackingLost != lastEmitted.trackingLost || next.modelWarmingUp != lastEmitted.modelWarmingUp || next.modelUncertain != lastEmitted.modelUncertain
        if (force || identityChanged || lastEmitMs == Long.MIN_VALUE || nowMs - lastEmitMs >= config.refreshMs) {
            lastEmitted = next; lastEmitMs = nowMs
        }
        return lastEmitted
    }

    companion object { val NON_LIVE_CUES: Set<CueId> = setOf(CueId.SpikeJumpHeight) }
}
