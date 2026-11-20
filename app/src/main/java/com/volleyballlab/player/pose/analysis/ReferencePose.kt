package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The two positions a repetition is drawn between. */
enum class ReferenceMoment { Start, Finish }

/**
 * What picture goes with a coaching line.
 *
 * [Demonstrate] alternates the start and finish positions, which is how the
 * coach shows what one rep is. [Start] and [Finish] hold one of them.
 */
enum class ReferenceShow { Demonstrate, Start, Finish }

/**
 * One arm, as the direction of its two segments.
 *
 * Elevation runs from hanging straight down (0) through horizontal (90) to
 * straight up (180). Azimuth turns that direction around the body: 0 is
 * forward, 90 out to the side, 180 behind, and a negative value reaches across
 * toward the midline. Both segments share one azimuth, which makes the arm a
 * rigid turn of a forward-pointing arm and so leaves its elbow angle exact.
 */
data class ArmPose(
    val upperElevation: Float,
    val foreElevation: Float,
    val azimuth: Float = 0f,
) {
    /** Interior elbow angle. 180 is a straight arm. */
    val elbowAngle: Float get() = 180f - abs(foreElevation - upperElevation)

    companion object {
        /**
         * The upper arm lifted to [raise] with the elbow closed to [elbow], the
         * forearm folding upward. This is the shape the cue catalog describes:
         * [raise] is the hip-shoulder-elbow angle and [elbow] the elbow's own.
         */
        fun reaching(raise: Float, elbow: Float, azimuth: Float = 0f) =
            ArmPose(raise, raise + (180f - elbow), azimuth)
    }
}

/**
 * A whole-body position, by joint angle.
 *
 * @param knee interior knee angle. 180 is a straight leg.
 * @param trunkLean forward lean of the trunk from vertical, in degrees.
 * @param handGap metres between the wrists when the hands work as a pair. The
 *   arms are turned inward or outward to meet it. Null leaves each arm where its
 *   own angles put it.
 */
data class ReferencePose(
    val left: ArmPose,
    val right: ArmPose,
    val knee: Float,
    val trunkLean: Float = 0f,
    val handGap: Float? = null,
)

/**
 * Segment lengths, in metres. The reference figure is built from the athlete's
 * own, so it is their body in the reference position rather than a stock
 * figure laid over them.
 */
data class BodyProportions(
    val torso: Float,
    val shoulderHalf: Float,
    val hipHalf: Float,
    val upperArm: Float,
    val forearm: Float,
    val thigh: Float,
    val shin: Float,
) {
    fun blend(to: BodyProportions, t: Float) = BodyProportions(
        mix(torso, to.torso, t), mix(shoulderHalf, to.shoulderHalf, t), mix(hipHalf, to.hipHalf, t),
        mix(upperArm, to.upperArm, t), mix(forearm, to.forearm, t), mix(thigh, to.thigh, t), mix(shin, to.shin, t),
    )

    companion object {
        /** A rough adult. Stands in only for limbs the camera did not see. */
        val Typical = BodyProportions(
            torso = 0.48f, shoulderHalf = 0.18f, hipHalf = 0.10f,
            upperArm = 0.28f, forearm = 0.25f, thigh = 0.42f, shin = 0.40f,
        )

        /**
         * The athlete's proportions as this frame shows them, or null when the
         * torso itself was not seen. A limb that was hidden takes the typical
         * length for a torso of this size, and no limb is allowed to stray far
         * from that: a tracker that briefly loses a wrist reports an arm half as
         * long as it is, and the figure should not believe it.
         */
        fun observed(frame: PoseFrame): BodyProportions? {
            val torsoPoints = listOf(
                BodyPoint.LEFT_SHOULDER, BodyPoint.RIGHT_SHOULDER, BodyPoint.LEFT_HIP, BodyPoint.RIGHT_HIP,
            )
            if (torsoPoints.any { !AnalyzerSupport.observed(frame, it) }) return null
            val torso = Angles.distance(Angles.shoulderCentre(frame), Angles.hipCentre(frame))
            if (!torso.isFinite() || torso !in MIN_TORSO_METRES..MAX_TORSO_METRES) return null
            val scale = torso / Typical.torso

            fun length(typical: Float, vararg segments: Pair<BodyPoint, BodyPoint>): Float {
                val expected = typical * scale
                val seen = segments.filter { (a, b) -> AnalyzerSupport.observed(frame, a) && AnalyzerSupport.observed(frame, b) }
                    .map { (a, b) -> Angles.distance(frame.world(a), frame.world(b)) }
                    .filter { it.isFinite() }
                if (seen.isEmpty()) return expected
                return seen.average().toFloat().coerceIn(expected * MIN_LIMB_RATIO, expected * MAX_LIMB_RATIO)
            }
            return BodyProportions(
                torso = torso,
                shoulderHalf = length(Typical.shoulderHalf * 2f, BodyPoint.LEFT_SHOULDER to BodyPoint.RIGHT_SHOULDER) / 2f,
                hipHalf = length(Typical.hipHalf * 2f, BodyPoint.LEFT_HIP to BodyPoint.RIGHT_HIP) / 2f,
                upperArm = length(Typical.upperArm,
                    BodyPoint.LEFT_SHOULDER to BodyPoint.LEFT_ELBOW, BodyPoint.RIGHT_SHOULDER to BodyPoint.RIGHT_ELBOW),
                forearm = length(Typical.forearm,
                    BodyPoint.LEFT_ELBOW to BodyPoint.LEFT_WRIST, BodyPoint.RIGHT_ELBOW to BodyPoint.RIGHT_WRIST),
                thigh = length(Typical.thigh,
                    BodyPoint.LEFT_HIP to BodyPoint.LEFT_KNEE, BodyPoint.RIGHT_HIP to BodyPoint.RIGHT_KNEE),
                shin = length(Typical.shin,
                    BodyPoint.LEFT_KNEE to BodyPoint.LEFT_ANKLE, BodyPoint.RIGHT_KNEE to BodyPoint.RIGHT_ANKLE),
            )
        }

        private fun mix(from: Float, to: Float, t: Float) = from + (to - from) * t
        private const val MIN_TORSO_METRES = 0.25f
        private const val MAX_TORSO_METRES = 0.80f
        private const val MIN_LIMB_RATIO = 0.70f
        private const val MAX_LIMB_RATIO = 1.35f
    }
}

