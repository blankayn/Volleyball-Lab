package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.FormCue

/**
 * What a cue measurement means, in words a player reads at a glance, never a number.
 *
 * ## Why words, not degrees
 *
 * "Your elbow was 142°, target 155–180°" asks the athlete to do arithmetic about a
 * joint they cannot see. "Arm extension: a bit bent" tells them what to change. The
 * spoken coach ([CoachPhrasebook]) already avoids numbers; the player's screens follow
 * it here, with the same directions (below the practice range reads the same way it
 * is spoken). The exact values are unchanged in the session data, and the coach's
 * replay still shows them.
 *
 * ## The gauge
 *
 * [Reading.position], [Reading.rangeStart] and [Reading.rangeEnd] place the
 * measurement and the practice range on a 0..1 bar spanning the range widened by
 * the cue's warn tolerance on each side. The bar shows *where* the athlete is
 * relative to the range without printing a value.
 */
object CuePlainWords {

    enum class Zone { Below, InRange, Above }

    data class Reading(
        val name: String,
        val verdict: String,
        val zone: Zone,
        /** Where the measurement sits on the gauge, 0..1. */
        val position: Float,
        /** Where the practice range begins on the gauge, 0..1. */
        val rangeStart: Float,
        /** Where the practice range ends on the gauge, 0..1. */
        val rangeEnd: Float,
    ) {
        val inRange: Boolean get() = zone == Zone.InRange

        /** "Arm extension: a bit bent" */
        val line: String get() = "$name: $verdict"
    }

    fun zone(cue: FormCue, measured: Float): Zone = when {
        measured < cue.target.start -> Zone.Below
        measured > cue.target.endInclusive -> Zone.Above
        else -> Zone.InRange
    }

    fun read(cue: FormCue, measured: Float): Reading {
        val low = cue.target.start - cue.warnTolerance
        val high = cue.target.endInclusive + cue.warnTolerance
        fun onGauge(value: Float) = ((value - low) / (high - low)).coerceIn(0f, 1f)
        val zone = zone(cue, measured)
        return Reading(
            name = name(cue.id),
            verdict = verdict(cue.id, zone),
            zone = zone,
            position = onGauge(measured),
            rangeStart = onGauge(cue.target.start),
            rangeEnd = onGauge(cue.target.endInclusive),
        )
    }

    /** The short name shown above the gauge. */
    fun name(id: CueId): String = when (id) {
        CueId.PassKneeFlexion, CueId.SetKneeFlexion, CueId.SpikeApproachLoad, CueId.BlockKneeLoad -> "Knee bend"
        CueId.PassPlatformStraight -> "Forearm platform"
        CueId.PassShouldersLevel -> "Shoulders"
        CueId.PassTrunkAngle, CueId.ServeTrunkLean, CueId.SpikeTrunkDrive -> "Trunk"
        CueId.SetHandsAboveForehead -> "Hand height"
        CueId.SetElbowFlexion -> "Elbow bend"
        CueId.SetExtensionFollowThrough -> "Arm finish"
        CueId.SpikeElbowHeight -> "Elbow height"
        CueId.ServeContactArmExtension, CueId.SpikeArmExtension, CueId.BlockArmExtension -> "Arm extension"
        CueId.ServeReachHeight -> "Overhead reach"
        CueId.ServeTossArmLift -> "Guiding arm"
        CueId.SpikeJumpHeight -> "Jump height"
        CueId.BlockHandSeparation -> "Hand spacing"
        CueId.BlockPenetration -> "Reach over the net"
    }

    /**
     * Direction wording, matching [CoachPhrasebook]: for the knee cues "below" is a
     * smaller knee angle, a deeper crouch; for the trunk cues it is less lean, more
     * upright. Where one side cannot happen (an arm straighter than straight, a
     * shoulder tilt under zero) it reads the same as in range.
     */
    fun verdict(id: CueId, zone: Zone): String = when (id) {
        CueId.PassKneeFlexion, CueId.SetKneeFlexion, CueId.SpikeApproachLoad, CueId.BlockKneeLoad ->
            pick(zone, "a bit deep", "good bend", "a bit straight")
        CueId.PassPlatformStraight -> pick(zone, "elbows bending", "straight", "straight")
        CueId.PassShouldersLevel -> pick(zone, "level", "level", "tilting")
        CueId.PassTrunkAngle, CueId.ServeTrunkLean, CueId.SpikeTrunkDrive ->
            pick(zone, "a bit upright", "good position", "leaning a bit far")
        CueId.SetHandsAboveForehead -> pick(zone, "a bit low", "above your forehead", "a bit far out")
        CueId.SetElbowFlexion -> pick(zone, "folded tight", "good bend", "a bit straight")
        CueId.SetExtensionFollowThrough -> pick(zone, "stopping short", "full finish", "full finish")
        CueId.ServeContactArmExtension, CueId.SpikeArmExtension, CueId.BlockArmExtension ->
            pick(zone, "a bit bent", "straight", "straight")
        CueId.ServeReachHeight -> pick(zone, "elbow a bit low", "reaching high", "reaching high")
        CueId.SpikeElbowHeight -> pick(zone, "elbow dropping", "elbow high", "elbow high")
        CueId.ServeTossArmLift -> pick(zone, "a bit low", "good height", "a bit high")
        CueId.SpikeJumpHeight -> pick(zone, "a bit low", "good height", "great height")
        CueId.BlockHandSeparation -> pick(zone, "a bit close", "good spacing", "a bit wide")
        CueId.BlockPenetration -> pick(zone, "staying back", "good reach", "reaching far")
    }

    private fun pick(zone: Zone, below: String, inRange: String, above: String) = when (zone) {
        Zone.Below -> below
        Zone.InRange -> inRange
        Zone.Above -> above
    }
}
