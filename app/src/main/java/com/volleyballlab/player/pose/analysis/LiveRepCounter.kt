package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.pose.model.PoseFrame

/**
 * Counts repetitions during capture, for the live rep badge on the camera screen.
 *
 * ## Why this exists alongside [RepSegmenter]
 *
 * The capstone document is contradictory about whether analysis is live or
 * post-hoc, and the resolution taken here is that it is both, at two different
 * levels of rigour:
 *
 * - **This class** runs on every frame, in real time, and only ever answers
 *   "was that another one". It cannot see the future and has no idea what the
 *   session's full range of motion will turn out to be, so it adapts as it goes.
 *   Cheap and immediate.
 * - **[RepSegmenter]** runs once when recording stops, over every frame, and
 *   produces the reps that actually get scored.
 *
 * They can disagree by a rep, most often on the first one or two before the
 * adaptive range here has settled. That is a known and accepted difference, not
 * a defect. The results screen always reports the segmenter's count.
 *
 * ## The adaptive range, and its floor
 *
 * Thresholds follow the athlete: the observed extremes are tracked with a slow
 * decay back toward the current value, so the counter settles on however big
 * this person's reps are. The decay matters - without it, one outlier frame
 * would stretch the range permanently and every later rep would fall below the
 * activation threshold and go uncounted.
 *
 * What the range may not do is shrink to nothing. Left purely relative, a
 * motionless athlete's range decays to the width of the tracker's jitter, the
 * thresholds follow it down, and the jitter then crosses them: reps counted
 * while standing still. So the range is never taken to be smaller than
 * [SkillAnalyzer.minExcursion], the real-world size of the skill's action.
 *
 * ## Shape is not enough
 *
 * A curve of the right size still only says that *something* moved. Every
 * candidate is therefore put to [SkillAnalyzer.verdict] before it is counted,
 * which is where a knee bend stops being a forearm pass. A candidate that is
 * turned away leaves its reason in [lastRejection] so the coach can say it.
 *
 * Not thread-safe. It is driven from a single collector on the main dispatcher.
 */
