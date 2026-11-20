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
 * Why a completed movement was not counted as a repetition.
 *
 * Every value names one thing the athlete can change, because the reason is
 * spoken and shown the moment the movement ends. A fault means the body was
 * observed doing something other than the skill. A joint that could not be seen
 * is never treated as a fault of technique: it is either [ArmsNotVisible] or
 * [LegsNotVisible], or the check that needed it is skipped.
 */
enum class MovementFault {
    /** Serve, spike: the hitting hand never rose above the head. */
    ReachOverhead,

    /** Set, block: one hand rose and the other stayed down. */
    UseBothHands,

    /** Set: both hands moved, but stayed below the face. */
    HandsAboveForehead,

    /** Set: straight arms were lifted; the elbows never bent to shape the hands. */
    BendElbowsFirst,

    /** Block: the hands stopped short of clearing the head. */
    ReachBothHandsHigh,

    /** Block: the hands went up but the arms stayed folded. */
    StraightenArms,

    /** Receive: the knees bent with no forearm platform in front of the body. */
    JoinPlatform,

    /** Receive: the joined hands were lifted above the shoulders. */
    PlatformTooHigh,

    /** The arm or arms that define this skill were not in view at contact. */
    ArmsNotVisible,

    /** Receive: neither leg was in view at the bottom of the bend. */
    LegsNotVisible,
}

/** The outcome of checking one completed movement against its skill. */
sealed interface MovementVerdict {

    /** The skill's movement, start to finish. Only this is counted and scored. */
    data object Counted : MovementVerdict

    /**
     * Too small to be an attempt at the skill: a shifted foot, a scratched nose,
     * tracker jitter. Dropped without comment, because commenting on movements
     * the athlete did not mean as a rep is how a coach becomes a nag.
     */
    data object TooSmall : MovementVerdict

    /** A real attempt that was not the skill. The athlete is told [fault]. */
    data class NotCounted(val fault: MovementFault) : MovementVerdict
}

/**
 * Skill-specific measurement logic.
 *
 * There is one implementation per [Skill], and the compiler enforces that via
 * [SkillAnalyzers]. Three responsibilities, deliberately kept separate:
 *
 * 1. [segmentationSignal] - a single scalar per frame that peaks at the moment of
 *    contact. This is what turns a continuous video into candidate movements.
 * 2. [verdict] - decides whether a candidate movement is this skill at all.
 * 3. [measure] - given a counted rep, produce the raw value for one cue.
 *
 * Scoring is not here. Turning a measurement into a 0..1 score is
 * [FormCue.score]'s job, so thresholds can be retuned after the coach interviews
 * without touching a line of geometry.
 *
 * ## Why [verdict] exists
 *
 * The segmenters only see the *shape* of one curve, rescaled to whatever range
 * it happened to cover. On its own that counts anything: with no absolute size
 * a few millimetres of tracker jitter is a full-height peak, and a knee bend is
 * indistinguishable from a forearm pass or a spike jump. Worse, the cue scores
 * agreed with it - arms hanging at the sides are straight, so they earned full
 * marks for a "straight platform". [minExcursion] gives the curve a real-world
 * size, and [verdict] checks the limbs the skill is actually performed with.
 */
interface SkillAnalyzer {

    val skill: Skill

    /**
     * The least rise of [segmentationSignal] above rest, in the signal's own
     * unit, that can be this skill's action. Both segmenters refuse to scale
     * their thresholds below it, so a motionless athlete has nothing to cross.
     *
     * Zero keeps the purely relative behaviour, which is only right for a signal
     * with no physical unit, as in a test double.
     */
    val minExcursion: Float get() = 0f

    /**
     * Whether a dip between two peaks of [segmentationSignal] means two reps, so
     * [RepSegmenter] may split pushes repeated from a raised ready position.
     *
     * True only for two-handed skills segmented on both wrists together, where
     * the hands rise and fall as one. A one-armed swing's signal follows whichever
     * hand is higher and dips between the toss or cocking arm and the hitting arm
     * inside a single serve or spike.
     */
    val repeatsFromRaisedHands: Boolean get() = false

