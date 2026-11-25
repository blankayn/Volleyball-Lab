package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.FormCue
import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.analysis.AnalyzerSupport.elbowAngle
import com.volleyballlab.player.pose.analysis.AnalyzerSupport.hittingSide
import com.volleyballlab.player.pose.analysis.AnalyzerSupport.meanKneeAngle
import com.volleyballlab.player.pose.analysis.AnalyzerSupport.shoulderAngle
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame
import kotlin.math.abs

private fun Side.opposite(): Side = if (this == Side.Left) Side.Right else Side.Left

/**
 * The movement check shared by the two one-armed overhead skills.
 *
 * A serve and a spike are both, at the level a pose can see, one hand travelling
 * from below the shoulders to above the head. What separates a rep from
 * everything else an athlete does in front of a camera is how far that hand
 * went and where it finished.
 *
 * Heights are in torso lengths; see [AnalyzerSupport.wristRise]. These are
 * first-pass values chosen from body proportions, in the same spirit as the cue
 * targets, and are gathered per skill so device testing can retune them in one
 * place.
 */
private object OverheadSwing {

    /**
     * The hitting hand must travel this far upward: about seventeen centimetres
     * on an adult, several times the tracker's jitter.
     *
     * Deliberately modest. A server who starts as they are taught to - hitting
     * hand already up beside the head - has little further to go, and a larger
     * figure here silently refused that swing. Where the hand finishes is what
     * tells a swing from everything else; this only has to rule out a hand that
     * was already overhead and wobbled.
     */
    const val MIN_TRAVEL = 0.35f

    /** How far below the rep's highest hand another hand's peak may sit and still be the hit (torso lengths). */
    private const val HIT_PEAK_TOLERANCE = 0.15f

    /**
     * The hit among the rep's frames: of each hand's own highest moment that
     * comes within [HIT_PEAK_TOLERANCE] of the highest hand overall, the one
     * whose arm is straightest; at nearly equal straightness (within 10°), the
     * later one, since the hit follows the toss.
     *
     * The signal peak alone is not enough. A groupmate's side-on serves hit with
     * the left arm straight up (elbow 160-167°) and then turned, lifting the
     * right arm almost as high with the elbow bent (123-138°). On 6 of 14 serves
     * the bent arm's moment was the higher by a few pixels and became contact,
     * so the arm checks measured the wrong arm.
     */
    fun hitIndex(frames: List<PoseFrame>, signalPeak: Int): Int {
        val top = frames.getOrNull(signalPeak)?.let { AnalyzerSupport.higherWristRise(it) } ?: return signalPeak
        val peaks = Side.entries.mapNotNull { side ->
            val best = frames.indices.mapNotNull { i -> AnalyzerSupport.wristRise(frames[i], side)?.let { i to it } }
                .maxByOrNull { it.second } ?: return@mapNotNull null
            if (best.second < top - HIT_PEAK_TOLERANCE) return@mapNotNull null
            val elbow = elbowAngle(frames[best.first], side) ?: return@mapNotNull null
            best.first to elbow
        }
        if (peaks.isEmpty()) return signalPeak
        val straightest = peaks.maxOf { it.second }
        return peaks.filter { it.second >= straightest - 10f }.maxOf { it.first }
    }

    /**
     * Where the hitting hand must finish: clear of the top of the head. An
     * overhead contact with a bent arm still reaches it; a hand raised to the
     * face does not.
     */
    const val MIN_CONTACT_RISE = 0.55f

    /**
     * A hand that never got above the shoulder line was not an attempt at an
     * overhead swing - hands on hips, a tug at a shirt - and is passed over in
     * silence rather than coached.
     */
    const val ATTEMPT_RISE = 0f

    /**
     * Peaks closer together than this are one moment: the hitting hand is the
     * higher one. Further apart, the toss came first and the swing after it.
     */
    const val SAME_MOMENT_MS = 150L

    /**
     * Judged on each hand's own peak across the movement, not on the contact
     * frame. Contact is the peak of a smoothed curve. On public coaching
     * footage it landed beside a swing that was overhead for one or two
     * frames, or on the toss, and real serves filmed from behind were refused
     * for "reach overhead".
     */
    fun verdict(window: RepWindow): MovementVerdict {
        val frames = AnalyzerSupport.steadyFrames(window.frames)
        val left = AnalyzerSupport.peakRise(frames, Side.Left)
        val right = AnalyzerSupport.peakRise(frames, Side.Right)
        val hitting = when {
            left == null && right == null -> return MovementVerdict.TooSmall
            left == null -> Side.Right
            right == null -> Side.Left
            // The toss rises first and the swing follows it, so when both
            // hands went above the shoulders the later peak is the hitting
            // hand. That also catches a low "shot-put" swing behind a high toss.
            left.rise >= ATTEMPT_RISE && right.rise >= ATTEMPT_RISE &&
                abs(left.frame.timestampMs - right.frame.timestampMs) > SAME_MOMENT_MS ->
                if (left.frame.timestampMs > right.frame.timestampMs) Side.Left else Side.Right
            else -> if (left.rise >= right.rise) Side.Left else Side.Right
        }
        val peak = (if (hitting == Side.Left) left else right) ?: return MovementVerdict.TooSmall
        // The hitting hand's own travel up to its peak, not the segmentation
        // signal's. That signal follows whichever hand is higher, so a guiding
        // arm held up in front would hide most of the swing that passes it.
        val lowest = frames.asSequence()
            .takeWhile { it.timestampMs <= peak.frame.timestampMs }
            .mapNotNull { AnalyzerSupport.wristRise(it, hitting) }
            .minOrNull() ?: return MovementVerdict.TooSmall
        if (peak.rise - lowest < MIN_TRAVEL || peak.rise < ATTEMPT_RISE) return MovementVerdict.TooSmall
        if (!AnalyzerSupport.armObserved(peak.frame, hitting)) {
            return MovementVerdict.NotCounted(MovementFault.ArmsNotVisible)
        }
        if (peak.rise < MIN_CONTACT_RISE) return MovementVerdict.NotCounted(MovementFault.ReachOverhead)
        incompleteSwing(frames, peak, hitting, if (hitting == Side.Left) right else left)
            ?.let { return MovementVerdict.NotCounted(it) }
        return MovementVerdict.Counted
    }

