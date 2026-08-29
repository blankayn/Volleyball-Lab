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
 *    plain min and max, so one wild frame cannot rescale everything.
 * 3. Walk the curve with a two-threshold state machine and pick out the phases.
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
    )

    private data class Sample(val frame: PoseFrame, val signal: Float)

    fun segment(frames: List<PoseFrame>, analyzer: SkillAnalyzer): List<RepWindow> {
        // Frames where the signal could not be computed are dropped rather than
        // zero-filled. They are frames where tracking failed, and a zero would be
        // a synthetic trough that could split one rep into two.
        val samples = frames.mapNotNull { frame ->
            analyzer.segmentationSignal(frame)?.let { Sample(frame, it) }
        }
        if (samples.size < config.smoothingWindow * 2) return emptyList()

        val smoothed = smooth(samples.map { it.signal })
        val low = smoothed.percentile(0.05f)
        val high = smoothed.percentile(0.95f)
        val range = high - low
        if (range < config.minSignalRange) return emptyList()

        val activate = low + config.activationFraction * range
        val release = low + config.releaseFraction * range

        val windows = mutableListOf<RepWindow>()
        var repStart = -1
        var index = 0
        while (index < smoothed.size) {
            val value = smoothed[index]
            if (repStart < 0) {
                if (value >= activate) {
                    // Walk backwards to where the signal was last at rest. The
                    // rep began when the athlete started moving, not when the
                    // signal happened to cross an arbitrary threshold.
                    repStart = index
                    while (repStart > 0 && smoothed[repStart - 1] > release) repStart--
                }
            } else {
                val elapsed = samples[index].frame.timestampMs - samples[repStart].frame.timestampMs
                if (elapsed > config.maxRepDurationMs) {
                    repStart = -1
                } else if (value <= release) {
                    buildWindow(samples, smoothed, repStart, index, windows.size)
                        ?.let(windows::add)
                    repStart = -1
                }
            }
            index++
        }

        // A rep still open when the recording stopped is discarded. Its contact
        // may never have happened, and scoring a half-rep would quietly drag the
        // session average down.
        return windows
    }

    private fun buildWindow(
        samples: List<Sample>,
        smoothed: List<Float>,
        startIndex: Int,
        endIndex: Int,
        repIndex: Int,
    ): RepWindow? {
        val duration = samples[endIndex].frame.timestampMs - samples[startIndex].frame.timestampMs
        if (duration < config.minRepDurationMs) return null

        val slice = samples.subList(startIndex, endIndex + 1)

        // Contact is the peak of the signal, by definition of what the analyzers
        // are asked to return.
        var contactIndex = startIndex
        for (i in startIndex..endIndex) {
            if (smoothed[i] > smoothed[contactIndex]) contactIndex = i
        }

        // Loading is the deepest point before contact: the bottom of the squat,
        // the cocked arm. If contact lands on the very first frame there is no
        // run-up to search, so preparation stands in for it.
        var loadingIndex = startIndex
        for (i in startIndex..contactIndex) {
            if (smoothed[i] < smoothed[loadingIndex]) loadingIndex = i
        }

        val phaseFrames = mapOf(
            RepPhase.Preparation to samples[startIndex].frame,
            RepPhase.Loading to samples[loadingIndex].frame,
            RepPhase.Contact to samples[contactIndex].frame,
            RepPhase.FollowThrough to samples[endIndex].frame,
        )

        return RepWindow(
            index = repIndex,
            frames = slice.map { it.frame },
            phaseFrames = phaseFrames,
            baselineHipImageY = Angles.hipCentreImage(samples[startIndex].frame).y,
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
