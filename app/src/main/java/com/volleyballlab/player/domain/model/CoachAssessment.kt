package com.volleyballlab.player.domain.model

import kotlin.math.abs
import kotlin.math.roundToInt

data class ObservedCue(
    val cue: FormCue,
    val medianMeasured: Float,
    val referenceScore: Int,
    val measuredReps: Int,
    val belowReference: Int,
    val aboveReference: Int,
)

data class TechniqueCorrection(
    val evidence: ObservedCue,
    val explanation: String,
    val instruction: String,
    val drillId: String,
    val drillName: String,
)

data class ObservedImprovement(
    val cue: FormCue,
    val previousMedian: Float,
    val currentMedian: Float,
    val previousSessionId: String,
    val angle: CameraAngle,
)

data class CoachAssessment(
    val overallScore: Int?,
    val bodyPositioningScore: Int?,
    /** Static angles do not establish dynamic coordination or ball timing. */
    val coordinationScore: Int? = null,
    val timingScore: Int? = null,
    /** Observability is separate from a calibrated model probability. */
    val evidenceCoverage: Float,
    val calibratedModelConfidence: Float? = null,
    val meanMovementDurationMs: Long?,
    val meanStartToMotionPeakMs: Long?,
    val observedCues: List<ObservedCue>,
    val corrections: List<TechniqueCorrection>,
    val improvements: List<ObservedImprovement>,
    val medianStanceWidthShoulderRatio: Float? = null,
    val stanceObservedReps: Int = 0,
    val medianKneeLoadToArmPeakMs: Long? = null,
    val timingObservedReps: Int = 0,
)

/**
 * Explainable assessment across all five skills. Uses repeated, measurable cue
 * evidence and the catalogue's reference ranges; it never converts an
 * unvalidated classifier probability into a coaching diagnosis or a score.
 * These reference ranges remain provisional until reviewed by coaches.
 */
object CoachAssessmentEngine {
    const val MIN_REPS = 3
    const val MIN_COVERAGE = 0.75f