/**
 * The start and finish position of each skill.
 *
 * ## Where the angles come from
 *
 * Wherever a cue scores a joint, the angle here is read out of that cue's own
 * target in [FormCueCatalog]: the finish position is the pose the Contact-phase
 * cues score full marks on, and the start position satisfies the Preparation
 * and Loading cues. Nothing is typed in twice, so retuning a target after the
 * coach interviews moves the picture with it. `ReferencePoseTest` holds this to
 * account by scoring these poses with the real analyzers, and by running a
 * start-to-finish movement through the same movement check the rep counters use.
 *
 * Angles with no cue behind them - where the idle arm hangs, how soft the knees
 * are at the finish - are plain choices and are marked as such.
 *
 * ## What this is not
 *
 * It is not a model of ideal technique. The targets are first-pass values that
 * still await coach validation, and this draws them exactly as they stand. It
 * shows where the app's own measurements want the body to be, which is what an
 * athlete needs in order to understand why a rep did or did not count.
 */
object ReferencePoses {

    fun pose(
        skill: Skill,
        moment: ReferenceMoment,
        hitting: Side = Side.Right,
        body: BodyProportions = BodyProportions.Typical,
    ): ReferencePose = when (skill) {
        Skill.Serving -> when (moment) {
            // Guiding arm lifted in front, hitting elbow out at shoulder height
            // with the hand up beside the head.
            ReferenceMoment.Start -> oneArmed(
                hitting,
                hittingArm = ArmPose(upperElevation = 90f, foreElevation = 170f, azimuth = 90f),
                otherArm = ArmPose.reaching(within(CueId.ServeTossArmLift, 0.15f), elbow = 165f),
                knee = SOFT_KNEE, trunkLean = mid(CueId.ServeTrunkLean),
            )
            ReferenceMoment.Finish -> oneArmed(
                hitting,
                hittingArm = ArmPose.reaching(mid(CueId.ServeReachHeight), mid(CueId.ServeContactArmExtension)),
                otherArm = LOWERED_ARM,
                knee = TALL_KNEE, trunkLean = mid(CueId.ServeTrunkLean),
            )
        }

        Skill.Receiving -> when (moment) {
            // The platform is already made; the rep is the sit into the ball.
            ReferenceMoment.Start -> platform(
                belowHorizontal = 50f, knee = READY_KNEE, trunkLean = target(CueId.PassTrunkAngle).start,
            )
            ReferenceMoment.Finish -> platform(
                belowHorizontal = 40f, knee = mid(CueId.PassKneeFlexion), trunkLean = mid(CueId.PassTrunkAngle),
            )
        }

        Skill.Setting -> when (moment) {
            ReferenceMoment.Start -> both(
                ArmPose.reaching(raise = 35f, elbow = 70f),
                knee = mid(CueId.SetKneeFlexion), trunkLean = UPRIGHT, handGap = SET_HAND_GAP,
            )
            // Hands a little above the forehead, by the amount that cue asks for.
            ReferenceMoment.Finish -> {
                val elbow = mid(CueId.SetElbowFlexion)
                val handsAboveShoulders = ReferenceFigure.noseAboveShoulders(body) +
                    within(CueId.SetHandsAboveForehead, 0.3f)
                val raise = solveRaise(elbow) { arm -> wristRise(arm, body) - handsAboveShoulders }
                both(ArmPose.reaching(raise, elbow), knee = SOFT_KNEE, trunkLean = UPRIGHT, handGap = SET_HAND_GAP)
            }
        }

        Skill.Spiking -> when (moment) {
            // Loaded: knees bent, both arms swung back.
            ReferenceMoment.Start -> ReferencePose(
                left = ARMS_BACK, right = ARMS_BACK,
                knee = within(CueId.SpikeApproachLoad, 0.85f), trunkLean = LOADED_LEAN,
            )
            ReferenceMoment.Finish -> oneArmed(
                hitting,
                hittingArm = ArmPose.reaching(raise = OVERHEAD_REACH, elbow = mid(CueId.SpikeArmExtension)),
                otherArm = LOWERED_ARM,
                knee = TALL_KNEE, trunkLean = mid(CueId.SpikeTrunkDrive),
            )
        }

        Skill.Blocking -> when (moment) {
            ReferenceMoment.Start -> both(
                ArmPose.reaching(raise = 60f, elbow = 60f),
                knee = within(CueId.BlockKneeLoad, 0.85f), trunkLean = UPRIGHT,
            )
            // Reaching up and a little over, by the amount the penetration cue asks for.
            ReferenceMoment.Finish -> {
                val elbow = mid(CueId.BlockArmExtension)
                val forward = mid(CueId.BlockPenetration)
                val raise = solveRaise(elbow) { arm -> forward - wristForward(arm, body) }
                both(ArmPose.reaching(raise, elbow), knee = TALL_KNEE, trunkLean = 0f,
                    handGap = mid(CueId.BlockHandSeparation))
            }
        }
    }

