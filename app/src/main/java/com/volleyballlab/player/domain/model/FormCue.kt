package com.volleyballlab.player.domain.model

import androidx.annotation.StringRes
import com.volleyballlab.player.R

/**
 * The phase of a repetition at which a cue is measured.
 *
 * Every cue is measured at exactly one phase. This matters more than it looks:
 * elbow extension during the loading phase of a spike *should* be low, and the
 * same measurement at contact *should* be high. A cue without a phase would
 * average the two and score every rep as mediocre regardless of technique.
 */
enum class RepPhase {
    /** Athlete is set and about to begin. The reference pose for the rep. */
    Preparation,

    /** Coiling, crouching, drawing back. The extremum of the loading signal. */
    Loading,

    /** The instant of ball contact, or its pose proxy when no ball is tracked. */
    Contact,

    /** Deceleration after contact. */
    FollowThrough,
}

/** The unit a cue's target range is expressed in, used only for formatting. */
enum class CueUnit {
    Degrees,
    Metres,
    /** A dimensionless 0..1 value, displayed as a percentage. */
    Ratio,
}

/**
 * Stable identifiers for every coaching cue.
 *
 * These are persisted with each session and will become document keys once the
 * Firebase schema is settled, so treat them as a wire format: renaming an entry
 * orphans historical sessions. Add new ones, do not repurpose old ones.
 */
enum class CueId {
    // Serving
    ServeContactArmExtension,
    ServeReachHeight,
    ServeTrunkLean,
    ServeTossArmLift,

    // Receiving (forearm passing)
    PassPlatformStraight,
    PassKneeFlexion,
    PassTrunkAngle,
    PassShouldersLevel,

    // Setting
    SetHandsAboveForehead,
    SetElbowFlexion,
    SetKneeFlexion,
    SetExtensionFollowThrough,

    // Spiking
    SpikeApproachLoad,
    SpikeArmExtension,
    SpikeJumpHeight,
    SpikeTrunkDrive,

    // Blocking
    BlockArmExtension,
    BlockHandSeparation,
    BlockPenetration,
    BlockKneeLoad,
}

/**
 * One measurable element of technique.
 *
 * A cue owns the *target*; the corresponding
 * [com.volleyballlab.player.pose.analysis.SkillAnalyzer] owns the *measurement*.
 * Splitting them this way means a coach can retune a threshold after the
 * validation interviews without anyone touching geometry code.
 *
 * @param target the range that scores full marks, in [unit].
 * @param warnTolerance how far outside [target] still counts as a near miss. A
 *   measurement inside target scores 1.0, one beyond target plus this tolerance
 *   scores 0.0, and anything between falls off linearly. Without a graded band a
 *   cue would be pass/fail, and a rep one degree off would look identical to a
 *   rep forty degrees off.
 * @param weight this cue's share of the rep score. Weights within a skill sum to
 *   1.0, which [FormCueCatalog] verifies at class-load time.
 */
data class FormCue(
    val id: CueId,
    val phase: RepPhase,
    val unit: CueUnit,
    val target: ClosedFloatingPointRange<Float>,
    val warnTolerance: Float,
    val weight: Float,
    @param:StringRes val label: Int,
    @param:StringRes val coachingTip: Int,
) {
    /**
     * Scores a measurement in `0f..1f`.
     *
     * Returns `null` for a null measurement rather than 0. A cue that could not
     * be measured - an occluded joint, a side-on view - is not a failed cue, and
     * scoring it as zero would punish the athlete for the camera angle.
     */
    fun score(measured: Float?): Float? {
        if (measured == null) return null
        if (measured in target) return 1f
        val distance = if (measured < target.start) {
            target.start - measured
        } else {
            measured - target.endInclusive
        }
        if (warnTolerance <= 0f) return 0f
        return (1f - distance / warnTolerance).coerceIn(0f, 1f)
    }
}

/**
 * The cue set for each skill.
 *
 * Thresholds are first-pass values drawn from general coaching guidance, not from
 * measured data. Experiment 2 in the capstone document - the ten coach interviews
 * still marked Pending - is what should replace them. They are gathered here, in
 * one file, precisely so that replacing them is a contained edit.
 */
