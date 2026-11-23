package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.DrillFocus
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.Skill

/**
 * The one decision on a finished movement: it is a repetition or it is not.
 *
 * ## Why this exists
 *
 * There used to be two judgements. [SkillAnalyzer.verdict] decided whether the
 * movement was the skill and added the rep; the form cues were scored
 * afterwards and decided what the coach said. So the voice could call a
 * movement wrong in the same breath as the count went up. Now the counter, the
 * saved session and the voice all read this result:
 *
 * - [MovementVerdict.NotCounted]: nothing is added, and the coach says why.
 * - [MovementVerdict.Counted]: the rep is added, and anything the coach says
 *   about it is a refinement for the next one, never that this one was wrong.
 *
 * ## What posture has to be wrong to lose the rep
 *
 * Only a cue that is *clearly* out: beyond its whole tolerance, where its score
 * reaches zero. The coaching line at 0.8 is not that line. On reps marked
 * correct in public coaching clips at least one cue was under 0.8 on 18 of 26
 * spikes and 14 of 17 blocks, and refusing those would refuse good technique;
 * at zero it is a handful. How high the platform is held is the exception,
 * because the platform is what a pass is: it loses the rep from
 * [PLATFORM_LIMIT], which separates the groupmate's 15 passes filmed as faults
 * (7 to 19 degrees below horizontal) from public front-view passes (50 to 80).
 *
 * A cue that could not be read in this view, or that the drill does not train
 * ([DrillFocus.excluded]), is never a reason.
 */
class RepJudge(
    private val skill: Skill,
    drillId: String? = null,
    private val analyzer: SkillAnalyzer = SkillAnalyzers.forSkill(skill),
) {
    private val cues = FormCueCatalog.poseCues(skill).filterNot { it.id in DrillFocus.excluded(drillId) }

    /** @param angle the guided view the rep was performed in, or null to read it from the movement itself. */
    fun judge(window: RepWindow, angle: CameraAngle? = null): MovementVerdict {
        val movement = analyzer.verdict(window)
        if (movement != MovementVerdict.Counted) return movement
        val view = angle ?: if (AnalyzerSupport.sideOn(window)) CameraAngle.Side else CameraAngle.Front
        val worst = cues.mapNotNull { cue ->
            val frame = analyzer.cueFrame(cue, window) ?: return@mapNotNull null
            val side = AngleCuePolicy.completedSide(cue.id, window)
            if (!AngleCuePolicy.isSupported(cue.id, view, frame, side)) return@mapNotNull null
            val measured = analyzer.measure(cue, window)?.takeIf { it.isFinite() } ?: return@mapNotNull null
            val score = cue.score(measured) ?: return@mapNotNull null
            if (score <= limit(cue.id)) Triple(cue, measured, score) else null
        }.minByOrNull { it.third } ?: return MovementVerdict.Counted
        return MovementVerdict.NotCounted(MovementFault.PostureOff, worst.first.id, worst.second)
    }

    private fun limit(id: CueId): Float = when (id) {
        CueId.PassPlatformHeight -> PLATFORM_LIMIT
        // Coached, never a refusal: side-on only, not validated on correct
        // spikes, and it would have refused 2 of the 8 it measured.
        CueId.SpikeElbowHeight -> NEVER
        else -> CLEARLY_OUT
    }

    private companion object {
        /** A score this low is outside the cue's whole tolerance. */
        const val CLEARLY_OUT = 0.001f

        /** Arms about 15 degrees below horizontal: a platform held up at the shoulders. */
        const val PLATFORM_LIMIT = 0.35f

        const val NEVER = -1f
    }
}
