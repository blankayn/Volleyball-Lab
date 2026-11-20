package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.pose.model.PoseFrame
import kotlin.math.roundToInt

/**
 * Cuts a continuous capture into individual repetitions.
 *
 * ## Offline, and why that is the right call
 *
 * This runs once, after recording stops, over the whole frame list. Segmenting
 * offline is strictly more accurate than doing it live, because it can normalise
 * against the session's full observed range and can look forward as well as back
 * when placing a boundary. The live rep count the athlete sees during capture
 * comes from [LiveRepCounter], which is a deliberately cheaper approximation.
 *
 * The two can disagree by a rep, and that is expected rather than a bug. The
 * results screen reports this segmenter's count, because that is the one the
 * scores were computed from.
 *
 * ## How a rep is found
 *
 * The analyzer supplies a per-frame scalar that peaks at contact. This class
 * never interprets it - it does not know a serve from a block - it only looks at
 * the curve's shape:
 *
 * 1. Smooth the signal, because raw landmark output is noisy enough that a
 *    single frame of tracking wobble looks like a peak.
 * 2. Establish the session's own range from robust percentiles rather than the
 *    plain min and max, so one wild frame cannot rescale everything - and never
 *    let that range fall below [SkillAnalyzer.minExcursion], the real size of
 *    the skill's action, or a quiet recording is rescaled until its noise is
 *    tall enough to count.
 * 3. Walk the curve with a two-threshold state machine and pick out the phases.
 *    A rep closes when it falls to the session's release line or, if that line
 *    would never close it in time, back near the level it rose from (see
 *    [floorRelease]).
 * 4. Put each candidate to [SkillAnalyzer.verdict] and keep only the ones that
 *    are the skill. The live counter applies the same test, so the saved
 *    session cannot contain a rep the camera screen refused.
 */
class RepSegmenter(private val config: Config = Config()) {

    data class Config(
        /** Frames in the centred moving average. Odd values keep it symmetric. */
        val smoothingWindow: Int = 5,
        /** Fraction of the signal range at which a rep is considered to have begun. */
        val activationFraction: Float = 0.35f,
        /**
         * Fraction at which it is considered to have ended.
         *
         * Lower than [activationFraction] on purpose. That gap is hysteresis: with
         * a single threshold, a signal hovering right at it flickers across the
         * boundary and produces a burst of phantom half-reps. Requiring the
         * signal to fall well back down before the rep can close makes the count
         * stable.
         */
        val releaseFraction: Float = 0.18f,
        /** Anything faster than this is tracking noise, not a repetition. */
        val minRepDurationMs: Long = 350,
        /** Anything slower is the athlete pausing mid-action; the rep is abandoned. */
        val maxRepDurationMs: Long = 6_000,
        /**
         * Minimum spread the signal must show before any rep is reported.
         *
         * Guards the degenerate case: an athlete who sets up and never moves
         * produces a flat signal whose percentile range is near zero, and
         * thresholds derived from it would sit inside the noise floor and emit
         * dozens of reps from nothing.
         */
        val minSignalRange: Float = 1e-3f,
        /** Never stitch movement across lost tracking or a camera timestamp reset. */
        val maxFrameGapMs: Long = 500L,
        /**
         * How long before a rep's first rise its window opens. Matches the live
         * counter's preparation buffer, so both judge the same movement.
         */
        val leadInMs: Long = 500L,
        /**
         * How much of a push's own climb the signal must give back before
         * [peakRelease] ends the rep. A held reach wobbles (a side-on wrist drifts in
         * and out of view) by a quarter to a third of the climb into it while the
         * elbows stay straight; hands coming back down between pushes give back
         * more than half.
         */
        val peakReleaseFraction: Float = 0.5f,
        /**
         * How far back from a peak the push to it is taken to have begun: its
         * lowest point in this time. Long enough to reach the start of a full arm
         * swing (about 0.8 s from hanging to overhead), short enough not to reach
         * past a ready position held before a push.
         */
        val pushClimbMs: Long = 1_000L,
        /**
         * The least time between two peaks that [peakRelease] splits into two reps.
         * Closer peaks are one movement: a serve's toss and hit, a spike's cocked
         * arm and swing, a wobble at the top of a set (0.3 to 0.7 s apart in public
         * coaching clips). Repeated pushes from a ready position come 1.5 s apart
         * or more, since each needs the hands back down, a load and the push.
         */
        val minPushSpacingMs: Long = 1_000L,
    )

    private data class Sample(val frame: PoseFrame, val signal: Float)