class LiveRepCounter(
    private val analyzer: SkillAnalyzer,
    private val config: Config = Config(),
) {

    data class Config(
        val activationFraction: Float = 0.40f,
        val releaseFraction: Float = 0.22f,
        val minRepDurationMs: Long = 350,
        /**
         * How fast the tracked extremes relax toward the current signal, per
         * frame. Roughly a two-second memory at 30 fps.
         */
        val rangeDecay: Float = 0.015f,
        val minSignalRange: Float = 1e-3f,
        /** Frames to observe before counting anything, while the range settles. */
        val warmUpFrames: Int = 15,
        val maxRepDurationMs: Long = 6_000,
        val maxFrameGapMs: Long = 500,
    )

    var count: Int = 0
        private set

    var lastCompletedWindow: RepWindow? = null
        private set

    /**
     * Why the movement that ended on the latest frame was not counted, or null.
     * Set for that one frame only, like [lastCompletedWindow].
     */
    var lastRejection: MovementFault? = null
        private set

    /** Contact is confirmed only after the signal returns to rest. */
    val currentPhase: RepPhase? get() = if (isInRep) null else RepPhase.Preparation

    val partialWindow: RepWindow? get() = activeFrames.firstOrNull()?.let { first ->
        RepWindow(count, activeFrames.toList(), mapOf(RepPhase.Preparation to first), Angles.hipCentreImage(first).y)
    }

    private var observedLow = Float.NaN
    private var observedHigh = Float.NaN
    private var isInRep = false
    private var repStartMs = 0L
    private var framesSeen = 0
    private var lastTimestampMs: Long? = null
    private val preparationFrames = ArrayDeque<PoseFrame>()
    private val activeFrames = mutableListOf<PoseFrame>()

    fun reset() {
        count = 0
        resetSegment()
    }

    /** Abandons incomplete movement while keeping completed session reps. */
    fun resetSegment() {
        observedLow = Float.NaN
        observedHigh = Float.NaN
        isInRep = false
        repStartMs = 0L
        framesSeen = 0
        lastTimestampMs = null
        lastCompletedWindow = null
        lastRejection = null
        preparationFrames.clear()
        activeFrames.clear()
    }

    fun abandon() = resetSegment()

    /**
     * Feeds one frame in.
     *
     * @return true exactly on the frame where a repetition completes, so the
     *   caller can drive a one-shot animation without having to diff [count].
     */
    fun onFrame(frame: PoseFrame): Boolean {
        val previous = lastTimestampMs
        if (previous != null && (frame.timestampMs <= previous || frame.timestampMs - previous > config.maxFrameGapMs)) resetSegment()
        lastTimestampMs = frame.timestampMs
        lastCompletedWindow = null
        lastRejection = null
        val signal = analyzer.segmentationSignal(frame)?.takeIf { it.isFinite() }
        if (signal == null) { resetSegment(); return false }
        if (isInRep) activeFrames.add(frame) else {
            preparationFrames.addLast(frame)
            while (preparationFrames.size > 1 && frame.timestampMs - preparationFrames.first().timestampMs > 500L) preparationFrames.removeFirst()
        }
        framesSeen++

        if (observedLow.isNaN()) {
            observedLow = signal
            observedHigh = signal
            return false
        }

        // Contract slowly: both bounds creep toward the current value, so a range
        // stretched by one freak frame recovers instead of poisoning the session.
        observedLow += (signal - observedLow) * config.rangeDecay
        observedHigh += (signal - observedHigh) * config.rangeDecay
        // Expand instantly: a genuine new extreme is real information and should
        // widen the range on the frame it appears, not fade in over two seconds.
        if (signal < observedLow) observedLow = signal
        if (signal > observedHigh) observedHigh = signal

        if (framesSeen < config.warmUpFrames) return false

        // Never scale the thresholds below the size of the skill's own action.
        val range = maxOf(observedHigh - observedLow, analyzer.minExcursion)
        if (range < config.minSignalRange) return false

        val activate = observedLow + config.activationFraction * range
        val release = observedLow + config.releaseFraction * range

        if (!isInRep) {
            if (signal >= activate) {
                isInRep = true
                repStartMs = frame.timestampMs
                activeFrames.clear()
                activeFrames.addAll(preparationFrames)
                preparationFrames.clear()
            }
            return false
        }

        if (frame.timestampMs - repStartMs > config.maxRepDurationMs) {
            resetSegment()
            return false
        }

        if (signal <= release) {
            isInRep = false
            val duration = frame.timestampMs - repStartMs
            if (duration >= config.minRepDurationMs) {
                val window = buildWindow()
                activeFrames.clear()
                preparationFrames.addLast(frame)
                if (window == null) return false
                return when (val verdict = analyzer.verdict(window)) {
                    MovementVerdict.Counted -> {
                        lastCompletedWindow = window
                        count++
                        true
                    }
                    is MovementVerdict.NotCounted -> {
                        lastRejection = verdict.fault
                        false
                    }
                    MovementVerdict.TooSmall -> false
                }
            }
            activeFrames.clear()
            preparationFrames.addLast(frame)
        }
        return false
    }

    private fun buildWindow(): RepWindow? {
        if (activeFrames.size < 3) return null
        val signals = activeFrames.map { analyzer.segmentationSignal(it) ?: return null }
        val contactIndex = signals.indices.maxByOrNull { signals[it] } ?: return null
        if (contactIndex == 0 || contactIndex == activeFrames.lastIndex) return null
        val loadingIndex = (0..contactIndex).minByOrNull { signals[it] } ?: 0
        val loading = analyzer.loadingFrame(activeFrames.subList(0, contactIndex + 1))
            ?: activeFrames[loadingIndex]
        val first = activeFrames.first()
        return RepWindow(
            index = count,
            frames = activeFrames.toList(),
            phaseFrames = mapOf(
                RepPhase.Preparation to first,
                RepPhase.Loading to loading,
                RepPhase.Contact to activeFrames[contactIndex],
                RepPhase.FollowThrough to activeFrames.last(),
            ),
            baselineHipImageY = Angles.hipCentreImage(first).y,
        )
    }
}