    /**
     * The frame of deepest load among [frames], which run from the start of a
     * movement to its contact. Null uses the lowest point of the segmentation
     * signal, which is right only when that signal is the thing being loaded.
     *
     * Skills segmented on the hands override this: the lowest the hands get says
     * nothing about when the knees were bent deepest, and a knee-load cue read
     * off the wrong frame corrects an athlete who loaded properly.
     */
    fun loadingFrame(frames: List<PoseFrame>): PoseFrame? = null

    /**
     * The frame [measure] reads [cue] on, so a review can show and mark the
     * moment the number came from. Usually the frame of the cue's phase.
     *
     * A skill overrides it when its phase frames miss the moment a cue is about:
     * a set's contact is where the hands peak, after the push, when the elbows
     * the elbow-bend cue asks about have already straightened.
     */
    fun cueFrame(cue: FormCue, window: RepWindow): PoseFrame? = window.frameFor(cue.phase)

    /**
     * Whether [window] is one complete repetition of this skill.
     *
     * Checked on every candidate before it is counted, live and in the final
     * analysis alike, so the badge on the camera screen and the saved session
     * cannot disagree about what a rep is.
     */
    fun verdict(window: RepWindow): MovementVerdict = MovementVerdict.Counted

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

    // --- Body-relative heights, for deciding whether a movement is the skill ---

    /**
     * Shoulder-to-hip length in the image: the ruler every hand height below is
     * measured with.
     *
     * A fraction of the frame means nothing on its own - the same reach covers a
     * third of the picture from two metres and a sixth from four. Dividing by the
     * athlete's own torso turns it into "how many torso lengths", which is the
     * same number wherever they stand. Null when the torso has collapsed to a
     * point, which only happens when tracking has.
     */
    fun torsoImageLength(frame: PoseFrame): Float? =
        Angles.distance2D(Angles.shoulderCentreImage(frame), Angles.hipCentreImage(frame))
            .takeIf { it.isFinite() && it >= MIN_TORSO_IMAGE }

    /**
     * Height of one wrist above the shoulder line, in torso lengths.
     *
     * Rough landmarks on the scale: about -1 with the arm hanging, 0 at the
     * shoulders, 0.4 at the nose, 0.6 at the top of the head, and a little over
     * 1 at a full overhead reach.
     */
    fun wristRise(frame: PoseFrame, side: Side): Float? {
        val torso = torsoImageLength(frame) ?: return null
        return ((Angles.shoulderCentreImage(frame).y - frame.image(wrist(side)).y) / torso)
            .takeIf { it.isFinite() }
    }

    /** [wristRise] of whichever hand is higher: the signal for one-armed overhead actions. */
    fun higherWristRise(frame: PoseFrame): Float? {
        val left = wristRise(frame, Side.Left) ?: return null
        val right = wristRise(frame, Side.Right) ?: return null
        return maxOf(left, right)
    }

    /** The highest one hand reached, and the frame it reached it on. */
    data class Peak(val frame: PoseFrame, val rise: Float)

    /**
     * Frames whose torso length is consistent with the rest of the movement.
     *
     * When tracking slips for a frame, the shoulders and hips can collapse
     * toward each other. Every hand height is divided by the torso, so on that
     * frame a hand at shoulder level reads as a full overhead reach. Public
     * coaching footage had such frames, with the torso at half its length,
     * and one of them was enough to make a single wrist look raised. A frame
     * is kept when its torso is within a third of the movement's median.
     */
    fun steadyFrames(frames: List<PoseFrame>): List<PoseFrame> {
        val torsos = frames.mapNotNull { torsoImageLength(it) }.sorted()
        if (torsos.isEmpty()) return emptyList()
        val median = torsos[torsos.size / 2]
        return frames.filter { frame ->
            torsoImageLength(frame)?.let { it in median * STEADY_TORSO_LOW..median * STEADY_TORSO_HIGH } == true
        }
    }

