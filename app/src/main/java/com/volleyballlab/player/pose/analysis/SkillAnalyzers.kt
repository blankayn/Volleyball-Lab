package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CameraAngle
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
        return MovementVerdict.Counted
    }
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
        return platformFault(contact)?.let { MovementVerdict.NotCounted(it) } ?: MovementVerdict.Counted
    }

    /**
     * What is wrong with the forearm platform at the bottom of the bend, or null.
     *
     * Each test reads only what the camera saw. From behind, a correct platform
     * is hidden by the athlete's own back, so hidden hands are accepted there
     * and nowhere else; hands that *are* visible hanging apart are a fault from
     * any direction.
     */
    private fun platformFault(frame: PoseFrame): MovementFault? {
        val view = CameraOrientationDetector().detect(frame).angle
        val wrists = Side.entries.filter { AnalyzerSupport.observed(frame, AnalyzerSupport.wrist(it)) }
        if (wrists.isEmpty()) {
            return if (view == CameraAngle.Rear) null else MovementFault.ArmsNotVisible
        }
        if (wrists.any { side -> AnalyzerSupport.wristRise(frame, side)?.let { it > MAX_PLATFORM_RISE } == true }) {
            return MovementFault.PlatformTooHigh
        }
        if (wrists.size == 2) {
            val shoulders = Angles.distance(
                frame.world(BodyPoint.LEFT_SHOULDER), frame.world(BodyPoint.RIGHT_SHOULDER),
            )
            val gap = Angles.distance(frame.world(BodyPoint.LEFT_WRIST), frame.world(BodyPoint.RIGHT_WRIST))
            if (shoulders > MIN_SHOULDER_WIDTH_METRES && gap / shoulders > MAX_WRIST_GAP) {
                return MovementFault.JoinPlatform
            }
        }
        // Side-on, hands joined against the thighs look the same as a platform
        // to the gap test, so the hands must also be out in front of the hips.
        if (view == CameraAngle.Side) {
            val torso = AnalyzerSupport.torsoImageLength(frame) ?: return null
            val hipX = Angles.hipCentreImage(frame).x
            val forward = wrists.maxOf { abs(frame.image(AnalyzerSupport.wrist(it)).x - hipX) } / torso
            if (forward < MIN_FORWARD_REACH) return MovementFault.JoinPlatform
        }
        return null
    }

    override fun measure(cue: FormCue, window: RepWindow): Float? {
        val contact = window.frameFor(RepPhase.Contact) ?: return null
        return when (cue.id) {
            CueId.PassPlatformStraight -> meanElbowAngle(contact)
            CueId.PassPlatformHeight -> platformBelowHorizontal(contact)
            CueId.PassKneeFlexion -> meanKneeAngle(contact)
            CueId.PassTrunkAngle -> Angles.trunkForwardLean(contact)
            CueId.PassShouldersLevel -> Angles.shoulderTilt(contact)
            else -> null
        }
    }

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
        const val MIN_KNEE_DIP_DEGREES = 15f

        /** Wrist gap as a share of shoulder width. Hanging arms measure above 1. */
        const val MAX_WRIST_GAP = 0.60f

        /** Torso lengths above the shoulder line. A platform lives below it. */
        const val MAX_PLATFORM_RISE = 0.15f

        /** Torso lengths the hands must be ahead of the hips, seen from the side. */
        const val MIN_FORWARD_REACH = 0.25f
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
        if (rises.isEmpty()) return MovementVerdict.NotCounted(MovementFault.ArmsNotVisible)
        if (rises.size == 2 && rises.max() >= MIN_HAND_RISE && rises.max() - rises.min() > MAX_HAND_GAP) {
            return MovementVerdict.NotCounted(MovementFault.UseBothHands)
        }
        if (rises.min() < MIN_HAND_RISE) return MovementVerdict.NotCounted(MovementFault.HandsAboveForehead)
        // Only an elbow that was seen staying straight is a fault. One that could
        // not be measured on the way up is unknown, not wrong.
        val tightest = window.frames.asSequence()
            .takeWhile { it.timestampMs <= contact.timestampMs }
            .mapNotNull { AnalyzerSupport.observedElbowAngle(it) }
            .minOrNull()
        if (tightest != null && tightest > MAX_STRAIGHT_ELBOW) {
            return MovementVerdict.NotCounted(MovementFault.BendElbowsFirst)
        }
        return MovementVerdict.Counted
    }

    override fun measure(cue: FormCue, window: RepWindow): Float? = when (cue.id) {
        CueId.SetHandsAboveForehead -> window.frameFor(RepPhase.Contact)
            ?.let { handsAboveNoseMetres(it) }

        CueId.SetElbowFlexion -> cueFrame(cue, window)?.let { setElbowAngle(it) }
        CueId.SetExtensionFollowThrough -> cueFrame(cue, window)?.let(finishElbowAngle(window))
        // Measured during loading, not contact: the leg drive that powers a set
        // happens before the hands touch the ball, and by contact the athlete is
        // already extending.
        CueId.SetKneeFlexion -> window.frameFor(RepPhase.Loading)?.let { meanKneeAngle(it) }

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
        AnalyzerSupport.observedElbowAngle(frame) ?: meanElbowAngle(frame)

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
        val wrists = Angles.midpoint(
            frame.world(BodyPoint.LEFT_WRIST),
            frame.world(BodyPoint.RIGHT_WRIST),
        )
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

    override fun measure(cue: FormCue, window: RepWindow): Float? = when (cue.id) {
        CueId.SpikeApproachLoad -> window.frameFor(RepPhase.Loading)?.let { meanKneeAngle(it) }
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
        if (rises.isEmpty()) return MovementVerdict.NotCounted(MovementFault.ArmsNotVisible)
        if (rises.size == 2 && rises.max() >= MIN_HAND_RISE && rises.min() < ONE_HAND_BELOW) {
            return MovementVerdict.NotCounted(MovementFault.UseBothHands)
        }
        if (rises.min() < MIN_HAND_RISE) return MovementVerdict.NotCounted(MovementFault.ReachBothHandsHigh)
        val elbows = AnalyzerSupport.observedElbowAngle(contact)
        if (elbows != null && elbows < MIN_ELBOW_ANGLE) {
            return MovementVerdict.NotCounted(MovementFault.StraightenArms)
        }
        if (!handsPeakTogether(window)) return MovementVerdict.NotCounted(MovementFault.UseBothHands)
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
