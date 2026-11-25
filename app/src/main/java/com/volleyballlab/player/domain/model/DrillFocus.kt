package com.volleyballlab.player.domain.model

/**
 * The checks each starter drill trains, most important first, taken from what
 * the drill's own instructions ask for.
 *
 * Each drill can exclude checks it does not train. The focus checks decide
 * which corrections the coach talks about first. When a focus check is off, it is
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
     * The spiking drills keep both feet planted. Standing Spike focuses on
     * the arm swing. The other two drills also train the knee load.
     */
    fun excluded(drillId: String?): Set<CueId> = notTrained[drillId].orEmpty()

    private val notTrained: Map<String, Set<CueId>> = mapOf(
        "spike-standing-swing" to setOf(CueId.SpikeApproachLoad, CueId.SpikeJumpHeight),
        "spike-three-step" to setOf(CueId.SpikeJumpHeight),
        "spike-four-step-lines" to setOf(CueId.SpikeJumpHeight),
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
        "spike-three-step" to linkedSetOf(CueId.SpikeApproachLoad, CueId.SpikeArmExtension),
        "spike-four-step-lines" to linkedSetOf(CueId.SpikeTrunkDrive, CueId.SpikeElbowHeight, CueId.SpikeApproachLoad),
        // Blocking
        "block-ready-jumps" to linkedSetOf(CueId.BlockKneeLoad, CueId.BlockArmExtension),
        "block-lateral-shuffle" to linkedSetOf(CueId.BlockHandSeparation, CueId.BlockArmExtension),
        "block-read-setter" to linkedSetOf(CueId.BlockPenetration, CueId.BlockKneeLoad),
    )
}
