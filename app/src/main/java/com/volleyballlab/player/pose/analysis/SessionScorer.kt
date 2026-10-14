package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.CueScore
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.domain.model.RepResult
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.model.PoseFrame
import com.volleyballlab.player.pose.review.ReviewIssue
import com.volleyballlab.player.pose.review.VideoReviewFeedback

data class ScoredSession(val session: SessionResult, val issues: List<ReviewIssue>, val assessedCueCount: Int)

/** Pure scoring shared by completed live windows and the final session analysis. */
class SessionScorer(private val segmenter: RepSegmenter = RepSegmenter()) {
    fun score(
        sessionId: String,
        skill: Skill,
        drillId: String,
        startedAtEpochMs: Long,
        frames: List<PoseFrame>,
        receivingPostureEvaluator: ReceivingReadyPostureEvaluator? = null,
        /** Null retains scoring compatibility for older captures without view metadata. */
        angle: CameraAngle? = null,
    ): SessionResult {
        return scoreWithReview(sessionId, skill, drillId, startedAtEpochMs, frames, receivingPostureEvaluator, angle).session
    }

    fun scoreWithReview(
        sessionId: String,
        skill: Skill,
        drillId: String,
        startedAtEpochMs: Long,
        frames: List<PoseFrame>,
        receivingPostureEvaluator: ReceivingReadyPostureEvaluator? = null,
        angle: CameraAngle? = null,
    ): ScoredSession {
        val analyzer = SkillAnalyzers.forSkill(skill)
        val windows = segmenter.segment(frames, analyzer)
        val reps = windows.map {
            scoreRep(it, skill, analyzer, receivingPostureEvaluator, angle)
        }
        val feedback = VideoReviewFeedback()
        val issues = windows.zip(reps).flatMap { (window, rep) ->
            rep.cueScores.mapNotNull { scored ->
                val cue = FormCueCatalog.byId(scored.cueId) ?: return@mapNotNull null
                feedback.issueFor(window, rep.index + 1, cue.id, cue.phase,
                    scored.measured ?: return@mapNotNull null, scored.score ?: return@mapNotNull null)
            }
        }
        return ScoredSession(SessionResult(
            id = sessionId,
            skill = skill,
            drillId = drillId,
            startedAtEpochMs = startedAtEpochMs,
            durationMs = if (frames.size < 2) 0L else frames.last().timestampMs - frames.first().timestampMs,
            reps = reps,
        ), issues, reps.sumOf { rep -> rep.cueScores.count { it.score != null } })
    }

    fun scoreRep(
        window: RepWindow,
        skill: Skill,
        analyzer: SkillAnalyzer = SkillAnalyzers.forSkill(skill),
        receivingPostureEvaluator: ReceivingReadyPostureEvaluator? = null,
        angle: CameraAngle? = null,
    ): RepResult {
        val cues = FormCueCatalog.forSkill(skill)
        val measurements = cues.associate { cue ->
            val phase = window.frameFor(cue.phase)
            val side = AngleCuePolicy.completedSide(cue.id, window)
            val supported = angle == null || phase != null && AngleCuePolicy.isSupported(cue.id, angle, phase, side)
            cue.id to if (supported) analyzer.measure(cue, window)?.takeIf { it.isFinite() } else null
        }
        val contact = window.frameFor(RepPhase.Contact)
        val receivingLegsReliable = skill != Skill.Receiving ||
            (contact != null && ReceivingReadyPostureRule.hasReliableFullLegs(contact))
        // The model was evaluated on front-facing ready postures. Plausible
        // hidden features from a side/rear view must never become model verdicts.
        val readyViewSupported = angle == null || angle == CameraAngle.Front && contact != null &&
            CameraOrientationDetector().detect(contact).let { it.angle == CameraAngle.Front && it.confidence >= 0.55f }
        val ready = if (skill == Skill.Receiving && readyViewSupported) {
            receivingPostureEvaluator?.evaluate(window.frames)
        } else null
        val modelIssue = ready?.features?.let { features ->
            ReceivingReadyPostureRule.issue(features[0], features[3])
                ?: if (features[0] > ReceivingReadyPostureRule.KNEE_FLOOR_DEGREES) {
                    ReceivingReadyPostureRule.Issue.Knees
                } else ReceivingReadyPostureRule.Issue.Trunk
        }
        return RepResult(
            index = window.index,
            startMs = window.startMs,
            contactMs = window.contactMs,
            endMs = window.endMs,
            cueScores = cues.map { cue ->
                val measured = measurements[cue.id]
                val readinessCue = cue.id == CueId.PassKneeFlexion || cue.id == CueId.PassTrunkAngle
                val override = when (ready?.decision) {
                    ReceivingReadyDecision.Ok -> if (readinessCue) 1f else null
                    ReceivingReadyDecision.Fault -> when (cue.id) {
                        CueId.PassKneeFlexion -> if (modelIssue == ReceivingReadyPostureRule.Issue.Knees) 0f else 1f
                        CueId.PassTrunkAngle -> if (modelIssue == ReceivingReadyPostureRule.Issue.Trunk) 0f else 1f
                        else -> null
                    }
                    ReceivingReadyDecision.Uncertain, ReceivingReadyDecision.WarmingUp,
                    ReceivingReadyDecision.InsufficientQuality -> null
                    null -> if (skill == Skill.Receiving && receivingLegsReliable && readyViewSupported) {
                        ReceivingReadyPostureRule.scoreOverride(
                            cue.id, measurements[CueId.PassKneeFlexion], measurements[CueId.PassTrunkAngle],
                        )
                    } else null
                }
                val score = when {
                    measured == null -> null
                    skill == Skill.Receiving && readinessCue && !receivingLegsReliable -> null
                    readinessCue && ready?.decision in setOf(
                        ReceivingReadyDecision.Uncertain, ReceivingReadyDecision.WarmingUp,
                        ReceivingReadyDecision.InsufficientQuality,
                    ) -> null
                    override != null -> override
                    else -> cue.score(measured)
                }
                CueScore(cue.id, measured, score)
            },
        )
    }
}