    fun assess(session: SessionResult, history: List<SessionResult> = emptyList()): CoachAssessment {
        val observed = observe(session)
        // Jump height is a performance estimate, not proof of faulty technique.
        val scored = observed.filter { it.cue.id != CueId.SpikeJumpHeight }
        val expectedWeight = FormCueCatalog.forSkill(session.skill).filter { it.id != CueId.SpikeJumpHeight }
            .sumOf { it.weight.toDouble() }
        val observedWeight = scored.sumOf { it.cue.weight.toDouble() }
        val coverage = (observedWeight / expectedWeight).toFloat().coerceIn(0f, 1f)
        val score = if (coverage + 1e-5f >= MIN_COVERAGE) {
            (scored.sumOf { it.referenceScore * it.cue.weight.toDouble() } / observedWeight).roundToInt().coerceIn(0, 100)
        } else null
        val corrections = scored.filter { evidence ->
            evidence.referenceScore < 80 &&
                maxOf(evidence.belowReference, evidence.aboveReference) >= MIN_REPS &&
                maxOf(evidence.belowReference, evidence.aboveReference).toFloat() / evidence.measuredReps >= 0.6f
        }.sortedBy { it.referenceScore }.take(3).map { evidence ->
            val drill = drillFor(session.skill)
            TechniqueCorrection(evidence, why(evidence.cue.id), instruction(evidence), drill.first, drill.second)
        }
        val validReps = session.reps.filter { it.durationMs in 1L..6000L && it.contactMs in it.startMs..it.endMs }
        val widths = validReps.mapNotNull { rep -> rep.motion?.takeIf {
            it.reliableFrameFraction.isFinite() && it.reliableFrameFraction in .8f..1f
        }?.stanceWidthShoulderRatio?.takeIf { it.isFinite() && it in .01f..10f } }.sorted()
        val lags = validReps.mapNotNull { rep -> rep.motion?.takeIf {
            it.reliableFrameFraction.isFinite() && it.reliableFrameFraction in .8f..1f
        }?.kneeLoadToArmPeakMs?.takeIf { it in -rep.durationMs..rep.durationMs } }.sorted()
        val improvements = session.angleStages.map { it.angle }.distinct().filter { it != CameraAngle.Unknown }.flatMap { angle ->
            val previous = history.filter { it.id != session.id && it.skill == session.skill && it.drillId == session.drillId &&
                it.startedAtEpochMs < session.startedAtEpochMs && it.angleStages.any { stage -> stage.angle == angle } }
                .maxByOrNull { it.startedAtEpochMs } ?: return@flatMap emptyList()
            val previousCues = observe(inView(previous, angle)).associateBy { it.cue.id }
            observe(inView(session, angle)).filter { it.cue.id != CueId.SpikeJumpHeight }.mapNotNull { current ->
                val old = previousCues[current.cue.id] ?: return@mapNotNull null
                // Raw measurements use the SAME current reference range, rather
                // than historical scores from potentially different ML models.
                val target = current.cue.target
                val minimumChange = when (current.cue.unit) {
                    CueUnit.Degrees -> 3f
                    CueUnit.Metres -> 0.02f
                    CueUnit.Ratio -> 0.03f
                }
                if (distance(old.medianMeasured, target) - distance(current.medianMeasured, target) < minimumChange) null
                else ObservedImprovement(current.cue, old.medianMeasured, current.medianMeasured, previous.id, angle)
            }
        }.take(3)
        return CoachAssessment(
            overallScore = score, bodyPositioningScore = score, evidenceCoverage = coverage,
            meanMovementDurationMs = validReps.takeIf { it.isNotEmpty() }?.map { it.durationMs }?.average()?.toLong(),
            meanStartToMotionPeakMs = validReps.takeIf { it.isNotEmpty() }
                ?.map { it.contactMs - it.startMs }?.average()?.toLong(),
            observedCues = observed, corrections = corrections, improvements = improvements,
            medianStanceWidthShoulderRatio = if (widths.size >= MIN_REPS) widths[widths.size / 2] else null,
            stanceObservedReps = widths.size,
            medianKneeLoadToArmPeakMs = if (lags.size >= MIN_REPS) lags[lags.size / 2] else null,
            timingObservedReps = lags.size,
        )
    }

    private fun observe(session: SessionResult): List<ObservedCue> = FormCueCatalog.forSkill(session.skill).mapNotNull { cue ->
        val measurements = session.reps.mapNotNull { rep ->
            if (rep.startMs >= rep.endMs || rep.contactMs !in rep.startMs..rep.endMs) return@mapNotNull null
            val candidates = rep.cueScores.filter { it.cueId == cue.id }
            if (candidates.size != 1) return@mapNotNull null
            val evidence = candidates.single()
            val recordedScore = evidence.score ?: return@mapNotNull null
            if (!recordedScore.isFinite() || recordedScore !in 0f..1f) return@mapNotNull null
            evidence.measured?.takeIf(Float::isFinite)
        }
        if (measurements.size < MIN_REPS) return@mapNotNull null
        val sorted = measurements.sorted()
        val middle = sorted.size / 2
        val median = if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
        ObservedCue(cue, median,
            (measurements.mapNotNull(cue::score).average() * 100).roundToInt(), measurements.size,
            measurements.count { it < cue.target.start }, measurements.count { it > cue.target.endInclusive })
    }

    private fun inView(session: SessionResult, angle: CameraAngle): SessionResult = session.copy(
        reps = session.reps.filter { rep -> session.angleStages.any { stage ->
            stage.angle == angle && rep.startMs >= stage.startMs && rep.endMs <= stage.endMs
        } },
    )

    private fun distance(value: Float, target: ClosedFloatingPointRange<Float>): Float = when {
        value < target.start -> abs(value - target.start)
        value > target.endInclusive -> abs(value - target.endInclusive)
        else -> 0f
    }

    private fun drillFor(skill: Skill): Pair<String, String> = when (skill) {
        Skill.Serving -> "serve-standing-float" to "Standing float serve"
        Skill.Receiving -> "receive-wall-platform" to "Wall platform passes"
        Skill.Setting -> "set-wall-reps" to "Wall setting reps"
        Skill.Spiking -> "spike-standing-swing" to "Standing arm swing"
        Skill.Blocking -> "block-ready-jumps" to "Ready-position block jumps"
    }