    fun segment(frames: List<PoseFrame>, analyzer: SkillAnalyzer): List<RepWindow> {
        // Frames where the signal could not be computed are dropped rather than
        // zero-filled. They are frames where tracking failed, and a zero would be
        // a synthetic trough that could split one rep into two.
        val samples = frames.mapNotNull { frame ->
            analyzer.segmentationSignal(frame)?.takeIf(Float::isFinite)?.let { Sample(frame, it) }
        }
        val chunks = mutableListOf<MutableList<Sample>>()
        samples.forEach { sample ->
            val previous = chunks.lastOrNull()?.lastOrNull()
            if (previous == null || sample.frame.timestampMs <= previous.frame.timestampMs ||
                sample.frame.timestampMs - previous.frame.timestampMs > config.maxFrameGapMs) {
                chunks += mutableListOf(sample)
            } else chunks.last() += sample
        }
        return chunks.flatMap { segmentContinuous(it, analyzer) }
            .mapIndexed { index, window -> window.copy(index = index) }
    }

    private fun segmentContinuous(samples: List<Sample>, analyzer: SkillAnalyzer): List<RepWindow> {
        if (samples.size < config.smoothingWindow * 2) return emptyList()

        val smoothed = smooth(samples.map { it.signal })
        val low = smoothed.percentile(0.05f)
        val high = smoothed.percentile(0.95f)
        // Widened to the skill's own size rather than rejected outright: one real
        // rep in a long, still recording has a percentile range near zero and
        // must still be found, while pure noise must have nothing to cross.
        val range = maxOf(high - low, analyzer.minExcursion)
        if (range < config.minSignalRange) return emptyList()

        val activate = low + config.activationFraction * range
        val release = low + config.releaseFraction * range
        // The least a rep must climb above its own floor before it can close on
        // that floor: the same hysteresis band that separates the two thresholds.
        val minRise = (config.activationFraction - config.releaseFraction) * range
        // For each sample, the first one at or after it that is back at or below
        // the release line, and the first that crosses up through the activation
        // line, or -1. Offline, so a rep can know in advance whether the session
        // line will close it before the next movement begins.
        val nextRelease = IntArray(smoothed.size + 1).also { next ->
            next[smoothed.size] = -1
            for (i in smoothed.indices.reversed()) next[i] = if (smoothed[i] <= release) i else next[i + 1]
        }
        val nextRise = IntArray(smoothed.size + 1).also { next ->
            next[smoothed.size] = -1
            for (i in smoothed.indices.reversed()) {
                next[i] = if (i > 0 && smoothed[i - 1] < activate && smoothed[i] >= activate) i else next[i + 1]
            }
        }
        fun timeMs(i: Int) = samples[i].frame.timestampMs

        val windows = mutableListOf<RepWindow>()
        var repStart = -1
        // One past the previous candidate, so a lead-in never reaches into it.
        var earliestStart = 0

        // The trough a rise to [peak] began from: walk back while the signal keeps
        // falling (stopping at the first flat or rising step, so a flat rest is not
        // crossed into whatever came before it), then take the lowest point in the
        // lead-in before that, which rides over a wobble partway up the rise.
        fun floorBefore(peak: Int): Int {
            var trough = peak
            while (trough > earliestStart && smoothed[trough - 1] < smoothed[trough]) trough--
            var floor = trough
            var k = trough
            while (k > earliestStart && timeMs(trough) - timeMs(k - 1) <= config.leadInMs) {
                k--
                if (smoothed[k] < smoothed[floor]) floor = k
            }
            return floor
        }

        // Floor mode (see [floorRelease]): the floor and peak the rep is measured between.
        var floorMode = false
        var repFloor = 0f
        var repPeak = 0f
        // Where the climb to the current peak began. Equal to the floor unless the
        // peak is a push from a higher rest the rep has not re-anchored on.
        var peakBase = 0f
        var peakIndex = -1
        // The peak the split look-ahead last ran for, so it runs once per peak.
        var checkedPeak = Float.NaN

        fun close(endIndex: Int) {
            buildWindow(samples, smoothed, repStart, endIndex, windows.size, analyzer, earliestStart)
                ?.takeIf { analyzer.verdict(it) == MovementVerdict.Counted }
                ?.let(windows::add)
            earliestStart = endIndex + 1
            repStart = -1
        }

        // The rules that end the open rep where it is.
        fun closesHere(value: Float) =
            value <= release || (floorMode && floorRelease(value, repFloor, repPeak, minRise))

        // See [peakRelease]: the valley between the push that has come down at [from]
        // and the next push, when that next push starts before [closesHere] would end
        // the rep and peaks at least [Config.minPushSpacingMs] after this one;
        // otherwise -1, and the rep closes by the rules it always had.
        fun valleyBeforeNextPush(from: Int): Int {
            var low = from
            var k = from + 1
            while (k < smoothed.size) {
                val value = smoothed[k]
                if (closesHere(value)) return -1
                if (value < smoothed[low]) low = k else if (value - smoothed[low] >= minRise) break
                k++
            }
            if (k >= smoothed.size) return -1
            // The next push's own peak: its highest point before it comes back down
            // by the band, or the rep would close.
            var top = k
            while (k < smoothed.size && !closesHere(smoothed[k]) && smoothed[top] - smoothed[k] < minRise) {
                if (smoothed[k] > smoothed[top]) top = k
                k++
            }
            return if (timeMs(top) - timeMs(peakIndex) >= config.minPushSpacingMs) low else -1
        }

        var index = 0
        while (index < smoothed.size) {
            val value = smoothed[index]
            if (repStart < 0) {
                if (value >= activate) {
                    // Walk backwards to where the signal was last at rest. The
                    // rep began when the athlete started moving, not when the
                    // signal happened to cross an arbitrary threshold. Never past
                    // the previous candidate, or two reps would share frames.
                    repStart = index
                    while (repStart > earliestStart && smoothed[repStart - 1] > release) repStart--
                    val closes = nextRelease[index]
                    val nextMovement = nextRise[index + 1]
                    floorMode = closes < 0 || timeMs(closes) - timeMs(repStart) > config.maxRepDurationMs ||
                        (nextMovement >= 0 && nextMovement < closes)
                    if (floorMode) {
                        // The session line would not close this rep in time, or only
                        // after the next movement has begun and merged into it.
                        // Measure it from the trough this rise began at instead: the
                        // rest it actually rose from.
                        repStart = floorBefore(index)
                    }
                    repFloor = smoothed[repStart]
                    repPeak = value
                    peakIndex = index
                    peakBase = repFloor
                    checkedPeak = Float.NaN
                }
            } else if (timeMs(index) - timeMs(repStart) > config.maxRepDurationMs) {
                repStart = -1
            } else {
                if (value > repPeak) {
                    repPeak = value
                    peakIndex = index
                    var base = index
                    while (base > earliestStart && timeMs(index) - timeMs(base - 1) <= config.pushClimbMs) base--
                    peakBase = (base..index).minOf { smoothed[it] }
                    val trough = floorBefore(index)
                    // A new peak may belong to a fresh rise from a later, higher
                    // rest (the first jump after setting up, say). Re-anchor on that
                    // rise's trough, but only when it is a real movement: a creep at
                    // the top of the reach would otherwise lift the floor onto the
                    // plateau and the rep could never close.
                    if (floorMode && repPeak - smoothed[trough] >= minRise) {
                        repStart = trough
                        repFloor = smoothed[trough]
                    }
                }
                if (closesHere(value)) {
                    close(index)
                } else if (analyzer.repeatsFromRaisedHands && repPeak != checkedPeak &&
                    peakRelease(value, repFloor, repPeak, peakBase, minRise)) {
                    checkedPeak = repPeak
                    val valley = valleyBeforeNextPush(index)
                    // The valley opens the next push: it is that push's rest and load.
                    if (valley > 0 && timeMs(valley - 1) - timeMs(repStart) <= config.maxRepDurationMs) {
                        close(valley - 1)
                        index = valley - 1
                    }
                }
            }
            index++
        }

        // A rep still open when the recording stopped is discarded. Its contact
        // may never have happened, and scoring a half-rep would quietly drag the
        // session average down.
        return windows
    }