object FormCueCatalog {

    private val serving = listOf(
        FormCue(
            id = CueId.ServeContactArmExtension,
            phase = RepPhase.Contact,
            unit = CueUnit.Degrees,
            target = 155f..180f,
            warnTolerance = 30f,
            weight = 0.35f,
            label = R.string.cue_serve_arm_extension,
            coachingTip = R.string.cue_serve_arm_extension_tip,
        ),
        FormCue(
            id = CueId.ServeReachHeight,
            phase = RepPhase.Contact,
            unit = CueUnit.Degrees,
            target = 140f..180f,
            warnTolerance = 35f,
            weight = 0.30f,
            label = R.string.cue_serve_reach,
            coachingTip = R.string.cue_serve_reach_tip,
        ),
        FormCue(
            id = CueId.ServeTrunkLean,
            phase = RepPhase.Contact,
            unit = CueUnit.Degrees,
            target = -10f..15f,
            warnTolerance = 20f,
            weight = 0.20f,
            label = R.string.cue_serve_trunk,
            coachingTip = R.string.cue_serve_trunk_tip,
        ),
        FormCue(
            id = CueId.ServeTossArmLift,
            phase = RepPhase.Preparation,
            unit = CueUnit.Degrees,
            target = 90f..160f,
            warnTolerance = 35f,
            weight = 0.15f,
            label = R.string.cue_serve_toss_arm,
            coachingTip = R.string.cue_serve_toss_arm_tip,
        ),
    )

    private val receiving = listOf(
        FormCue(
            id = CueId.PassPlatformStraight,
            phase = RepPhase.Contact,
            unit = CueUnit.Degrees,
            target = 165f..180f,
            warnTolerance = 25f,
            weight = 0.35f,
            label = R.string.cue_pass_platform,
            coachingTip = R.string.cue_pass_platform_tip,
        ),
        FormCue(
            id = CueId.PassKneeFlexion,
            phase = RepPhase.Contact,
            unit = CueUnit.Degrees,
            target = 110f..150f,
            warnTolerance = 30f,
            weight = 0.30f,
            label = R.string.cue_pass_knees,
            coachingTip = R.string.cue_pass_knees_tip,
        ),
        FormCue(
            id = CueId.PassTrunkAngle,
            phase = RepPhase.Contact,
            unit = CueUnit.Degrees,
            target = 15f..40f,
            warnTolerance = 20f,
            weight = 0.20f,
            label = R.string.cue_pass_trunk,
            coachingTip = R.string.cue_pass_trunk_tip,
        ),
        FormCue(
            id = CueId.PassShouldersLevel,
            phase = RepPhase.Contact,
            unit = CueUnit.Degrees,
            target = 0f..12f,
            warnTolerance = 15f,
            weight = 0.15f,
            label = R.string.cue_pass_shoulders,
            coachingTip = R.string.cue_pass_shoulders_tip,
        ),
    )

    private val setting = listOf(
        FormCue(
            id = CueId.SetHandsAboveForehead,
            phase = RepPhase.Contact,
            unit = CueUnit.Metres,
            target = 0.02f..0.40f,
            warnTolerance = 0.15f,
            weight = 0.35f,
            label = R.string.cue_set_hand_height,
            coachingTip = R.string.cue_set_hand_height_tip,
        ),
        FormCue(
            id = CueId.SetElbowFlexion,
            phase = RepPhase.Contact,
            unit = CueUnit.Degrees,
            target = 80f..120f,
            warnTolerance = 30f,
            weight = 0.25f,
            label = R.string.cue_set_elbows,
            coachingTip = R.string.cue_set_elbows_tip,
        ),
        FormCue(
            id = CueId.SetKneeFlexion,
            phase = RepPhase.Loading,
            unit = CueUnit.Degrees,
            target = 130f..165f,
            warnTolerance = 30f,
            weight = 0.20f,
            label = R.string.cue_set_knees,
            coachingTip = R.string.cue_set_knees_tip,
        ),
        FormCue(
            id = CueId.SetExtensionFollowThrough,
            phase = RepPhase.FollowThrough,
            unit = CueUnit.Degrees,
            target = 155f..180f,
            warnTolerance = 35f,
            weight = 0.20f,
            label = R.string.cue_set_follow,
            coachingTip = R.string.cue_set_follow_tip,
        ),
    )