    private fun instruction(evidence: ObservedCue): String {
        val low = evidence.medianMeasured < evidence.cue.target.start
        return when (evidence.cue.id) {
            CueId.PassKneeFlexion, CueId.SetKneeFlexion, CueId.SpikeApproachLoad, CueId.BlockKneeLoad ->
                if (low) "Rise slightly from the deep crouch and rehearse a controlled ready position."
                else "Add a little knee bend before the upward movement, then reset between repetitions."
            CueId.ServeTrunkLean, CueId.PassTrunkAngle, CueId.SpikeTrunkDrive ->
                if (low) "Bring your chest slightly forward from the hips while keeping a controlled base."
                else "Reduce the forward trunk lean and rehearse a more upright, controlled position."
            CueId.SetElbowFlexion -> if (low) "Open your elbows slightly before extending through the set."
                else "Allow a comfortable elbow bend before extending through the set."
            CueId.BlockHandSeparation -> if (low) "Widen your hands slightly toward a comfortable shoulder-width position."
                else "Bring your hands closer together and rehearse the same spacing on each block."
            CueId.ServeContactArmExtension, CueId.SpikeArmExtension, CueId.PassPlatformStraight,
            CueId.SetExtensionFollowThrough, CueId.BlockArmExtension ->
                "Rehearse extending the elbows through the indicated phase, using slow controlled repetitions first."
            CueId.ServeReachHeight -> "Rehearse reaching higher with the hitting arm while keeping your trunk controlled."
            CueId.ServeTossArmLift -> "Rehearse a repeatable tossing-arm lift from the same ready position."
            CueId.PassShouldersLevel -> "Reset with level shoulders and keep the forearm platform steady."
            CueId.SetHandsAboveForehead -> "Rehearse a comfortable hand position above the forehead before extending."
            CueId.BlockPenetration -> "Rehearse a controlled forward reach with your hands during the block."
            CueId.SpikeJumpHeight -> "Repeat the recording with clear full-body framing before interpreting jump height."
        }
    }

    private fun why(id: CueId): String = when (id) {
        CueId.ServeContactArmExtension, CueId.SpikeArmExtension ->
            "An extended elbow supports a longer reach through the observed contact position."
        CueId.ServeReachHeight -> "The shoulder angle describes how high the hitting arm reaches relative to the trunk."
        CueId.ServeTrunkLean, CueId.SpikeTrunkDrive -> "Trunk position changes the alignment of the reaching arm and body."
        CueId.ServeTossArmLift -> "A repeatable tossing-arm preparation gives you a consistent position to rehearse."
        CueId.PassPlatformStraight -> "Straighter elbows help form a stable forearm platform."
        CueId.PassKneeFlexion -> "Knee flexion changes the height of your receiving position."
        CueId.PassTrunkAngle -> "Hip and trunk position determine how your upper body sits over your receiving base."
        CueId.PassShouldersLevel -> "Shoulder alignment changes the orientation of your passing platform."
        CueId.SetHandsAboveForehead -> "Hand height relative to the forehead is part of the observed setting position."
        CueId.SetElbowFlexion -> "Elbow bend describes the arm preparation available before extension."
        CueId.SetKneeFlexion, CueId.SpikeApproachLoad, CueId.BlockKneeLoad ->
            "Knee bend describes your preparation before the upward movement."
        CueId.SetExtensionFollowThrough -> "Arm extension shows how the setting movement continues through its finish."
        CueId.SpikeJumpHeight -> "This is a camera-derived jump estimate; it does not establish contact height or landing quality."
        CueId.BlockArmExtension -> "Elbow extension describes how fully your arms reach in the observed block position."
        CueId.BlockHandSeparation -> "Hand spacing affects the shape of the area covered by your block."
        CueId.BlockPenetration -> "Forward hand reach describes the position of your hands relative to your shoulders."
    }
}