    /**
     * What a hand that did get overhead is still missing to be a swing, or null.
     *
     * Reaching overhead alone let unrelated movements count: a set, a block, a
     * stretch, a wave or a raised hand all end there. A swing is drawn back
     * first, by one arm, with the other lifted to guide it. Checked against
     * recorded reps: the groupmate's 14 serves and 15 spikes all pass; of 32
     * serves and 27 spikes marked correct in public coaching clips, 29 and 24
     * do, and 78 and 75 two-handed movements from other skills no longer count.
     */
    private fun incompleteSwing(
        frames: List<PoseFrame>,
        peak: AnalyzerSupport.Peak,
        hitting: Side,
        otherPeak: AnalyzerSupport.Peak?,
    ): MovementFault? {
        val other = hitting.opposite()
        val before = frames.filter { it.timestampMs <= peak.frame.timestampMs }
        if (otherPeak != null && AnalyzerSupport.armObserved(peak.frame, other)) {
            val otherAtPeak = AnalyzerSupport.wristRise(peak.frame, other)
            if (otherAtPeak != null && otherAtPeak >= BOTH_HANDS_UP && peak.rise - otherAtPeak < BOTH_HANDS_LEVEL &&
                abs(peak.frame.timestampMs - otherPeak.frame.timestampMs) <= BOTH_HANDS_TOGETHER_MS) {
                return MovementFault.SwingOneHand
            }
        }
        // The tightest the hitting elbow got with the arm carried up near the
        // shoulder. An elbow that was never carried there was not drawn back
        // either, but it was not measured, and is left alone.
        val drawn = before.filter { elbowCarried(it, hitting) }.mapNotNull { elbowAngle(it, hitting) }.minOrNull()
        if (drawn != null && drawn > MAX_DRAWN_ELBOW) return MovementFault.DrawElbowBack
        // Side-on the guiding arm is behind the body for most of the swing. Only
        // an arm that was seen hanging for most of it is asked for.
        val guide = before.filter { AnalyzerSupport.armObserved(it, other) }
        val lifted = guide.mapNotNull { AnalyzerSupport.wristRise(it, other) }.maxOrNull()
        if (lifted != null && guide.size * 2 >= before.size && lifted < MIN_GUIDE_RISE) return MovementFault.LiftGuidingArm
        return null
    }

    private fun elbowCarried(frame: PoseFrame, side: Side): Boolean {
        val torso = AnalyzerSupport.torsoImageLength(frame) ?: return false
        val rise = (Angles.shoulderCentreImage(frame).y - frame.image(AnalyzerSupport.elbow(side)).y) / torso
        return rise > ELBOW_CARRIED_RISE
    }

    /** Two hands this high, this level and peaking this close together are one two-handed movement. */
    private const val BOTH_HANDS_UP = 0.45f
    private const val BOTH_HANDS_LEVEL = 0.25f
    private const val BOTH_HANDS_TOGETHER_MS = 200L

    /** Below the shoulder line by this much or less, the elbow is carried (torso lengths). */
    private const val ELBOW_CARRIED_RISE = -0.25f

    /** Straighter than this all the way up and the arm was lifted, not swung. */
    private const val MAX_DRAWN_ELBOW = 160f

    /** The guiding hand must come up to about the chest at some point before the hit. */
    private const val MIN_GUIDE_RISE = -0.25f
}

/**
 * Overhand serve.
 *
 * Segmentation follows the hitting hand: a serve is defined by the hand rising
 * to meet the ball above the head, and that produces a large, clean peak that
 * survives a noisy tracker.
 */
class ServeAnalyzer : SkillAnalyzer {
    override val skill = Skill.Serving

    override fun segmentationSignal(frame: PoseFrame): Float? =
        AnalyzerSupport.higherWristRise(frame)

    override val minExcursion: Float get() = OverheadSwing.MIN_TRAVEL

    override fun verdict(window: RepWindow): MovementVerdict =
        OverheadSwing.verdict(window)

    /** The hit, not a later moment when the other hand rises as the body turns. */
    override fun contactIndex(frames: List<PoseFrame>, signalPeak: Int): Int =
        OverheadSwing.hitIndex(frames, signalPeak)

    override fun measure(cue: FormCue, window: RepWindow): Float? {
        val contact = window.frameFor(RepPhase.Contact) ?: return null
        val hitting = hittingSide(contact)
        return when (cue.id) {
            CueId.ServeContactArmExtension -> elbowAngle(contact, hitting)
            CueId.ServeReachHeight -> shoulderAngle(contact, hitting)
            CueId.ServeTrunkLean -> Angles.trunkForwardLean(contact)
            // Measured on the non-hitting arm: the tossing arm is by definition the
            // one that is not about to strike the ball.
            CueId.ServeTossArmLift -> cueFrame(cue, window)?.let { shoulderAngle(it, hitting.opposite()) }

            else -> null
        }
    }