    /**
     * Whether a rep the session's release line cannot close has finished anyway,
     * by coming back down to the level it rose from.
     *
     * The session thresholds come from its 5th and 95th percentiles. When the
     * recording opens and closes with the athlete standing arms-down (setting up,
     * then walking to the phone), those seconds set the low percentile, but a
     * blocker rests with hands at the chest between jumps, well above it. With only
     * the session line, no rep ever releases: each runs past
     * [Config.maxRepDurationMs] and is abandoned, and the saved session holds zero
     * reps while the live counter, whose range adapts as it goes, showed many.
     *
     * Floor mode is used only for a rep the session line would not close within
     * [Config.maxRepDurationMs] (one the segmenter used to abandon), or would close
     * only after the signal had risen back through the activation line - a second
     * movement it used to merge into the first. A rep the session line closes
     * before the next movement begins segments exactly as before. A floor-mode rep
     * closes once it has fallen back to within [Config.releaseFraction] of its own rise
     * (the same fraction, applied to the rep instead of the session), provided it
     * rose at least [minRise], the hysteresis band, above its floor.
     */
    private fun floorRelease(value: Float, floor: Float, peak: Float, minRise: Float): Boolean {
        val rise = peak - floor
        return rise >= minRise && value <= floor + config.releaseFraction * rise
    }

