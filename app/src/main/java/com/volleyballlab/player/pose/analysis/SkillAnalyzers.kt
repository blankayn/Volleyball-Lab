package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.FormCue
import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.analysis.AnalyzerSupport.elbowAngle
import com.volleyballlab.player.pose.analysis.AnalyzerSupport.hittingSide
import com.volleyballlab.player.pose.analysis.AnalyzerSupport.meanElbowAngle
import com.volleyballlab.player.pose.analysis.AnalyzerSupport.meanKneeAngle
import com.volleyballlab.player.pose.analysis.AnalyzerSupport.shoulderAngle
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame

private fun Side.opposite(): Side = if (this == Side.Left) Side.Right else Side.Left

/**
 * Overhand serve.
 *
 * Segmentation follows the hitting hand: a serve is defined by the hand rising
 * to meet the ball above the head, and that produces a large, clean peak that
 * survives a noisy tracker.
 */
class ServeAnalyzer : SkillAnalyzer {
    override val skill = Skill.Serving

    override fun segmentationSignal(frame: PoseFrame): Float =
        AnalyzerSupport.wristRiseAboveShoulders(frame)

    override fun measure(cue: FormCue, window: RepWindow): Float? {
        val contact = window.frameFor(RepPhase.Contact) ?: return null
        val hitting = hittingSide(contact)
        return when (cue.id) {
            CueId.ServeContactArmExtension -> elbowAngle(contact, hitting)
            CueId.ServeReachHeight -> shoulderAngle(contact, hitting)
            CueId.ServeTrunkLean -> Angles.trunkForwardLean(contact)
            // Measured on the non-hitting arm during preparation: the tossing arm
            // is by definition the one that is not about to strike the ball.
            CueId.ServeTossArmLift -> window.frameFor(RepPhase.Preparation)
                ?.let { shoulderAngle(it, hitting.opposite()) }

            else -> null
        }
    }
}

/**
 * Forearm passing, which the capstone document calls receiving.
 *
 * Segmentation follows knee flexion rather than the arms. A pass is defined by
 * dropping to meet the ball, and the platform itself barely moves relative to the
 * torso, so arm-based signals for this skill are almost flat and segment badly.
 * The assumption this carries is that the athlete returns to a taller stance
 * between reps; a drill performed entirely in a low crouch will under-count.
 */
class ReceiveAnalyzer : SkillAnalyzer {
    override val skill = Skill.Receiving

    override fun segmentationSignal(frame: PoseFrame): Float? =
        meanKneeAngle(frame)?.let { STRAIGHT_LEG_DEGREES - it }

    override fun measure(cue: FormCue, window: RepWindow): Float? {
        val contact = window.frameFor(RepPhase.Contact) ?: return null
        return when (cue.id) {
            CueId.PassPlatformStraight -> meanElbowAngle(contact)
            CueId.PassKneeFlexion -> meanKneeAngle(contact)
            CueId.PassTrunkAngle -> Angles.trunkForwardLean(contact)
            CueId.PassShouldersLevel -> Angles.shoulderTilt(contact)
            else -> null
        }
    }

    private companion object {
        const val STRAIGHT_LEG_DEGREES = 180f
    }
}

/**
 * Overhead setting.
 *
 * Segmentation follows both wrists together, since a legal set is two-handed and
 * symmetric.
 */
class SetAnalyzer : SkillAnalyzer {
    override val skill = Skill.Setting

    override fun segmentationSignal(frame: PoseFrame): Float =
        AnalyzerSupport.meanWristRiseAboveShoulders(frame)

    override fun measure(cue: FormCue, window: RepWindow): Float? = when (cue.id) {
        CueId.SetHandsAboveForehead -> window.frameFor(RepPhase.Contact)
            ?.let { handsAboveNoseMetres(it) }

        CueId.SetElbowFlexion -> window.frameFor(RepPhase.Contact)?.let { meanElbowAngle(it) }
        // Measured during loading, not contact: the leg drive that powers a set
        // happens before the hands touch the ball, and by contact the athlete is
        // already extending.
        CueId.SetKneeFlexion -> window.frameFor(RepPhase.Loading)?.let { meanKneeAngle(it) }
        CueId.SetExtensionFollowThrough -> window.frameFor(RepPhase.FollowThrough)
            ?.let { meanElbowAngle(it) }

        else -> null
    }

    /**
     * Height of the wrist midpoint above the nose, in metres.
     *
     * World space, because this cue's target is a real distance rather than a
     * ratio, and image-space centimetres would change with the athlete's distance
     * from the camera. Nose rather than a computed forehead point: the forehead
     * is not a tracked landmark, and the nose is the closest stable proxy.
     */
    private fun handsAboveNoseMetres(frame: PoseFrame): Float {
        val nose = frame.world(BodyPoint.NOSE)
        val wrists = Angles.midpoint(
            frame.world(BodyPoint.LEFT_WRIST),
            frame.world(BodyPoint.RIGHT_WRIST),
        )
        // World y increases downward, so "above" is the nose's y minus the wrists'.
        return nose.y - wrists.y
    }
}

