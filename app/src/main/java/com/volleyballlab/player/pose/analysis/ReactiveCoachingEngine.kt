package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CueId

enum class CoachingEventType { Correction, Improvement, RepMilestone, Tracking, Rotation }

data class CoachingEvent(
    val id: Long,
    val type: CoachingEventType,
    val text: String,
    val timestampMs: Long,
    val priority: Int,
)

/** One-shot measured-event templates. Speech playback is owned by the screen. */
class ReactiveCoachingEngine(
    private val minCueGapMs: Long = 4_000L,
    private val repeatCueGapMs: Long = 12_000L,
) {
    private var sequence = 0L
    private var lastEventMs: Long? = null
    private val lastKeyMs = mutableMapOf<String, Long>()
    private var activeCorrection: CueId? = null
    private var trackingWasLost = false
    private var correctionVariant = 0
    private data class Pending(val type: CoachingEventType, val key: String, val text: String)
    private var pending: Pending? = null

    fun reset() {
        lastEventMs = null; lastKeyMs.clear(); activeCorrection = null
        trackingWasLost = false; correctionVariant = 0
        pending = null
    }

    fun onPosture(ui: LivePostureUi, timestampMs: Long): CoachingEvent? {
        if (ui.trackingLost) return onTracking(true, timestampMs)
        if (trackingWasLost) {
            trackingWasLost = false; activeCorrection = null
            if (pending?.type == CoachingEventType.Tracking) pending = null
        }
        val cue = ui.displayedCue
        if (cue != null) {
            activeCorrection = cue.id
            val status = ui.statuses.firstOrNull { it.cue.id == cue.id }
            return emit(CoachingEventType.Correction, "correction:${cue.id}", shortCueText(cue.id, status?.side, status?.measured), timestampMs)
        }
        val improved = activeCorrection?.takeIf { id ->
            ui.isGood && ui.statuses.any { it.cue.id == id && (it.score ?: 0f) >= 0.92f }
        }
        if (improved != null) {
            val event = emit(CoachingEventType.Improvement, "improved:$improved", improvement(improved), timestampMs)
            if (event != null) activeCorrection = null
            return event
        }
        return null
    }

    fun onRep(assessment: LiveRepAssessment, totalReps: Int, streak: Int, timestampMs: Long): CoachingEvent? {
        // A high partial score with missing cue coverage earns no success event.
        if (!assessment.isAssessable) return null
        val improved = activeCorrection?.takeIf { id ->
            assessment.isGood && assessment.statuses.any { it.cue.id == id && (it.score ?: 0f) >= 0.92f }
        }
        if (improved != null) {
            val event = emit(CoachingEventType.Improvement, "improved:$improved", improvement(improved), timestampMs)
            if (event != null) activeCorrection = null
            return event
        }
        if (assessment.isGood && streak >= 3 && streak % 3 == 0) {
            return emit(CoachingEventType.RepMilestone, "streak:$streak", "$streak good reps in a row. Keep that rhythm.", timestampMs)
        }
        if (totalReps > 0 && totalReps % 5 == 0) {
            return emit(CoachingEventType.RepMilestone, "reps:$totalReps", "$totalReps reps completed. Keep your next movement controlled.", timestampMs)
        }
        return null
    }

    fun onTracking(lost: Boolean, timestampMs: Long): CoachingEvent? {
        if (!lost) {
            trackingWasLost = false
            if (pending?.type == CoachingEventType.Tracking) pending = null
            return null
        }
        activeCorrection = null
        val first = !trackingWasLost
        trackingWasLost = true
        if (!first) return null
        return emitOrQueue(CoachingEventType.Tracking, "tracking", "Pause for a moment. Step back so I can see your body clearly.", timestampMs)
    }

    fun onRotation(message: String, timestampMs: Long): CoachingEvent? {
        activeCorrection = null
        return emitOrQueue(CoachingEventType.Rotation, "rotation:$message", message, timestampMs)
    }

    /** Call while turning as well as performing reps; queued guidance is still paced. */
    fun drainDue(timestampMs: Long): CoachingEvent? {
        val next = pending ?: return null
        val event = emit(next.type, next.key, next.text, timestampMs) ?: return null
        pending = null
        return event
    }

    fun cancelPendingGuidance() { pending = null }

    private fun emitOrQueue(type: CoachingEventType, key: String, text: String, nowMs: Long): CoachingEvent? {
        val immediate = emit(type, key, text, nowMs)
        if (immediate != null) { pending = null; return immediate }
        // A turn instruction supersedes a tracking prompt, not vice versa.
        if (type == CoachingEventType.Rotation || pending?.type != CoachingEventType.Rotation) pending = Pending(type, key, text)
        return null
    }

    fun assessmentSummary(assessment: LiveRepAssessment): String {
        if (!assessment.isAssessable) return "Some cues were obscured. Let's check another view."
        val worst = assessment.statuses.filter { it.isMeasurable }.minByOrNull { it.score!! }
            ?: return "Let's check another view."
        if ((worst.score ?: 0f) < 0.80f) return shortCueText(worst.cue.id, worst.side, worst.measured)
        if ((worst.score ?: 0f) < 1f) return "That rep met the current form-score target."
        return when (worst.cue.id) {
            CueId.PassShouldersLevel -> "Your shoulders look level."
            CueId.PassPlatformStraight -> "Your platform looks straight."
            CueId.PassKneeFlexion, CueId.SetKneeFlexion, CueId.SpikeApproachLoad, CueId.BlockKneeLoad -> "Your knee bend is in the target range."
            else -> "The cues I could measure are in range."
        }
    }

    private fun emit(type: CoachingEventType, key: String, text: String, nowMs: Long): CoachingEvent? {
        val last = lastEventMs
        val duplicate = lastKeyMs[key]
        if (last != null && nowMs - last < minCueGapMs || duplicate != null && nowMs - duplicate < repeatCueGapMs) return null
        lastEventMs = nowMs; lastKeyMs[key] = nowMs
        if (type == CoachingEventType.Correction) correctionVariant++
        return CoachingEvent(++sequence, type, text, nowMs, when (type) {
            CoachingEventType.Rotation -> 3
            CoachingEventType.Tracking -> 2
            CoachingEventType.Correction -> 1
            CoachingEventType.Improvement, CoachingEventType.RepMilestone -> 0
        })
    }

    fun shortCueText(id: CueId, side: Side? = null, measured: Float? = null): String {
        val arm = when (side) { Side.Left -> "left arm"; Side.Right -> "right arm"; null -> "arms" }
        val tip = when (id) {
            CueId.ServeContactArmExtension, CueId.SpikeArmExtension -> "Reach through contact with your $arm straighter."
            CueId.ServeReachHeight -> "Lift your hitting elbow and reach higher at contact."
            CueId.ServeTrunkLean -> "Keep your trunk tall through the serve."
            CueId.ServeTossArmLift -> "Raise your tossing arm steadily before the swing."
            CueId.PassPlatformStraight -> "Straighten both elbows and keep your platform together."
            CueId.PassKneeFlexion -> if (measured != null && measured < 110f) "Rise a little from your crouch as you receive the ball." else "Bend your knees more as you receive the ball."
            CueId.PassTrunkAngle -> "Lean from your hips and keep your chest over the ball."
            CueId.PassShouldersLevel -> "Keep your shoulders level as you pass."
            CueId.SetHandsAboveForehead -> "Meet the ball with your hands above your forehead."
            CueId.SetElbowFlexion -> "Keep your elbows comfortably bent before you release the set."
            CueId.SetKneeFlexion -> "Load your knees before driving up into the set."
            CueId.SetExtensionFollowThrough -> "Extend your arms through the finish of the set."
            CueId.SpikeApproachLoad -> "Load your knees before taking off."
            CueId.SpikeJumpHeight -> "Drive up from your loaded legs before the hit."
            CueId.SpikeTrunkDrive -> "Keep your trunk controlled as you drive into contact."
            CueId.BlockArmExtension -> "Reach with straighter arms at the top of your block."
            CueId.BlockHandSeparation -> "Keep your hands about shoulder width apart."
            CueId.BlockPenetration -> "Reach your hands forward over the net."
            CueId.BlockKneeLoad -> "Bend your knees before pushing up for the block."
        }
        return if (correctionVariant % 2 == 0) tip else "Next rep: ${tip.replaceFirstChar { it.lowercase() }}"
    }

    private fun improvement(id: CueId): String = when (id) {
        CueId.PassPlatformStraight -> "Better. Your platform is straighter."
        CueId.PassShouldersLevel -> "Better. Your shoulders look level."
        CueId.PassKneeFlexion, CueId.SetKneeFlexion, CueId.SpikeApproachLoad, CueId.BlockKneeLoad -> "Better. Your knee loading improved."
        CueId.ServeTrunkLean, CueId.PassTrunkAngle, CueId.SpikeTrunkDrive -> "Better. Your trunk position improved."
        CueId.ServeReachHeight -> "Better. You reached higher through contact."
        CueId.ServeTossArmLift -> "Better. Your tossing arm is lifting steadily."
        CueId.SetHandsAboveForehead -> "Better. Your hands met the ball above your forehead."
        CueId.BlockHandSeparation -> "Better. Your hand spacing improved."
        CueId.BlockPenetration -> "Better. Your hands reached farther forward."
        CueId.SpikeJumpHeight -> "Better. Your jump drive improved."
        CueId.ServeContactArmExtension, CueId.SpikeArmExtension,
        CueId.SetElbowFlexion, CueId.SetExtensionFollowThrough, CueId.BlockArmExtension -> "Better. Your arm position improved."
    }
}