    /**
     * The guiding-arm lift is read where the arm is highest before contact, not on
     * the preparation frame. Preparation is the window's first frame, before the
     * arm has moved: a groupmate's 14 serves read 17-52° there against the
     * 90-160° target, and only 7 of 29 correct side-on public serves passed.
     * The arm's shoulder angle is averaged over three frames before picking, so
     * one jittery frame cannot become the highest.
     */
    override fun cueFrame(cue: FormCue, window: RepWindow): PoseFrame? {
        if (cue.id != CueId.ServeTossArmLift) return super.cueFrame(cue, window)
        val contact = window.frameFor(RepPhase.Contact) ?: return super.cueFrame(cue, window)
        val guiding = hittingSide(contact).opposite()
        val before = window.frames.filter { it.timestampMs <= contact.timestampMs }
        val lifts = before.map { shoulderAngle(it, guiding) }
        val highest = lifts.indices.mapNotNull { i ->
            val near = (maxOf(0, i - 1)..minOf(lifts.lastIndex, i + 1)).mapNotNull { lifts[it] }
            if (lifts[i] == null || near.isEmpty()) null else i to near.average()
        }.maxByOrNull { it.second } ?: return super.cueFrame(cue, window)
        return before[highest.first]
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
        AnalyzerSupport.steadyKneeAngle(frame)?.let { STRAIGHT_LEG_DEGREES - it }

    override val minExcursion: Float get() = MIN_KNEE_DIP_DEGREES

    /**
     * A pass is a knee bend *with a platform under the ball*. The bend alone is
     * what this skill is segmented on, so without this check every squat, and
     * every time the athlete sat down into their stance, was a counted pass -
     * and a well-scored one, because arms hanging at the sides are straight.
     */
    override fun verdict(window: RepWindow): MovementVerdict {
        val contact = window.frameFor(RepPhase.Contact) ?: return MovementVerdict.TooSmall
        val dip = AnalyzerSupport.excursion(window, ::segmentationSignal) ?: return MovementVerdict.TooSmall
        if (dip < MIN_KNEE_DIP_DEGREES) return MovementVerdict.TooSmall
        if (Side.entries.none { AnalyzerSupport.legObserved(contact, it) }) {
            return MovementVerdict.NotCounted(MovementFault.LegsNotVisible)
        }
        // The platform has to be there as the ball is met, not for one frame of
        // it: the frames on either side of the deepest bend vote.
        val index = window.frames.indexOfFirst { it.timestampMs >= contact.timestampMs }.coerceAtLeast(0)
        val met = window.frames.subList((index - 1).coerceAtLeast(0), (index + 2).coerceAtMost(window.frames.size))
        val sideOn = AnalyzerSupport.sideOn(window)
        val faults = met.mapNotNull { platformFault(it, sideOn) }
        if (faults.size * 2 <= met.size) return MovementVerdict.Counted
        val fault = faults.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
        return MovementVerdict.NotCounted(fault)
    }

    /**
     * What is wrong with the forearm platform on this frame, or null.
     *
     * A platform is **both** forearms, joined, straight and level. Facing the
     * camera both arms are in view, so both are required: one arm out with the
     * other hanging, hidden or behind the back is not a pass. Joined wrists in
     * public front-view passes measured 0.08 to 0.27 torso lengths apart on
     * screen and hands that were not joined 0.50 or more.
     *
     * Side-on the far arm is behind the near one, and the tracker's guess at it
     * is unusable (a groupmate's joined passes read 0.3 to 1.1 torso lengths
     * apart with the far wrist at visibility 0.04 to 0.18). So the near arm must
     * make the platform, and the far arm is refused only when it was seen
     * somewhere else. From behind the arms cannot be seen at all, and a platform
     * that cannot be seen is not counted.
     */
    private fun platformFault(frame: PoseFrame, sideOn: Boolean): MovementFault? {
        val arms = AnalyzerSupport.observedArms(frame)
        if (arms.isEmpty() || !sideOn && arms.size < 2) return MovementFault.ArmsNotVisible
        val rises = arms.mapNotNull { AnalyzerSupport.wristRise(frame, it) }
        if (rises.any { it > MAX_PLATFORM_RISE }) return MovementFault.PlatformTooHigh
        val gap = AnalyzerSupport.wristGap(frame)
        if (!sideOn) {
            val shoulders = Angles.distance(
                frame.world(BodyPoint.LEFT_SHOULDER), frame.world(BodyPoint.RIGHT_SHOULDER),
            )
            val worldGap = Angles.distance(frame.world(BodyPoint.LEFT_WRIST), frame.world(BodyPoint.RIGHT_WRIST))
            val level = rises.size < 2 || rises.max() - rises.min() <= MAX_LEVEL_GAP
            if (!level) return MovementFault.UseBothArms
            if (gap != null && gap > MAX_JOINED_GAP ||
                shoulders > MIN_SHOULDER_WIDTH_METRES && worldGap / shoulders > MAX_WRIST_GAP) {
                return MovementFault.JoinPlatform
            }
        } else {
            if (gap != null) {
                if (arms.size == 2 && gap > MAX_SIDE_GAP) return MovementFault.UseBothArms
                // Partly hidden: a guess, unless it is nowhere near the other hand.
                val farSeen = Side.entries.filterNot { it in arms }.any {
                    frame.image(AnalyzerSupport.wrist(it)).visibility >= FAR_WRIST_SEEN
                }
                if (farSeen && gap > MAX_FAR_GAP) return MovementFault.UseBothArms
            }
            // Side-on, hands joined against the thighs look the same as a platform
            // to the gap test, so the hands must also be out in front of the hips.
            val w = frame.imageWidth.toFloat()
            val h = frame.imageHeight.toFloat()
            val shoulder = Angles.shoulderCentreImage(frame)
            val hip = Angles.hipCentreImage(frame)
            val torso = kotlin.math.hypot((shoulder.x - hip.x) * w, (shoulder.y - hip.y) * h)
            if (torso > 0f) {
                val forward = arms.minOf { abs(frame.image(AnalyzerSupport.wrist(it)).x - hip.x) * w / torso }
                if (forward < MIN_FORWARD_REACH) return MovementFault.JoinPlatform
            }
        }
        val elbows = arms.mapNotNull { platformElbow(frame, it, sideOn) }
        if (elbows.any { it < MIN_PLATFORM_ELBOW }) return MovementFault.StraightenPlatform
        return null
    }

    override fun measure(cue: FormCue, window: RepWindow): Float? {
        val contact = window.frameFor(RepPhase.Contact) ?: return null
        return when (cue.id) {
            CueId.PassPlatformStraight -> AnalyzerSupport.sideOn(window).let { sideOn ->
                AngleCuePolicy.visibleSides(contact) {
                    listOf(AnalyzerSupport.shoulder(it), AnalyzerSupport.elbow(it), AnalyzerSupport.wrist(it))
                }.mapNotNull { platformElbow(contact, it, sideOn) }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
            }
            CueId.PassPlatformHeight -> platformBelowHorizontal(contact)
            CueId.PassKneeFlexion -> AnalyzerSupport.visibleKneeAngle(contact)
            CueId.PassTrunkAngle -> Angles.trunkForwardLean(contact)
            CueId.PassShouldersLevel -> Angles.shoulderTilt(contact)
            else -> null
        }
    }

    /** Side-on the arm lies across the picture, so it is read there; facing the lens it needs the depth. */
    private fun platformElbow(frame: PoseFrame, side: Side, sideOn: Boolean): Float? =
        listOfNotNull(elbowAngle(frame, side), if (sideOn) AnalyzerSupport.imageElbowAngle(frame, side) else null).maxOrNull()

    /**
     * Degrees the shoulder-to-wrist line points below horizontal, averaged over
     * the arms the camera saw; null when it saw neither.
     *
     * The verdict's [MovementFault.PlatformTooHigh] only refuses hands above the
     * shoulder line, which is not a pass at all. A pass made with the arms lifted
     * to about shoulder height is still a pass, with the platform too high: a
     * groupmate's 15 such passes read 7-15° here and all were counted with no word
     * about it. World space, so the slope reads the same from the front, where
     * the arms point at the camera, as from the side.
     */
    private fun platformBelowHorizontal(frame: PoseFrame): Float? =
        AnalyzerSupport.observedArms(frame).mapNotNull { side ->
            val shoulder = frame.world(AnalyzerSupport.shoulder(side))
            val wrist = frame.world(AnalyzerSupport.wrist(side))
            val length = Angles.distance(shoulder, wrist)
            // World y points down, so a wrist below the shoulder has the larger y.
            if (length < 1e-3f) null else Math.toDegrees(kotlin.math.asin(((wrist.y - shoulder.y) / length).coerceIn(-1f, 1f)).toDouble()).toFloat()
        }.takeIf { it.isNotEmpty() }?.average()?.toFloat()

    private companion object {
        const val STRAIGHT_LEG_DEGREES = 180f

        /**
         * How much deeper the knees must go than where the movement began.
         * Standing knees wobble by several degrees in the tracker's depth
         * estimate; a pass sits well clear of that.
         */
        const val MIN_KNEE_DIP_DEGREES = 20f

        /** Wrist gap in depth as a share of shoulder width. Hanging arms measure above 1. */
        const val MAX_WRIST_GAP = 0.60f

        /** Joined wrists on screen, in torso lengths, facing the camera. */
        const val MAX_JOINED_GAP = 0.35f

        /**
         * The same with both wrists seen side-on, where the far one is partly a
         * guess. A far arm hanging or behind the back is about a torso length away.
         */
        const val MAX_SIDE_GAP = 0.70f

        /** A far wrist at least this visible, this far from the near one, is somewhere else. */
        const val FAR_WRIST_SEEN = 0.30f
        const val MAX_FAR_GAP = 0.75f

        /** One wrist this much higher than the other is one arm, not a level platform. */
        const val MAX_LEVEL_GAP = 0.25f

        /** Straight enough to be a platform at all; how straight is the cue's job. */
        const val MIN_PLATFORM_ELBOW = 125f

        /** Torso lengths above the shoulder line. A platform lives below it. */
        const val MAX_PLATFORM_RISE = 0.15f

        /** Torso lengths the hands must be ahead of the hips, seen from the side. */
        const val MIN_FORWARD_REACH = 0.40f
        const val MIN_SHOULDER_WIDTH_METRES = 0.05f
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

    override fun segmentationSignal(frame: PoseFrame): Float? =
        AnalyzerSupport.meanWristRise(frame)

    override val minExcursion: Float get() = MIN_EXCURSION

    /** A setter or blocker keeps the hands up between pushes. */
    override val repeatsFromRaisedHands: Boolean get() = true

    /** The deepest knee bend before the push, not the lowest the hands got. */
    override fun loadingFrame(frames: List<PoseFrame>): PoseFrame? =
        AnalyzerSupport.deepestKneeBend(frames)

    /**
     * A set is both hands rising together to above the face, with the elbows
     * bent on the way to shape the hands. One hand, hands that stop at the
     * chest, and straight arms swung overhead are all something else.
     */
    override fun verdict(window: RepWindow): MovementVerdict {
        val contact = window.frameFor(RepPhase.Contact) ?: return MovementVerdict.TooSmall
        val excursion = AnalyzerSupport.excursion(window, ::segmentationSignal) ?: return MovementVerdict.TooSmall
        if (excursion < MIN_EXCURSION) return MovementVerdict.TooSmall
        val rises = AnalyzerSupport.observedArms(contact).mapNotNull { AnalyzerSupport.wristRise(contact, it) }
        // Facing the camera both hands are in view, so both are required. Side-on
        // the far one is behind the head.
        if (rises.isEmpty() || rises.size < 2 && !AnalyzerSupport.sideOn(window)) {
            return MovementVerdict.NotCounted(MovementFault.ArmsNotVisible)
        }
        if (rises.size == 2 && rises.max() >= MIN_HAND_RISE && rises.max() - rises.min() > MAX_HAND_GAP) {
            return MovementVerdict.NotCounted(MovementFault.UseBothHands)
        }
        if (rises.min() < MIN_HAND_RISE) return MovementVerdict.NotCounted(MovementFault.HandsAboveForehead)
        if (rises.size == 2 && (AnalyzerSupport.wristGap(contact) ?: 0f) > MAX_HANDS_APART) {
            return MovementVerdict.NotCounted(MovementFault.HandsTogether)
        }
        if (!handsFinishLevel(window)) return MovementVerdict.NotCounted(MovementFault.UseBothHands)
        // Only an elbow that was seen staying straight is a fault. One that could
        // not be measured on the way up is unknown, not wrong.
        val tightest = window.frames.asSequence()
            .takeWhile { it.timestampMs <= contact.timestampMs }
            .mapNotNull { AnalyzerSupport.observedElbowAngle(it) }
            .minOrNull()
        if (tightest != null && tightest > MAX_STRAIGHT_ELBOW) {
            return MovementVerdict.NotCounted(MovementFault.BendElbowsFirst)
        }
        if ((pushDegrees(window, contact) ?: MIN_PUSH_DEGREES) < MIN_PUSH_DEGREES) {
            return MovementVerdict.NotCounted(MovementFault.PushToExtend)
        }
        return MovementVerdict.Counted
    }

    /**
     * Both hands at the top together. On a swing the guiding hand and the hitting
     * hand pass each other at face height, and at that moment their average height
     * looks like a set; at the higher hand's own peak the other is far below it.
     * On correct public sets it was never more than 0.17 torso lengths below (19
     * measured); on the spikes that used to count as sets it was over 0.35 on 30
     * of 39.
     */
    private fun handsFinishLevel(window: RepWindow): Boolean {
        val frames = AnalyzerSupport.steadyFrames(window.frames)
        val left = AnalyzerSupport.peakRise(frames, Side.Left) ?: return true
        val right = AnalyzerSupport.peakRise(frames, Side.Right) ?: return true
        val (higher, other) = if (left.rise >= right.rise) left to Side.Right else right to Side.Left
        if (!AnalyzerSupport.armObserved(higher.frame, other)) return true
        val otherAtPeak = AnalyzerSupport.wristRise(higher.frame, other) ?: return true
        return higher.rise - otherAtPeak <= MAX_HAND_GAP
    }

    /**
     * How far the elbows opened from the catch to the finish, with the hands up at
     * the face throughout; null when it was not seen. Hands that only come up to
     * the face and go back down - adjusting hair, shading the eyes - never push.
     * Of 31 correct public sets 29 opened 8 degrees or more (median 45).
     */
    private fun pushDegrees(window: RepWindow, contact: PoseFrame): Float? {
        val raised = window.frames.filter { (AnalyzerSupport.meanWristRise(it) ?: return@filter false) >= MIN_HAND_RISE }
        val caught = raised.filter { it.timestampMs <= contact.timestampMs }
            .mapNotNull { frame -> AnalyzerSupport.observedElbowAngle(frame)?.let { frame to it } }
            .minByOrNull { it.second } ?: return null
        val finish = raised.filter { it.timestampMs >= caught.first.timestampMs }
            .mapNotNull { AnalyzerSupport.observedElbowAngle(it) }.maxOrNull() ?: return null
        return finish - caught.second
    }

    override fun measure(cue: FormCue, window: RepWindow): Float? = when (cue.id) {
        CueId.SetHandsAboveForehead -> window.frameFor(RepPhase.Contact)
            ?.let { handsAboveNoseMetres(it) }

        CueId.SetElbowFlexion -> cueFrame(cue, window)?.let { setElbowAngle(it) }
        CueId.SetExtensionFollowThrough -> cueFrame(cue, window)?.let(finishElbowAngle(window))
        // Measured during loading, not contact: the leg drive that powers a set
        // happens before the hands touch the ball, and by contact the athlete is
        // already extending.
        CueId.SetKneeFlexion -> window.frameFor(RepPhase.Loading)?.let { AnalyzerSupport.visibleKneeAngle(it) }

        else -> null
    }

    /**
     * The elbow cues are read where they happen, not on the phase frames.
     *
     * The window's contact is the peak of hand height: the end of the push, with
     * the arms already straight. Read there, the 80-120° elbow bend could never be
     * met; 0 of 31 correct sets in public coaching clips passed, the same as the
     * faulty ones. Its follow-through frame is the window's last, when the hands
     * are already on the way down, and 2 of 31 correct sets passed the arm finish.
     *
     * - Elbow bend is read at the catch: the most bent the elbows get while both
     *   hands are up at the face, before the hands peak. That is where the ball
     *   meets a setter's hands.
     * - Arm finish is read at the fullest extension from the catch on, with the
     *   hands still up: the push's own end, whichever frame it fell on.
     *
     * Elbow angles are averaged over three frames before picking, so one jittery
     * frame cannot become the catch or the finish. Either falls back to its phase
     * frame when the hands never reach the face in the window.
     *
     * Over the 31 correct public sets, elbow bend now passes 24 and arm finish 23,
     * against 0 and 2 on the phase frames.
     */
    override fun cueFrame(cue: FormCue, window: RepWindow): PoseFrame? = when (cue.id) {
        CueId.SetElbowFlexion -> catchFrame(window) ?: super.cueFrame(cue, window)
        CueId.SetExtensionFollowThrough -> finishFrame(window) ?: super.cueFrame(cue, window)
        else -> super.cueFrame(cue, window)
    }

    private fun catchFrame(window: RepWindow): PoseFrame? {
        val contact = window.frameFor(RepPhase.Contact) ?: return null
        return extremeElbow(window.frames.filter { it.timestampMs <= contact.timestampMs }, straightest = false, ::setElbowAngle)
    }

    private fun finishFrame(window: RepWindow): PoseFrame? {
        val from = catchFrame(window) ?: return null
        return extremeElbow(window.frames.filter { it.timestampMs >= from.timestampMs }, straightest = true,
            finishElbowAngle(window))
    }

    /**
     * How the arm finish is read for this rep. Side-on, the arms extend across the
     * picture, where the image shows their true angle and the tracker's depth
     * estimate bends them: of 19 correct sets filmed from the side, 4 reached the
     * 155° finish in world space (median 147°) and 15 read this way (median 165°).
     * From the front the arms extend toward the camera, the image foreshortens them,
     * and world space is the right reading. The view is decided once, at contact,
     * so one rep is never read half one way and half the other.
     */
    private fun finishElbowAngle(window: RepWindow): (PoseFrame) -> Float? {
        val sideOn = window.frameFor(RepPhase.Contact)
            ?.let { CameraOrientationDetector().detect(it).angle == CameraAngle.Side } == true
        return if (sideOn) { frame -> imageElbowAngle(frame) ?: setElbowAngle(frame) } else ::setElbowAngle
    }

    /** Mean elbow angle in the picture, in pixels so the frame's aspect ratio cannot skew it, over observed arms. */
    private fun imageElbowAngle(frame: PoseFrame): Float? {
        val w = frame.imageWidth.toFloat()
        val h = frame.imageHeight.toFloat()
        fun pixels(point: BodyPoint) = frame.image(point).let { it.copy(x = it.x * w, y = it.y * h, z = 0f) }
        return AnalyzerSupport.observedArms(frame).mapNotNull { side ->
            Angles.at(pixels(AnalyzerSupport.shoulder(side)), pixels(AnalyzerSupport.elbow(side)), pixels(AnalyzerSupport.wrist(side)))
        }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    }

    /**
     * Mean elbow angle over the arms the camera saw, or both when it saw neither.
     * Side-on, the far arm is hidden and its estimated angle reads a straight arm
     * as bent: correct sets filmed from the side averaged 146° at the finish
     * against 161° from the front.
     */
    private fun setElbowAngle(frame: PoseFrame): Float? =
        AnalyzerSupport.observedElbowAngle(frame) ?: AnalyzerSupport.visibleElbowAngle(frame)

    /** The frame of most bent or straightest elbows among [frames] with both hands up at the face. */
    private fun extremeElbow(frames: List<PoseFrame>, straightest: Boolean, elbow: (PoseFrame) -> Float?): PoseFrame? {
        val raised = frames.filter { (AnalyzerSupport.meanWristRise(it) ?: return@filter false) >= MIN_HAND_RISE }
        val angles = raised.map(elbow)
        val smoothed = angles.indices.mapNotNull { i ->
            val near = (maxOf(0, i - 1)..minOf(angles.lastIndex, i + 1)).mapNotNull { angles[it] }
            if (angles[i] == null || near.isEmpty()) null else i to near.average()
        }
        val pick = if (straightest) smoothed.maxByOrNull { it.second } else smoothed.minByOrNull { it.second }
        return pick?.let { raised[it.first] }
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
        // The wrists the camera saw: side-on the far hand is behind the head.
        val wrists = AnalyzerSupport.visibleWrists(frame)
        // World y increases downward, so "above" is the nose's y minus the wrists'.
        return nose.y - wrists.y
    }

    private companion object {
        /**
         * Torso lengths. Smaller than the one-armed skills because a setter can
         * begin with the hands already raised, leaving only the push to travel.
         */
        const val MIN_EXCURSION = 0.30f

        /** Each hand must finish at least level with the face. */
        const val MIN_HAND_RISE = 0.30f

        /** Further apart in height than this and one hand did the work. */
        const val MAX_HAND_GAP = 0.35f

        /** An elbow that never closed past this was a straight-arm lift. */
        const val MAX_STRAIGHT_ELBOW = 150f

        /** Hands further apart than this on screen (torso lengths) are not shaping a ball. Correct sets peaked at 0.77. */
        const val MAX_HANDS_APART = 0.90f

        /** The least the elbows open in a push. */
        const val MIN_PUSH_DEGREES = 8f
    }
}

/**
 * Spike approach and attack.
 *
 * Segmentation follows the hitting hand, as for the serve: the arm swing is the
 * one thing every spike has, with or without a jump, and it peaks at contact.
 *
 * ## Why this no longer follows the hips
 *
 * It used to be hip height, on the reasoning that the jump is the largest
 * movement in a spike. Hip height is also what a knee bend moves. Standing up
 * out of one put the hips at the top of their range, which the segmenter read as
 * the top of a jump, so every knee bend was counted as a spike - and scored as
 * a good one, since the "contact" frame was the athlete standing with a straight
 * arm at their side. It also could not see the standing arm-swing drill at all,
 * where the hips barely move.
 *
 * The jump is still measured: [Angles.hipRiseMetres] at contact, against the
 * hip height the rep began at.
 */
class SpikeAnalyzer : SkillAnalyzer {
    override val skill = Skill.Spiking

    override fun segmentationSignal(frame: PoseFrame): Float? =
        AnalyzerSupport.higherWristRise(frame)

    override val minExcursion: Float get() = OverheadSwing.MIN_TRAVEL

    /** The deepest knee bend of the approach, not the lowest the hand got. */
    override fun loadingFrame(frames: List<PoseFrame>): PoseFrame? =
        AnalyzerSupport.deepestKneeBend(frames)

    override fun verdict(window: RepWindow): MovementVerdict =
        OverheadSwing.verdict(window)

    /** The hit, not a later moment when the other hand rises as the body turns. */
    override fun contactIndex(frames: List<PoseFrame>, signalPeak: Int): Int =
        OverheadSwing.hitIndex(frames, signalPeak)

    override fun measure(cue: FormCue, window: RepWindow): Float? = when (cue.id) {
        CueId.SpikeApproachLoad -> window.frameFor(RepPhase.Loading)?.let { AnalyzerSupport.visibleKneeAngle(it) }
        CueId.SpikeArmExtension -> window.frameFor(RepPhase.Contact)
            ?.let { elbowAngle(it, hittingSide(it)) }

        CueId.SpikeElbowHeight -> cockedArm(window)?.let { (frame, side) -> upperArmAboveHorizontal(frame, side) }

        CueId.SpikeJumpHeight -> window.frameFor(RepPhase.Contact)
            ?.let { Angles.hipRiseMetres(it, window.baselineHipImageY) }

        CueId.SpikeTrunkDrive -> window.frameFor(RepPhase.Contact)
            ?.let { Angles.trunkForwardLean(it) }

        else -> null
    }

    /**
     * A dropped elbow happens before contact, so the elbow-height cue is read at
     * the cocked arm, not on a phase frame. At contact a dropped-elbow swing still
     * finishes straight: a groupmate's 15 dropped-elbow swings measured 160-173°
     * of arm extension there and passed it every time.
     */
    override fun cueFrame(cue: FormCue, window: RepWindow): PoseFrame? =
        if (cue.id == CueId.SpikeElbowHeight) cockedArm(window)?.first else super.cueFrame(cue, window)

    /**
     * The cocked arm: in the second before contact, with the hitting hand above
     * the shoulder line and that arm seen by the camera, the frame where its elbow
     * is most bent (a 3-frame mean, so one jittery frame cannot win).
     *
     * Side-on only. From the side the upper arm swings across the picture and its
     * angle reads true; from the front it swings toward the camera. On public
     * clips the side-on reading told correct from dropped-elbow spikes (AUC 0.69,
     * 36 correct, 32 dropped) and the front-on one did not (14 clips). The
     * hitting side is the higher hand at contact, as for the arm-extension cue.
     *
     * The view is the rep's majority, not the contact frame's: a hitter's
     * shoulders turn to the camera as the arm comes through, so a side-on swing
     * read Front at contact on 6 of a groupmate's first 8 reps while most of each
     * rep read Side.
     */
    private fun cockedArm(window: RepWindow): Pair<PoseFrame, Side>? {
        val contact = window.frameFor(RepPhase.Contact) ?: return null
        val views = window.frames.map { CameraOrientationDetector().detect(it).angle }
        if (views.count { it == CameraAngle.Side } <= views.count { it == CameraAngle.Front || it == CameraAngle.Rear }) return null
        val side = hittingSide(contact)
        val raised = window.frames.filter {
            // Not limited to frames where the arm is clearly seen: a dropped elbow
            // sits behind the body, partly hidden, and a visibility filter kept
            // only the high positions (a groupmate's dropped swings read +42 to
            // +59° with it). The tracker's image estimate there is what is read.
            it.timestampMs <= contact.timestampMs && contact.timestampMs - it.timestampMs <= COCK_SEARCH_MS &&
                (AnalyzerSupport.wristRise(it, side) ?: return@filter false) > 0f
        }
        val bends = raised.map { imageElbowAngle(it, side) }
        val cocked = bends.indices.mapNotNull { i ->
            val near = (maxOf(0, i - 1)..minOf(bends.lastIndex, i + 1)).mapNotNull { bends[it] }
            if (bends[i] == null || near.isEmpty()) null else i to near.average()
        }.minByOrNull { it.second } ?: return null
        return raised[cocked.first] to side
    }

    private fun pixels(frame: PoseFrame, point: BodyPoint) = frame.image(point).let {
        it.copy(x = it.x * frame.imageWidth, y = it.y * frame.imageHeight, z = 0f)
    }

    private fun imageElbowAngle(frame: PoseFrame, side: Side): Float? = Angles.at(
        pixels(frame, AnalyzerSupport.shoulder(side)), pixels(frame, AnalyzerSupport.elbow(side)),
        pixels(frame, AnalyzerSupport.wrist(side)),
    )

    /** Degrees the upper arm points above horizontal in the picture: 0 with the elbow level with the shoulder. */
    private fun upperArmAboveHorizontal(frame: PoseFrame, side: Side): Float? {
        val shoulder = pixels(frame, AnalyzerSupport.shoulder(side))
        val elbow = pixels(frame, AnalyzerSupport.elbow(side))
        val dx = abs(elbow.x - shoulder.x)
        val up = shoulder.y - elbow.y
        if (dx < 1e-3f && abs(up) < 1e-3f) return null
        return Math.toDegrees(kotlin.math.atan2(up, dx).toDouble()).toFloat()
    }

    private companion object {
        /** How far before contact the cocked arm is looked for. */
        const val COCK_SEARCH_MS = 1_000L
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

    override fun segmentationSignal(frame: PoseFrame): Float? =
        AnalyzerSupport.meanWristRise(frame)

    override val minExcursion: Float get() = MIN_EXCURSION

    /** A setter or blocker keeps the hands up between pushes. */
    override val repeatsFromRaisedHands: Boolean get() = true

    /** The deepest knee bend before the reach, not the lowest the hands got. */
    override fun loadingFrame(frames: List<PoseFrame>): PoseFrame? =
        AnalyzerSupport.deepestKneeBend(frames)

    /** A block is both hands reaching clear above the head on mostly straight arms. */
    override fun verdict(window: RepWindow): MovementVerdict {
        val contact = window.frameFor(RepPhase.Contact) ?: return MovementVerdict.TooSmall
        val excursion = AnalyzerSupport.excursion(window, ::segmentationSignal) ?: return MovementVerdict.TooSmall
        if (excursion < MIN_EXCURSION) return MovementVerdict.TooSmall
        val rises = AnalyzerSupport.observedArms(contact).mapNotNull { AnalyzerSupport.wristRise(contact, it) }
        if (rises.isEmpty() || rises.size < 2 && !AnalyzerSupport.sideOn(window)) {
            return MovementVerdict.NotCounted(MovementFault.ArmsNotVisible)
        }
        if (rises.size == 2 && rises.max() >= MIN_HAND_RISE && rises.min() < ONE_HAND_BELOW) {
            return MovementVerdict.NotCounted(MovementFault.UseBothHands)
        }
        if (rises.min() < MIN_HAND_RISE) return MovementVerdict.NotCounted(MovementFault.ReachBothHandsHigh)
        val elbows = AnalyzerSupport.observedElbowAngle(contact)
        if (elbows != null && elbows < MIN_ELBOW_ANGLE) {
            return MovementVerdict.NotCounted(MovementFault.StraightenArms)
        }
        if (!handsPeakTogether(window)) return MovementVerdict.NotCounted(MovementFault.UseBothHands)
        // Arms lifted from still legs are a stretch. All 26 correct public blocks
        // bent the knees at least 8 degrees on the way up or left the floor.
        val load = AnalyzerSupport.kneeLoad(window)
        val lift = AnalyzerSupport.hipLift(window)
        if (load != null && lift != null && load < MIN_KNEE_LOAD_DEGREES && lift < MIN_HIP_LIFT) {
            return MovementVerdict.NotCounted(MovementFault.BendKnees)
        }
        return MovementVerdict.Counted
    }

    /**
     * A block is both hands going up as one. A spike approach also lifts both
     * arms, and at the moment their *average* height peaks it looks like a
     * block. Public footage of hitters attacking a block pad passed this check
     * 8 times in 19. What gives the spike away is each hand's own peak. On a
     * block the two peak together, 0 to 67 ms apart. On a spike the hitting
     * hand peaks 0.3 to 3 s after the other, or the other hand has already
     * dropped by the time it does.
     *
     * The timing is read over every steady frame. Two other designs were tried
     * on the same footage and rejected:
     * - Requiring both arms to be seen on the peak frame let half of the spikes
     *   through, because a spiker's off arm is often hidden mid-swing.
     * - Reading each peak only from frames where that arm was seen refused real
     *   blocks, because arms held overhead lose visibility at full reach and
     *   the filter moved the peak elsewhere.
     * An arm never seen anywhere in the movement leaves the check standing
     * aside. The "other hand already dropped" test is applied only when that
     * hand is seen on the peak frame.
     */
    private fun handsPeakTogether(window: RepWindow): Boolean {
        val frames = AnalyzerSupport.steadyFrames(window.frames)
        if (Side.entries.any { side -> frames.none { AnalyzerSupport.armObserved(it, side) } }) return true
        val left = AnalyzerSupport.peakRise(frames, Side.Left) ?: return true
        val right = AnalyzerSupport.peakRise(frames, Side.Right) ?: return true
        if (abs(left.frame.timestampMs - right.frame.timestampMs) > MAX_PEAK_GAP_MS) return false
        val (higher, otherSide) = if (left.rise >= right.rise) left to Side.Right else right to Side.Left
        if (!AnalyzerSupport.armObserved(higher.frame, otherSide)) return true
        val otherAtPeak = AnalyzerSupport.wristRise(higher.frame, otherSide) ?: return true
        return otherAtPeak >= ONE_HAND_BELOW
    }

    override fun measure(cue: FormCue, window: RepWindow): Float? = when (cue.id) {
        CueId.BlockArmExtension -> window.frameFor(RepPhase.Contact)?.let { AnalyzerSupport.visibleElbowAngle(it) }
        CueId.BlockHandSeparation -> window.frameFor(RepPhase.Contact)?.let {
            Angles.distance(it.world(BodyPoint.LEFT_WRIST), it.world(BodyPoint.RIGHT_WRIST))
        }

        CueId.BlockPenetration -> window.frameFor(RepPhase.Contact)?.let { penetrationMetres(it) }
        CueId.BlockKneeLoad -> window.frameFor(RepPhase.Loading)?.let { AnalyzerSupport.visibleKneeAngle(it) }
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
        val wrists = AnalyzerSupport.visibleWrists(frame)
        return Angles.alongForward(frame, Angles.shoulderCentre(frame), wrists)
    }

    private companion object {
        /** Torso lengths. A blocker starts with the hands already up and ready. */
        const val MIN_EXCURSION = 0.30f

        /** Each hand must finish clear of the top of the head. */
        const val MIN_HAND_RISE = 0.60f

        /** A hand left below this while the other reached is a one-handed reach. */
        const val ONE_HAND_BELOW = 0.25f

        /**
         * Well under the extension cue's target, on purpose. This decides whether
         * it was a block at all; how straight the arms were is still the cue's job.
         */
        const val MIN_ELBOW_ANGLE = 120f

        /**
         * The most the two hands' peaks may be apart on one block. Blocks on
         * public footage peaked 0 to 67 ms apart and spikes 300 ms or more, so
         * this sits between them with room for a slower camera.
         */
        const val MAX_PEAK_GAP_MS = 250L

        /** The least the knees bend before a block, unless the hips left the floor. */
        const val MIN_KNEE_LOAD_DEGREES = 8f

        /** Torso lengths the hips rise in even a small jump. */
        const val MIN_HIP_LIFT = 0.10f
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
