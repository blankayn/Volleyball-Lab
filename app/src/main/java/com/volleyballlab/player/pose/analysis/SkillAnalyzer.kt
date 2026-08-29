package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.FormCue
import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame

/** Which side of the body a measurement refers to. */
enum class Side { Left, Right }

/**
 * One detected repetition, with the frames that matter already picked out.
 *
 * [RepSegmenter] builds these; analyzers consume them. Splitting it this way
 * means an analyzer never has to reason about where a rep starts, and the
 * segmenter never has to know what a serve is.
 *
 * @param phaseFrames the single representative frame for each phase. Cues are
 *   measured on one frame rather than averaged across the phase on purpose:
 *   elbow extension "at contact" means at the instant of contact, and averaging
 *   the surrounding frames would drag the value toward the mid-swing posture and
 *   systematically under-report good technique.
 * @param baselineHipImageY the athlete's hip height, in normalised image units,
 *   during this rep's preparation phase. The reference for jump height.
 */
data class RepWindow(
    val index: Int,
    val frames: List<PoseFrame>,
    val phaseFrames: Map<RepPhase, PoseFrame>,
    val baselineHipImageY: Float,
) {
    val startMs: Long get() = frames.first().timestampMs
    val endMs: Long get() = frames.last().timestampMs
    val contactMs: Long get() = phaseFrames[RepPhase.Contact]?.timestampMs ?: startMs

    fun frameFor(phase: RepPhase): PoseFrame? = phaseFrames[phase]
}

/**
 * Skill-specific measurement logic.
 *
 * There is one implementation per [Skill], and the compiler enforces that via
 * [SkillAnalyzers]. Two responsibilities, deliberately kept separate:
 *
 * 1. [segmentationSignal] - a single scalar per frame that peaks at the moment of
 *    contact. This is what turns a continuous video into countable repetitions.
 * 2. [measure] - given a segmented rep, produce the raw value for one cue.
 *
 * Scoring is not here. Turning a measurement into a 0..1 score is
 * [FormCue.score]'s job, so thresholds can be retuned after the coach interviews
 * without touching a line of geometry.
 */
interface SkillAnalyzer {

    val skill: Skill

    /**
     * A scalar that rises through the action and peaks at contact.
     *
     * The units differ per skill and are never compared across skills - the
     * segmenter only ever looks at this signal's own shape, normalising against
     * its observed range within a single session. Return null when the frame
     * cannot produce a value, which the segmenter treats as a gap rather than a zero.
     */
    fun segmentationSignal(frame: PoseFrame): Float?

    /**
     * The raw measurement for [cue] on [window], in the cue's own unit, or null
     * if it could not be measured on this rep.
     *
     * Returning null is not a failure path. An occluded joint or a side-on camera
     * angle makes some cues genuinely unmeasurable, and saying so is far more
     * useful than inventing a number. [com.volleyballlab.player.domain.model.RepResult]
     * renormalises the remaining weights around it.
     */
    fun measure(cue: FormCue, window: RepWindow): Float?
}

/**
 * Geometry shared across analyzers.
 *
 * These are the measurements more than one skill needs. Anything used by exactly
 * one skill stays in that skill's analyzer, where its rationale lives next to it.
 */
internal object AnalyzerSupport {

    /**
     * The arm the athlete is hitting with, inferred from whichever wrist is
     * higher at contact.
     *
     * Inferred per rep rather than stored on a profile because handedness is not
     * captured anywhere in the app yet, and because a drill can legitimately ask
     * an athlete to work their off hand. Image y increases downward, so the
     * higher wrist has the smaller y.
     */
    fun hittingSide(frame: PoseFrame): Side {
        val left = frame.image(BodyPoint.LEFT_WRIST)
        val right = frame.image(BodyPoint.RIGHT_WRIST)
        return if (left.y <= right.y) Side.Left else Side.Right
    }

    fun shoulder(side: Side) =
        if (side == Side.Left) BodyPoint.LEFT_SHOULDER else BodyPoint.RIGHT_SHOULDER

    fun elbow(side: Side) =
        if (side == Side.Left) BodyPoint.LEFT_ELBOW else BodyPoint.RIGHT_ELBOW

    fun wrist(side: Side) =
        if (side == Side.Left) BodyPoint.LEFT_WRIST else BodyPoint.RIGHT_WRIST

    fun hip(side: Side) =
        if (side == Side.Left) BodyPoint.LEFT_HIP else BodyPoint.RIGHT_HIP

    fun knee(side: Side) =
        if (side == Side.Left) BodyPoint.LEFT_KNEE else BodyPoint.RIGHT_KNEE

    fun ankle(side: Side) =
        if (side == Side.Left) BodyPoint.LEFT_ANKLE else BodyPoint.RIGHT_ANKLE

    /** Interior elbow angle: shoulder, elbow, wrist. 180 is a straight arm. */
    fun elbowAngle(frame: PoseFrame, side: Side): Float? =
        Angles.at(frame, shoulder(side), elbow(side), wrist(side))

    /**
     * Shoulder elevation: hip, shoulder, elbow. Larger means the arm is carried
     * higher relative to the trunk, which is what "reach" means for a serve.
     */
    fun shoulderAngle(frame: PoseFrame, side: Side): Float? =
        Angles.at(frame, hip(side), shoulder(side), elbow(side))

    /** Interior knee angle: hip, knee, ankle. 180 is a straight leg. */
    fun kneeAngle(frame: PoseFrame, side: Side): Float? =
        Angles.at(frame, hip(side), knee(side), ankle(side))

    /**
     * Mean of a measurement across both sides.
     *
     * Falls back to whichever single side is available rather than returning
     * null, because a self-filmed athlete very often has one limb partly out of
     * frame or occluded by their own body, and one good knee is a far better
     * estimate of squat depth than no answer at all.
     */
    fun bilateralMean(measure: (Side) -> Float?): Float? {
        val left = measure(Side.Left)
        val right = measure(Side.Right)
        return when {
            left != null && right != null -> (left + right) / 2f
            else -> left ?: right
        }
    }

    /** Mean knee angle across both legs. */
    fun meanKneeAngle(frame: PoseFrame): Float? =
        bilateralMean { side -> kneeAngle(frame, side) }

    /** Mean elbow angle across both arms. */
    fun meanElbowAngle(frame: PoseFrame): Float? =
        bilateralMean { side -> elbowAngle(frame, side) }

    /**
     * Height of the higher wrist above the shoulder line, in normalised image
     * units. Positive means above.
     *
     * Image space rather than world space because this feeds rep segmentation,
     * where only the shape of the curve matters, and image space is one fewer
     * conversion per frame on the hot path.
     */
    fun wristRiseAboveShoulders(frame: PoseFrame): Float {
        val shoulderY = Angles.shoulderCentreImage(frame).y
        val higherWristY = minOf(
            frame.image(BodyPoint.LEFT_WRIST).y,
            frame.image(BodyPoint.RIGHT_WRIST).y,
        )
        return shoulderY - higherWristY
    }

    /** Mean wrist height above the shoulder line, for two-handed actions. */
    fun meanWristRiseAboveShoulders(frame: PoseFrame): Float {
        val shoulderY = Angles.shoulderCentreImage(frame).y
        val meanWristY = (
            frame.image(BodyPoint.LEFT_WRIST).y + frame.image(BodyPoint.RIGHT_WRIST).y
            ) / 2f
        return shoulderY - meanWristY
    }
}
