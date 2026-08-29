package com.volleyballlab.player.pose.quality

import androidx.annotation.StringRes
import com.volleyballlab.player.R

/**
 * A specific, actionable reason the current framing is not good enough to record.
 *
 * Each issue maps to one instruction the athlete can follow immediately. There is
 * deliberately no generic "poor quality" case: telling someone their framing is
 * bad without telling them what to change is not feedback, it is just a blocker.
 *
 * [priority] orders the issues when several are true at once, lowest first. Only
 * the top one is shown. Position problems outrank tracking problems because
 * stepping into frame usually fixes the tracking problem too, and a stack of
 * three simultaneous complaints reads as a broken app.
 */
enum class PoseQualityIssue(
    val priority: Int,
    @param:StringRes val message: Int,
) {
    NoPersonDetected(0, R.string.quality_no_person),
    PartialBody(1, R.string.quality_partial_body),
    TooClose(2, R.string.quality_too_close),
    TooFar(3, R.string.quality_too_far),
    OffCentre(4, R.string.quality_off_centre),
    LowConfidence(5, R.string.quality_low_confidence),
    Unstable(6, R.string.quality_unstable),
}

/**
 * The verdict on a single frame's framing.
 *
 * @param issues every problem found, so the UI can highlight all of them on the
 *   framing diagram while surfacing only [primaryIssue] as text.
 * @param bodyCoverage fraction of the frame height the athlete spans, `0f..1f`.
 *   Exposed for the framing meter, which shows the athlete how close they are to
 *   the acceptable band rather than just failing them.
 */
data class PoseQuality(
    val issues: Set<PoseQualityIssue>,
    val bodyCoverage: Float,
    val meanVisibility: Float,
) {
    val isAcceptable: Boolean get() = issues.isEmpty()

    /** The single issue worth telling the athlete about right now. */
    val primaryIssue: PoseQualityIssue? get() = issues.minByOrNull { it.priority }

    companion object {
        /** Used before the first frame arrives. */
        val NotYetKnown = PoseQuality(
            issues = setOf(PoseQualityIssue.NoPersonDetected),
            bodyCoverage = 0f,
            meanVisibility = 0f,
        )
    }
}