    /**
     * Whether a rep has come back down from its own peak by [minRise], the band it
     * had to rise by: the other half of the same hysteresis. When it has, and
     * another push begins before the rep's usual close rules would end it, the rep
     * is split at the valley between the two pushes.
     *
     * The usual rules wait for the signal to return to the session's release line
     * or near the level the rep rose from, and some reps rise from a rest the hands
     * never go back to. An athlete who lifts the hands into a ready position once,
     * then pushes up from there again and again (a blocker with hands at the head,
     * a setter with hands at the forehead), rises the first time from arms hanging
     * and afterwards only from the ready position. Each push comes down to the
     * ready position, never to the arms-down floor, so the pushes ran together:
     * into one movement longer than [Config.maxRepDurationMs], which was discarded,
     * or into one rep scored at only its highest push.
     *
     * A rep that comes down and is closed by the usual rules before any next push
     * keeps exactly the window it had: the split never moves a close later or
     * earlier, it only separates pushes that used to merge.
     *
     * The fall must also give back [Config.peakReleaseFraction] of the climb from
     * [base], where the push to this peak began. A block held overhead whose wrist
     * signal sags and recovers (seen side-on, elbows straight throughout) is one
     * rep, not two.
     */
    private fun peakRelease(value: Float, floor: Float, peak: Float, base: Float, minRise: Float): Boolean {
        val fall = peak - value
        return peak - floor >= minRise && fall >= minRise && fall >= config.peakReleaseFraction * (peak - base)
    }

    private fun buildWindow(
        samples: List<Sample>,
        smoothed: List<Float>,
        startIndex: Int,
        endIndex: Int,
        repIndex: Int,
        analyzer: SkillAnalyzer,
        earliestStart: Int,
    ): RepWindow? {
        val duration = samples[endIndex].frame.timestampMs - samples[startIndex].frame.timestampMs
        if (duration < config.minRepDurationMs) return null

        // Contact is the peak of the signal, by definition of what the analyzers
        // are asked to return.
        var contactIndex = startIndex
        for (i in startIndex..endIndex) {
            if (smoothed[i] > smoothed[contactIndex]) contactIndex = i
        }

        // Recording or tracking can resume after the action has already peaked.
        // A falling tail has no observed preparation; do not score it as a full rep.
        if (contactIndex == startIndex || contactIndex == endIndex) return null

        // The window opens a short lead-in before the first rise, exactly as the
        // live counter keeps half a second of preparation. Two things live in
        // that lead-in: the stance the movement really began from, which the
        // movement check measures travel against, and the knee load of a skill
        // segmented on its hands, which comes just before the hands move. A
        // window cut at the threshold instead would see a shorter movement than
        // the live counter did and could refuse a rep the athlete was credited.
        var windowStart = startIndex
        while (windowStart > earliestStart &&
            samples[startIndex].frame.timestampMs - samples[windowStart - 1].frame.timestampMs <= config.leadInMs) {
            windowStart--
        }

        // Loading is the deepest point before contact: the bottom of the squat,
        // the cocked arm.
        var loadingIndex = startIndex
        for (i in windowStart..contactIndex) {
            if (smoothed[i] < smoothed[loadingIndex]) loadingIndex = i
        }
        val loading = analyzer.loadingFrame(samples.subList(windowStart, contactIndex + 1).map { it.frame })
            ?: samples[loadingIndex].frame

        val phaseFrames = mapOf(
            RepPhase.Preparation to samples[windowStart].frame,
            RepPhase.Loading to loading,
            RepPhase.Contact to samples[contactIndex].frame,
            RepPhase.FollowThrough to samples[endIndex].frame,
        )

        return RepWindow(
            index = repIndex,
            frames = samples.subList(windowStart, endIndex + 1).map { it.frame },
            phaseFrames = phaseFrames,
            baselineHipImageY = Angles.hipCentreImage(samples[windowStart].frame).y,
        )
    }

    /**
     * Centred moving average.
     *
     * Centred rather than trailing because this is offline, so there is no reason
     * to accept the half-window phase lag a trailing filter introduces. That lag
     * would shift every detected contact slightly late, and contact is the frame
     * most cues are measured on.
     */
    private fun smooth(values: List<Float>): List<Float> {
        val half = config.smoothingWindow / 2
        return values.indices.map { i ->
            val from = (i - half).coerceAtLeast(0)
            val to = (i + half).coerceAtMost(values.lastIndex)
            var sum = 0f
            for (j in from..to) sum += values[j]
            sum / (to - from + 1)
        }
    }

    private fun List<Float>.percentile(fraction: Float): Float {
        val sorted = sorted()
        val index = ((sorted.size - 1) * fraction).roundToInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }
}
