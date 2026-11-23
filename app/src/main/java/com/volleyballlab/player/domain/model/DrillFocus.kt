package com.volleyballlab.player.domain.model

/**
 * The checks each starter drill trains, most important first, taken from what
 * the drill's own instructions ask for.
 *
 * Every drill of a skill is measured with the same checks; this only decides
 * which of them the coach talks about first. When a focus check is off, it is
 * the correction the live coach speaks and the report's "practice next", ahead
 * of a slightly worse check the drill is not about. A focus check that is fine
 * is never forced into a correction.
 *
 * Drill ids are the stable ones from the starter catalogue; an unknown id (a
 * coach-authored drill, say) has no focus and behaves exactly as before.
 */
object DrillFocus {

    fun cues(drillId: String): Set<CueId> = byDrill[drillId].orEmpty()

    /**
     * Checks that do not apply to a drill at all: never measured, scored,
     * coached or a reason to refuse a rep.
     *
     * The basic spike is done with both feet on the floor, so the deep knee load
     * of a jump (80 to 115 degrees) is not part of it. It used to be scored on
     * every spike, which marked a correct standing swing wrong every time and in
     * effect asked a beginner to jump. The jump starts at the intermediate drill.
     */
    fun excluded(drillId: String?): Set<CueId> = notTrained[drillId].orEmpty()

    /** Whether a spiking drill is done without leaving the floor. */
    fun isGrounded(drillId: String?): Boolean = CueId.SpikeApproachLoad in excluded(drillId)

    private val notTrained: Map<String, Set<CueId>> = mapOf(
        "spike-standing-swing" to setOf(CueId.SpikeApproachLoad, CueId.SpikeJumpHeight),
    )

    private val byDrill: Map<String, Set<CueId>> = mapOf(
        // Serving
        "serve-standing-float" to linkedSetOf(CueId.ServeTossArmLift, CueId.ServeTrunkLean),
        "serve-jump-float" to linkedSetOf(CueId.ServeReachHeight, CueId.ServeContactArmExtension),
        "serve-topspin-jump" to linkedSetOf(CueId.ServeTrunkLean, CueId.ServeContactArmExtension),
        // Receiving
        "receive-wall-platform" to linkedSetOf(CueId.PassPlatformStraight, CueId.PassPlatformHeight, CueId.PassShouldersLevel),
        "receive-short-court" to linkedSetOf(CueId.PassKneeFlexion, CueId.PassTrunkAngle),
        "receive-serve-to-target" to linkedSetOf(CueId.PassShouldersLevel, CueId.PassPlatformHeight, CueId.PassTrunkAngle),
        // Setting
        "set-wall-reps" to linkedSetOf(CueId.SetHandsAboveForehead, CueId.SetElbowFlexion, CueId.SetKneeFlexion),
        "set-toss-to-target" to linkedSetOf(CueId.SetExtensionFollowThrough, CueId.SetKneeFlexion),
        "set-back-set" to linkedSetOf(CueId.SetHandsAboveForehead, CueId.SetExtensionFollowThrough),
        // Spiking
        "spike-standing-swing" to linkedSetOf(CueId.SpikeElbowHeight, CueId.SpikeArmExtension),
        "spike-three-step" to linkedSetOf(CueId.SpikeArmExtension, CueId.SpikeApproachLoad),
        "spike-four-step-lines" to linkedSetOf(CueId.SpikeTrunkDrive, CueId.SpikeElbowHeight),
        // Blocking
        "block-ready-jumps" to linkedSetOf(CueId.BlockKneeLoad, CueId.BlockArmExtension),
        "block-lateral-shuffle" to linkedSetOf(CueId.BlockHandSeparation, CueId.BlockArmExtension),
        "block-read-setter" to linkedSetOf(CueId.BlockPenetration, CueId.BlockKneeLoad),
    )
}
