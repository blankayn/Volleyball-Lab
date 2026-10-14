package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame

/** Frozen v27 rule learned from the source-separated volleyball posture set. */
object ReceivingReadyPostureRule {
    const val KNEE_FLOOR_DEGREES = 145f
    const val KNEE_HIGH_DEGREES = 155f
    const val TRUNK_LOW_DEGREES = 10f
    const val MIN_LANDMARK_VISIBILITY = 0.40f

    enum class Issue { Knees, Trunk }

    fun issue(kneeDegrees: Float?, trunkForwardDegrees: Float?): Issue? {
        if (kneeDegrees == null || trunkForwardDegrees == null) return null
        return when {
            kneeDegrees > KNEE_HIGH_DEGREES -> Issue.Knees
            kneeDegrees > KNEE_FLOOR_DEGREES && trunkForwardDegrees < TRUNK_LOW_DEGREES ->
                Issue.Trunk
            else -> null
        }
    }

    /**
     * Returns a score override for the two coupled posture cues.
     * Other cue scores remain on their existing calibrated bands.
     */
    fun scoreOverride(
        cueId: CueId,
        kneeDegrees: Float?,
        trunkForwardDegrees: Float?,
    ): Float? {
        if (kneeDegrees == null || trunkForwardDegrees == null) return null
        val issue = issue(kneeDegrees, trunkForwardDegrees)
        return when (cueId) {
            CueId.PassKneeFlexion -> if (issue == Issue.Knees) 0f else 1f
            CueId.PassTrunkAngle -> if (issue == Issue.Trunk) 0f else 1f
            else -> null
        }
    }

    /** Refuse a receiving verdict when either knee or ankle is guessed or clipped. */
    fun hasReliableFullLegs(frame: PoseFrame): Boolean = listOf(
        BodyPoint.LEFT_KNEE,
        BodyPoint.RIGHT_KNEE,
        BodyPoint.LEFT_ANKLE,
        BodyPoint.RIGHT_ANKLE,
    ).all { point ->
        val landmark = frame.image(point)
        landmark.visibility >= MIN_LANDMARK_VISIBILITY &&
            landmark.x in 0f..1f && landmark.y in 0f..1f
    }
}