    private val spiking = listOf(
        FormCue(
            id = CueId.SpikeApproachLoad,
            phase = RepPhase.Loading,
            unit = CueUnit.Degrees,
            target = 80f..115f,
            warnTolerance = 35f,
            weight = 0.25f,
            label = R.string.cue_spike_load,
            coachingTip = R.string.cue_spike_load_tip,
        ),
        FormCue(
            id = CueId.SpikeArmExtension,
            phase = RepPhase.Contact,
            unit = CueUnit.Degrees,
            target = 155f..180f,
            warnTolerance = 30f,
            weight = 0.30f,
            label = R.string.cue_spike_arm,
            coachingTip = R.string.cue_spike_arm_tip,
        ),
        FormCue(
            id = CueId.SpikeJumpHeight,
            phase = RepPhase.Contact,
            unit = CueUnit.Metres,
            target = 0.30f..1.00f,
            warnTolerance = 0.20f,
            weight = 0.25f,
            label = R.string.cue_spike_jump,
            coachingTip = R.string.cue_spike_jump_tip,
        ),
        FormCue(
            id = CueId.SpikeTrunkDrive,
            phase = RepPhase.Contact,
            unit = CueUnit.Degrees,
            target = -5f..25f,
            warnTolerance = 25f,
            weight = 0.20f,
            label = R.string.cue_spike_trunk,
            coachingTip = R.string.cue_spike_trunk_tip,
        ),
    )

    private val blocking = listOf(
        FormCue(
            id = CueId.BlockArmExtension,
            phase = RepPhase.Contact,
            unit = CueUnit.Degrees,
            target = 160f..180f,
            warnTolerance = 30f,
            weight = 0.30f,
            label = R.string.cue_block_arms,
            coachingTip = R.string.cue_block_arms_tip,
        ),
        FormCue(
            id = CueId.BlockHandSeparation,
            phase = RepPhase.Contact,
            unit = CueUnit.Metres,
            target = 0.15f..0.45f,
            warnTolerance = 0.20f,
            weight = 0.25f,
            label = R.string.cue_block_hands,
            coachingTip = R.string.cue_block_hands_tip,
        ),
        FormCue(
            id = CueId.BlockPenetration,
            phase = RepPhase.Contact,
            unit = CueUnit.Metres,
            target = 0.05f..0.35f,
            warnTolerance = 0.15f,
            weight = 0.25f,
            label = R.string.cue_block_penetration,
            coachingTip = R.string.cue_block_penetration_tip,
        ),
        FormCue(
            id = CueId.BlockKneeLoad,
            phase = RepPhase.Loading,
            unit = CueUnit.Degrees,
            target = 90f..125f,
            warnTolerance = 35f,
            weight = 0.20f,
            label = R.string.cue_block_knees,
            coachingTip = R.string.cue_block_knees_tip,
        ),
    )

    private val bySkill: Map<Skill, List<FormCue>> = mapOf(
        Skill.Serving to serving,
        Skill.Receiving to receiving,
        Skill.Setting to setting,
        Skill.Spiking to spiking,
        Skill.Blocking to blocking,
    )

    init {
        // Catches a mistyped weight the moment the class loads rather than as a
        // quietly skewed score three screens later. Every skill must be covered,
        // and its weights must form a proper weighted average.
        val missing = Skill.All - bySkill.keys
        check(missing.isEmpty()) { "No cue set defined for: $missing" }
        bySkill.forEach { (skill, cues) ->
            val total = cues.sumOf { it.weight.toDouble() }
            check(kotlin.math.abs(total - 1.0) < 1e-3) {
                "Cue weights for $skill sum to $total, expected 1.0"
            }
            val duplicates = cues.groupBy { it.id }.filterValues { it.size > 1 }.keys
            check(duplicates.isEmpty()) { "Duplicate cues for $skill: $duplicates" }
        }
    }

    fun forSkill(skill: Skill): List<FormCue> = bySkill.getValue(skill)

    fun byId(id: CueId): FormCue? = bySkill.values.flatten().firstOrNull { it.id == id }
}
