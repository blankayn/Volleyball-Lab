package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.pose.model.PoseFrame

/**
 * Prototype: is this serve or spike's hitting elbow clearly lower than on the
 * same athlete's best earlier reps in this view?
 *
 * ## Why relative, not a fixed threshold
 *
 * On 87 labelled coaching clips (outputs/rule-evaluation-20261118) no absolute
 * measure separated dropped-elbow reps from correct ones: people, cameras and
 * viewing angles differ more than the fault does. Within one video - one
 * person, one camera - the faulty reps' peak elbow was lower in 9 of 11 videos.
 * The app always watches one athlete on one fixed phone, so the athlete's own
 * best reps are the reference.
 *
 * ## The measurement
 *
 * The highest the hitting elbow gets before contact, as torso lengths above the
 * shoulder line in the image (the same normalisation as [AnalyzerSupport.wristRise]).
 * The hitting side is taken at contact exactly as the arm cues take it
 * ([AngleCuePolicy.measuredSide]); a rep whose side or elbow cannot be trusted
 * is [Check.Unmeasured] and never changes the baseline.
 *
 * ## The decision
 *
 * A rep is [Check.Dropped] when its peak is more than [margin] below the best
 * earlier measured rep. Nothing is judged until [minBaselineReps] reps have
 * been measured, and a drop is announced at most once every [cooldownReps]
 * reps; the caller reports a spoken drop with [announced], so a drop that lost
 * its turn to another correction can still be said on the next low rep. In the
 * clip simulation, a 0.25 margin flagged 8 of 22 faulty reps and
 * 1 of 24 correct ones: it misses many drops but is rarely wrong when it
 * speaks. That margin was chosen on the same small set, so it is a starting
 * value for device testing, not a validated threshold.
 *
 * Image heights change with the view, so the baseline belongs to one view:
 * [observe] starts again whenever its view key changes.
 */
class PersonalElbowBaseline(
    private val margin: Float = MARGIN_TORSO,
    private val minBaselineReps: Int = MIN_BASELINE_REPS,
    private val cooldownReps: Int = COOLDOWN_REPS,
) {
    sealed interface Check {
        /** The hitting side or elbow was not reliable enough to measure. */
        data object Unmeasured : Check

        /** Measured, but there are not yet enough earlier reps to compare against. */
        data class WarmingUp(val peak: Float) : Check

        /** Within [margin] of the athlete's best rep, or above it. */
        data class Held(val peak: Float, val best: Float) : Check

        /**
         * More than [margin] below the best rep. [announce] is false while a
         * spoken drop is still cooling down, so a run of low reps is coached once.
         */
        data class Dropped(val peak: Float, val best: Float, val announce: Boolean) : Check {
            val dropTorsoLengths: Float get() = best - peak
        }
    }

    private val peaks = mutableListOf<Float>()
    private var viewKey: Any? = null
    private var repsSinceAnnouncement = Int.MAX_VALUE

    fun reset() {
        peaks.clear()
        viewKey = null
        repsSinceAnnouncement = Int.MAX_VALUE
    }

    fun observe(window: RepWindow, view: Any? = null): Check {
        if (view != viewKey) {
            reset()
            viewKey = view
        }
        val peak = peakElbowRise(window) ?: return Check.Unmeasured
        val best = peaks.maxOrNull()
        val enough = peaks.size >= minBaselineReps
        peaks += peak
        if (repsSinceAnnouncement != Int.MAX_VALUE) repsSinceAnnouncement++
        if (!enough || best == null) return Check.WarmingUp(peak)
        if (peak >= best - margin) return Check.Held(peak, best)
        return Check.Dropped(peak, best, announce = repsSinceAnnouncement >= cooldownReps)
    }

    /** The last [Check.Dropped] was actually said; the cooldown starts from this rep. */
    fun announced() {
        repsSinceAnnouncement = 0
    }

    companion object {
        /** About twelve centimetres on an adult torso. */
        const val MARGIN_TORSO = 0.25f
        const val MIN_BASELINE_REPS = 2
        const val COOLDOWN_REPS = 3

        /** The highest the hitting elbow got before contact, or null when it cannot be trusted. */
        fun peakElbowRise(window: RepWindow): Float? {
            val contact = window.frameFor(RepPhase.Contact) ?: return null
            val side = AngleCuePolicy.measuredSide(CueId.ServeContactArmExtension, contact) ?: return null
            return window.frames.asSequence()
                .takeWhile { it.timestampMs <= contact.timestampMs }
                .mapNotNull { elbowRise(it, side) }
                .maxOrNull()
        }

        fun elbowRise(frame: PoseFrame, side: Side): Float? {
            val elbow = AnalyzerSupport.elbow(side)
            if (!AngleCuePolicy.isReliable(frame, elbow)) return null
            val torso = AnalyzerSupport.torsoImageLength(frame) ?: return null
            return ((Angles.shoulderCentreImage(frame).y - frame.image(elbow).y) / torso).takeIf { it.isFinite() }
        }
    }
}