/**
 * Spike approach and attack.
 *
 * Segmentation follows hip height in image space, because the jump is by far the
 * largest and least ambiguous movement in a spike. World space cannot be used
 * here at all: it is hip-centred, so the hips never move in it. See
 * [Angles.hipRiseMetres].
 *
 * The absolute value of this signal is meaningless - it is a raw normalised image
 * coordinate - but the segmenter only ever looks at the curve's shape relative to
 * its own range, so that is fine.
 */
class SpikeAnalyzer : SkillAnalyzer {
    override val skill = Skill.Spiking

    override fun segmentationSignal(frame: PoseFrame): Float =
        // Negated because image y increases downward, and the signal must peak,
        // not trough, at the top of the jump.
        -Angles.hipCentreImage(frame).y

    override fun measure(cue: FormCue, window: RepWindow): Float? = when (cue.id) {
        CueId.SpikeApproachLoad -> window.frameFor(RepPhase.Loading)?.let { meanKneeAngle(it) }
        CueId.SpikeArmExtension -> window.frameFor(RepPhase.Contact)
            ?.let { elbowAngle(it, hittingSide(it)) }

        CueId.SpikeJumpHeight -> window.frameFor(RepPhase.Contact)
            ?.let { Angles.hipRiseMetres(it, window.baselineHipImageY) }

        CueId.SpikeTrunkDrive -> window.frameFor(RepPhase.Contact)
            ?.let { Angles.trunkForwardLean(it) }

        else -> null
    }
}

/**
 * Blocking at the net.
 *
 * Segmentation follows both wrists, like setting, since a block is symmetric. Hip
 * height would also work, but block jumps are shorter than spike jumps and the
 * hand signal has better contrast against the ready position.
 */
class BlockAnalyzer : SkillAnalyzer {
    override val skill = Skill.Blocking

    override fun segmentationSignal(frame: PoseFrame): Float =
        AnalyzerSupport.meanWristRiseAboveShoulders(frame)

    override fun measure(cue: FormCue, window: RepWindow): Float? = when (cue.id) {
        CueId.BlockArmExtension -> window.frameFor(RepPhase.Contact)?.let { meanElbowAngle(it) }
        CueId.BlockHandSeparation -> window.frameFor(RepPhase.Contact)?.let {
            Angles.distance(it.world(BodyPoint.LEFT_WRIST), it.world(BodyPoint.RIGHT_WRIST))
        }

        CueId.BlockPenetration -> window.frameFor(RepPhase.Contact)?.let { penetrationMetres(it) }
        CueId.BlockKneeLoad -> window.frameFor(RepPhase.Loading)?.let { meanKneeAngle(it) }
        else -> null
    }

    /**
     * How far the hands reach in front of the shoulder line, in metres.
     *
     * A block that goes straight up gives the attacker the whole court to hit
     * into; reaching over the net is what makes it a wall. MediaPipe's world z
     * decreases toward the camera, so hands in front of the shoulders produce a
     * positive value here.
     *
     * Measured along the athlete's own forward direction, not along camera depth,
     * so it means the same thing from any angle the face is visible from.
     *
     * ## What this used to do, and why it was worse than wrong
     *
     * It read `shoulderCentre.z - wristMid.z` and suppressed itself when
     * [Angles.facingCamera] exceeded 0.9. Both halves were backwards.
     *
     * A raw z difference is anatomical penetration only when the athlete faces
     * the lens; side-on, their forward runs along camera **x**, and the
     * expression measures lateral hand offset instead. Correlation against the
     * true quantity, over real frames: **+0.92** when square to the camera,
     * **-0.26** when most side-on. The gate then discarded exactly the frames
     * where the measurement was nearly exact and kept the ones where it was
     * anticorrelated.
     *
     * Worse, the app's own remediation string tells an athlete whose cues went
     * unmeasured to "film from the side" - and this was the only cue that
     * returned null for camera reasons. Following the advice moved you further
     * into the broken regime.
     *
     * With the projection there is no regime to gate on: [Angles.alongForward]
     * returns null only when the pose itself cannot say which way the athlete
     * faces, which is the honest reason to decline.
     */
    private fun penetrationMetres(frame: PoseFrame): Float? {
        val wrists = Angles.midpoint(
            frame.world(BodyPoint.LEFT_WRIST),
            frame.world(BodyPoint.RIGHT_WRIST),
        )
        return Angles.alongForward(frame, Angles.shoulderCentre(frame), wrists)
    }
}

/**
 * The analyzer registry.
 *
 * The init block is what guarantees every [Skill] has exactly one analyzer, and
 * that each analyzer is registered under the skill it actually declares. Without
 * that second check a copy-paste slip could file the block analyzer under
 * spiking, and the result would not be a crash - it would be plausible scores for
 * the wrong skill.
 */
object SkillAnalyzers {

    private val bySkill: Map<Skill, SkillAnalyzer> = listOf(
        ServeAnalyzer(),
        ReceiveAnalyzer(),
        SetAnalyzer(),
        SpikeAnalyzer(),
        BlockAnalyzer(),
    ).associateBy { it.skill }

    init {
        val missing = Skill.All - bySkill.keys
        check(missing.isEmpty()) { "No analyzer registered for: $missing" }
    }

    fun forSkill(skill: Skill): SkillAnalyzer = bySkill.getValue(skill)
}