    private fun target(id: CueId) = requireNotNull(FormCueCatalog.byId(id)) { "No cue $id" }.target

    /** The middle of a cue's full-marks range. */
    private fun mid(id: CueId): Float = within(id, 0.5f)

    /** A point part-way through a cue's full-marks range: 0 its start, 1 its end. */
    private fun within(id: CueId, fraction: Float): Float =
        target(id).let { it.start + (it.endInclusive - it.start) * fraction }

    private fun oneArmed(hitting: Side, hittingArm: ArmPose, otherArm: ArmPose, knee: Float, trunkLean: Float) =
        if (hitting == Side.Left) ReferencePose(hittingArm, otherArm, knee, trunkLean)
        else ReferencePose(otherArm, hittingArm, knee, trunkLean)

    private fun both(arm: ArmPose, knee: Float, trunkLean: Float, handGap: Float? = null) =
        ReferencePose(arm, arm, knee, trunkLean, handGap)

    /**
     * Straight arms joined in front, pointing [belowHorizontal] degrees down. An
     * arm's elevation is measured against the trunk, so the lean is added back
     * to keep the platform at the same angle to the floor.
     */
    private fun platform(belowHorizontal: Float, knee: Float, trunkLean: Float) = both(
        ArmPose.reaching(raise = 90f - belowHorizontal + trunkLean, elbow = mid(CueId.PassPlatformStraight)),
        knee = knee, trunkLean = trunkLean, handGap = HANDS_TOGETHER,
    )

    /** Height of the wrist above the shoulder, along the trunk, in metres. */
    private fun wristRise(arm: ArmPose, body: BodyProportions): Float =
        -(body.upperArm * cos(radians(arm.upperElevation)) + body.forearm * cos(radians(arm.foreElevation)))

    /** How far the wrist is in front of the shoulder, in metres. */
    private fun wristForward(arm: ArmPose, body: BodyProportions): Float =
        body.upperArm * sin(radians(arm.upperElevation)) + body.forearm * sin(radians(arm.foreElevation))

    /**
     * The overhead raise, between shoulder height and straight up, that brings
     * [error] closest to zero for an arm bent to [elbow].
     */
    private fun solveRaise(elbow: Float, error: (ArmPose) -> Float): Float =
        (90..178).map { it.toFloat() }.minBy { abs(error(ArmPose.reaching(it, elbow))) }

    private fun radians(degrees: Float) = Math.toRadians(degrees.toDouble()).toFloat()

    // Plain choices, with no cue behind them.
    private const val TALL_KNEE = 172f
    private const val SOFT_KNEE = 165f
    private const val READY_KNEE = 162f
    private const val UPRIGHT = 3f
    private const val LOADED_LEAN = 20f
    private const val OVERHEAD_REACH = 165f
    private const val HANDS_TOGETHER = 0.06f
    private const val SET_HAND_GAP = 0.20f
    private val LOWERED_ARM = ArmPose.reaching(raise = 25f, elbow = 150f)
    private val ARMS_BACK = ArmPose(upperElevation = 35f, foreElevation = 45f, azimuth = 180f)
}