    /**
     * The highest [side]'s hand reached over [frames], read from the frames
     * themselves rather than from the contact frame.
     *
     * The contact frame is the peak of a *smoothed* signal. A fast arm swing is
     * above the head for one or two camera frames, and the smoothing lands
     * contact beside them, or on the toss. Verdicts that read the hand only at
     * contact refused real serves on public footage for "reach overhead".
     */
    fun peakRise(frames: List<PoseFrame>, side: Side): Peak? =
        frames.mapNotNull { frame -> wristRise(frame, side)?.let { Peak(frame, it) } }.maxByOrNull { it.rise }

    /** Mean [wristRise] of both hands: the signal for two-handed overhead actions. */
    fun meanWristRise(frame: PoseFrame): Float? {
        val left = wristRise(frame, Side.Left) ?: return null
        val right = wristRise(frame, Side.Right) ?: return null
        return (left + right) / 2f
    }

    /** Seen by the camera and inside the picture, rather than inferred or clipped. */
    fun observed(frame: PoseFrame, point: BodyPoint): Boolean {
        val image = frame.image(point)
        return AngleCuePolicy.isReliable(frame, point) && image.x in 0f..1f && image.y in 0f..1f
    }

    /** True when the whole arm on [side] was observed: shoulder, elbow and wrist. */
    fun armObserved(frame: PoseFrame, side: Side): Boolean =
        listOf(shoulder(side), elbow(side), wrist(side)).all { observed(frame, it) }

    /** The arms a verdict may rely on. A side view honestly returns one. */
    fun observedArms(frame: PoseFrame): List<Side> = Side.entries.filter { armObserved(frame, it) }

    /** True when the whole leg on [side] was observed: hip, knee and ankle. */
    fun legObserved(frame: PoseFrame, side: Side): Boolean =
        listOf(hip(side), knee(side), ankle(side)).all { observed(frame, it) }

    /** Mean elbow angle over the arms that were actually observed in [frame]. */
    fun observedElbowAngle(frame: PoseFrame): Float? =
        observedArms(frame).mapNotNull { elbowAngle(frame, it) }.takeIf { it.isNotEmpty() }
            ?.average()?.toFloat()

    /**
     * How far [signal] rose from its lowest point before contact to contact.
     *
     * Measured from the lowest point rather than the first frame, because a
     * movement that dips before it rises - a backswing, a load - starts its
     * climb from the dip. Null when the signal cannot be read at contact.
     */
    fun excursion(window: RepWindow, signal: (PoseFrame) -> Float?): Float? {
        val contact = window.frameFor(RepPhase.Contact) ?: return null
        val peak = signal(contact)?.takeIf { it.isFinite() } ?: return null
        val rest = window.frames.asSequence()
            .takeWhile { it.timestampMs <= contact.timestampMs }
            .mapNotNull { signal(it)?.takeIf(Float::isFinite) }
            .minOrNull() ?: return null
        return peak - rest
    }

    /**
     * The frame of deepest knee bend among [frames], reading only legs that were
     * observed. Smoothed over three frames so one jittery knee does not win.
     */
    fun deepestKneeBend(frames: List<PoseFrame>): PoseFrame? {
        val angles = frames.map { frame ->
            Side.entries.filter { legObserved(frame, it) }.mapNotNull { kneeAngle(frame, it) }
                .takeIf { it.isNotEmpty() }?.average()?.toFloat()
        }
        var best: Int? = null
        var bestAngle = Float.MAX_VALUE
        for (index in frames.indices) {
            angles[index] ?: continue
            val around = (index - 1..index + 1).mapNotNull { angles.getOrNull(it) }
            val smoothed = around.average().toFloat()
            if (smoothed < bestAngle) { bestAngle = smoothed; best = index }
        }
        return best?.let(frames::get)
    }

    private const val MIN_TORSO_IMAGE = 0.03f

    /** The torso range [steadyFrames] accepts, as fractions of the median. */
    private const val STEADY_TORSO_LOW = 0.75f
    private const val STEADY_TORSO_HIGH = 1.33f
}
