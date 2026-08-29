package com.volleyballlab.player.feature.train.capture

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.di.PoseDetectorFactory
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.repository.SessionRepository
import com.volleyballlab.player.pose.PoseDetector
import com.volleyballlab.player.pose.PoseDetectorUnavailableException
import com.volleyballlab.player.pose.analysis.LiveRepCounter
import com.volleyballlab.player.pose.analysis.SessionScorer
import com.volleyballlab.player.pose.analysis.SkillAnalyzers
import com.volleyballlab.player.pose.model.PoseFrame
import com.volleyballlab.player.pose.quality.PoseQuality
import com.volleyballlab.player.pose.quality.PoseQualityValidator
import java.util.UUID
import kotlin.math.ceil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Where the capture screen is in its lifecycle.
 *
 * A sealed hierarchy rather than a bag of booleans. With flags, states like
 * "counting down while also analysing" are representable and someone eventually
 * produces one; here they simply do not typecheck.
 */
sealed interface CaptureUiState {

    /** Detector is being constructed. */
    data object Initialising : CaptureUiState

    /** Detector could not start. Terminal unless the user retries. */
    data class Unavailable(
        val reason: PoseDetectorUnavailableException.Reason,
        val canUseDemoDetector: Boolean,
    ) : CaptureUiState

    /**
     * Live, not recording. The athlete is getting into frame.
     *
     * @param steadyMs how long framing has been continuously acceptable, capped at
     *   [CaptureViewModel.AUTO_START_HOLD_MS]. Drives the hold-still ring and the
     *   hands-free start.
     * @param autoStartArmed whether holding still will begin the session on its
     *   own. Cleared when the athlete cancels a countdown and re-armed once
     *   framing is lost and regained, so cancelling is not immediately undone by
     *   the athlete standing exactly where they were.
     */
    data class Framing(
        val quality: PoseQuality,
        val steadyMs: Long = 0L,
        val autoStartArmed: Boolean = true,
    ) : CaptureUiState {

        /** Progress towards the hands-free start, `0f..1f`. */
        val holdProgress: Float
            get() = (steadyMs.toFloat() / CaptureViewModel.AUTO_START_HOLD_MS).coerceIn(0f, 1f)

        /** Whole seconds of steady framing still required, for the on-screen count. */
        val holdSecondsLeft: Int
            get() = ceil((CaptureViewModel.AUTO_START_HOLD_MS - steadyMs) / 1_000.0)
                .toInt()
                .coerceAtLeast(0)

        /** True when the hold gate is running and worth drawing. */
        val isHoldingStill: Boolean get() = autoStartArmed && quality.isAcceptable
    }

    /** Recording starts when this reaches zero. */
    data class CountingDown(val secondsLeft: Int) : CaptureUiState

    data class Recording(
        val elapsedMs: Long,
        val liveRepCount: Int,
        val quality: PoseQuality,
    ) : CaptureUiState

    /** Post-hoc scoring, after recording stops. */
    data object Analysing : CaptureUiState

    data class Finished(val sessionId: String, val repCount: Int) : CaptureUiState
}

/**
 * Owns the capture session: the detector, the framing gate, the recording buffer
 * and the handoff to scoring.
 *
 * ## The two-tier analysis, in one place
 *
 * This is where the resolution of conflict 1 in the capstone document actually
 * lives. The document is contradictory about whether analysis is live or
 * post-hoc, and the answer implemented here is both, at different fidelity:
 *
 * - **While recording**, [LiveRepCounter] runs on every frame and drives the rep
 *   badge, and [PoseQualityValidator] keeps reporting framing. Both are cheap
 *   enough to run at camera frame rate.
 * - **When recording stops**, [SessionScorer] runs over the whole buffer and
 *   produces the scores that get stored and shown.
 *
 * ## Why frames are buffered rather than scored as they arrive
 *
 * Rep segmentation needs the session's full range of motion to place thresholds,
 * and needs to look forward from a peak to find where the rep ends. Neither is
 * possible frame by frame. The buffer is bounded by [MAX_RECORDING_MS] so it
 * cannot grow without limit.
 *
 * ## Why recording can start without anyone touching the phone
 *
 * The athlete props the phone up and walks far enough away that their whole body
 * fits in shot - that is the only framing the quality gate accepts. From there
 * the Start button is out of reach, so requiring a tap makes the screen unusable
 * for the one workflow it exists for. Holding acceptable framing for
 * [AUTO_START_HOLD_MS] therefore begins the countdown by itself, and the button
 * remains for anyone who is standing next to the phone anyway.
 */
class CaptureViewModel(
    private val skill: Skill,
    private val drillId: String,
    private val detectorFactory: PoseDetectorFactory,
    private val demoDetectorFactory: PoseDetectorFactory,
    private val sessionRepository: SessionRepository,
    private val sessionScorer: SessionScorer,
    private val allowDemoDetector: Boolean,
) : ViewModel() {

    private val _uiState = MutableStateFlow<CaptureUiState>(CaptureUiState.Initialising)
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    /**
     * The latest pose, for the overlay only.
     *
     * Kept separate from [uiState] deliberately. This updates at camera frame
     * rate; folding it into the main state would recompose the entire control
     * layer thirty times a second to redraw a skeleton that lives in one Canvas.
     */
    private val _poseFrame = MutableStateFlow<PoseFrame?>(null)
    val poseFrame: StateFlow<PoseFrame?> = _poseFrame.asStateFlow()

    private val qualityValidator = PoseQualityValidator()
    private val repCounter = LiveRepCounter(SkillAnalyzers.forSkill(skill))

    private var detector: PoseDetector? = null
    private var collectJob: Job? = null
    private var timerJob: Job? = null
    private var countdownJob: Job? = null
    private var holdJob: Job? = null

    /**
     * Hold-gate bookkeeping. Only [runHoldGate] writes these; [onPose] contributes
     * the raw verdict and nothing else, so there is a single owner of the gate.
     */
    private var latestQuality: PoseQuality = PoseQuality.NotYetKnown
    private var steadyMs = 0L
    private var ticksSincePose = 0
    private var autoStartArmed = true

    private val recordedFrames = mutableListOf<PoseFrame>()
    private var recordingStartedAtEpochMs = 0L

    init {
        startDetector(useDemo = false)
    }

    private fun startDetector(useDemo: Boolean) {
        collectJob?.cancel()
        detector?.close()
        _uiState.value = CaptureUiState.Initialising

        val created = try {
            if (useDemo) demoDetectorFactory.create() else detectorFactory.create()
        } catch (unavailable: PoseDetectorUnavailableException) {
            _uiState.value = CaptureUiState.Unavailable(
                reason = unavailable.reason,
                canUseDemoDetector = allowDemoDetector,
            )
            return
        }

        detector = created
        repCounter.reset()
        enterFraming(armAutoStart = true)

        collectJob = viewModelScope.launch {
            created.poses.collect(::onPose)
        }
    }

    /** Called from the CameraX analyzer. Must stay cheap; it only forwards. */
    fun onCameraFrame(bitmap: Bitmap, timestampMs: Long, mirrored: Boolean) {
        detector?.submit(bitmap, timestampMs, mirrored)
    }

    private fun onPose(frame: PoseFrame) {
        _poseFrame.value = frame

        when (val state = _uiState.value) {
            // Only the verdict is recorded here. The framing state itself is
            // published by the hold gate on its own tick: two writers running at
            // two different rates would race over steadyMs, and republishing at
            // camera frame rate would recompose the guidance card thirty times a
            // second to change nothing.
            is CaptureUiState.Framing -> {
                latestQuality = qualityValidator.validate(frame)
                ticksSincePose = 0
            }

            is CaptureUiState.Recording -> {
                if (recordedFrames.size < MAX_RECORDED_FRAMES) recordedFrames += frame
                val countedRep = repCounter.onFrame(frame)
                _uiState.value = state.copy(
                    liveRepCount = repCounter.count,
                    // Framing is still reported while recording so the athlete can
                    // see they have drifted out of shot, but it no longer gates
                    // anything: interrupting a drill to re-frame would be worse
                    // than a few unusable reps.
                    quality = qualityValidator.validate(frame),
                )
                if (countedRep) onRepCounted()
            }

            else -> Unit
        }
    }

    /** Hook for rep feedback. Kept explicit so haptics or audio can be added here. */
    private fun onRepCounted() = Unit

    /**
     * Begins the countdown, then recording.
     *
     * Only permitted from [CaptureUiState.Framing] with an acceptable pose. The
     * gate is the whole reason the framing state exists: every score downstream
     * is computed from these landmarks, and a rep captured with the athlete's
     * feet out of frame yields a confident, precise, wrong number.
     */
    fun onStartRequested() {
        val framing = _uiState.value as? CaptureUiState.Framing ?: return
        if (!framing.quality.isAcceptable) return
        beginCountdown()
    }

    /**
     * Returns to framing and restarts the hold-still gate from zero.
     *
     * @param armAutoStart false after a cancelled countdown. See
     *   [CaptureUiState.Framing.autoStartArmed].
     */
    private fun enterFraming(armAutoStart: Boolean) {
        holdJob?.cancel()
        qualityValidator.reset()
        latestQuality = PoseQuality.NotYetKnown
        steadyMs = 0L
        // Starts stale: nothing has been seen yet, and the gate must not treat
        // that silence as a settled athlete.
        ticksSincePose = POSE_STALE_TICKS
        autoStartArmed = armAutoStart
        _uiState.value = CaptureUiState.Framing(
            quality = PoseQuality.NotYetKnown,
            steadyMs = 0L,
            autoStartArmed = armAutoStart,
        )
        holdJob = viewModelScope.launch { runHoldGate() }
    }

    /**
     * The hands-free start: fills while framing is acceptable, empties the moment
     * it is not, and begins the countdown when it is full.
     *
     * Time is counted in ticks rather than read from the clock. A stalled main
     * thread then makes the gate take marginally longer in wall time instead of
     * completing early, which is the right way round for something that starts a
     * recording on its own - and it makes the gate testable against virtual time.
     */
    private suspend fun runHoldGate() {
        while (true) {
            delay(HOLD_TICK_MS)
            if (_uiState.value !is CaptureUiState.Framing) return

            // The detector emits nothing at all when it finds no pose, rather than
            // an empty frame. Without this, the last acceptable verdict would sit
            // on screen and keep the gate filling after the athlete walked out of
            // shot - and then start recording an empty court.
            ticksSincePose++
            if (ticksSincePose >= POSE_STALE_TICKS) {
                qualityValidator.reset()
                latestQuality = PoseQuality.NotYetKnown
            }

            val quality = latestQuality
            if (quality.isAcceptable) {
                steadyMs = (steadyMs + HOLD_TICK_MS).coerceAtMost(AUTO_START_HOLD_MS)
            } else {
                steadyMs = 0L
                // Losing framing re-arms a gate the athlete disarmed by cancelling:
                // stepping out of shot and back in is how they ask for the
                // hands-free start again, and it needs no reachable control.
                autoStartArmed = true
            }

            _uiState.value = CaptureUiState.Framing(
                quality = quality,
                steadyMs = steadyMs,
                autoStartArmed = autoStartArmed,
            )

            if (autoStartArmed && quality.isAcceptable && steadyMs >= AUTO_START_HOLD_MS) {
                beginCountdown()
                return
            }
        }
    }

    private fun beginCountdown() {
        holdJob?.cancel()
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (second in COUNTDOWN_SECONDS downTo 1) {
                _uiState.value = CaptureUiState.CountingDown(second)
                delay(1_000)
            }
            beginRecording()
        }
    }

    private fun beginRecording() {
        recordedFrames.clear()
        repCounter.reset()
        recordingStartedAtEpochMs = System.currentTimeMillis()
        _uiState.value = CaptureUiState.Recording(
            elapsedMs = 0L,
            liveRepCount = 0,
            quality = PoseQuality.NotYetKnown,
        )

        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                delay(TIMER_TICK_MS)
                val elapsed = System.currentTimeMillis() - startedAt
                val state = _uiState.value as? CaptureUiState.Recording ?: break
                _uiState.value = state.copy(elapsedMs = elapsed)
                if (elapsed >= MAX_RECORDING_MS) {
                    onStopRequested()
                    break
                }
            }
        }
    }

    /** Stops recording and runs the post-hoc scoring pass. */
    fun onStopRequested() {
        if (_uiState.value !is CaptureUiState.Recording) return
        timerJob?.cancel()
        _uiState.value = CaptureUiState.Analysing

        viewModelScope.launch {
            val frames = recordedFrames.toList()
            val sessionId = UUID.randomUUID().toString()
            val result = sessionScorer.score(
                sessionId = sessionId,
                skill = skill,
                drillId = drillId,
                startedAtEpochMs = recordingStartedAtEpochMs,
                frames = frames,
            )
            // Saved even when no reps were detected. An empty session is a real
            // outcome the athlete should see recorded, and discarding it silently
            // would look like the app lost their work.
            sessionRepository.save(result)
            recordedFrames.clear()
            _uiState.value = CaptureUiState.Finished(sessionId, result.repCount)
        }
    }

    /** Abandons the countdown and returns to framing. */
    fun onCancelCountdown() {
        countdownJob?.cancel()
        // Disarmed, because the athlete is still standing in perfect framing and
        // the gate would otherwise refill and restart the countdown they just
        // cancelled - a cancel button that undoes itself in three seconds.
        enterFraming(armAutoStart = false)
    }

    fun onRetryDetector() = startDetector(useDemo = false)

    /**
     * Switches to the scripted detector after a real failure.
     *
     * Debug builds only, and never automatic. An app that silently fell back to
     * synthetic pose data would show scores that look completely real and mean
     * nothing at all, which is far worse than an error message.
     */
    fun onUseDemoDetector() {
        if (!allowDemoDetector) return
        startDetector(useDemo = true)
    }

    override fun onCleared() {
        // The detector holds native resources and a GPU context. Failing to close
        // it here leaks them for the life of the process, and the next capture
        // session then fails to acquire the delegate.
        collectJob?.cancel()
        detector?.close()
        detector = null
    }

    companion object {
        const val COUNTDOWN_SECONDS = 3
        const val MAX_RECORDING_MS = 90_000L
        private const val TIMER_TICK_MS = 100L

        /**
         * How long framing must stay acceptable before the countdown starts by
         * itself.
         *
         * Three seconds, balanced between two failure modes. Shorter and someone
         * walking behind the athlete on their way to the next court can satisfy
         * the gate; longer and standing still long enough starts to feel like the
         * app has hung. The [COUNTDOWN_SECONDS] that follow are the athlete's
         * window to move from "standing still for the camera" into their starting
         * position for the drill.
         */
        const val AUTO_START_HOLD_MS = 3_000L

        private const val HOLD_TICK_MS = 100L

        /**
         * Ticks without a pose after which the athlete is assumed to have left the
         * shot. Half a second, which is several frames even on a CPU delegate
         * running at ten frames a second.
         */
        private const val POSE_STALE_TICKS = 5

        /**
         * Bounds the buffer independently of the timer, since frame rate varies
         * with the device and the delegate. 90 s at 60 fps with headroom.
         */
        private const val MAX_RECORDED_FRAMES = 6_000

        fun factory(skill: Skill, drillId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer
                CaptureViewModel(
                    skill = skill,
                    drillId = drillId,
                    detectorFactory = container.poseDetectorFactory,
                    demoDetectorFactory = container.syntheticDetectorFactory,
                    sessionRepository = container.sessionRepository,
                    sessionScorer = container.sessionScorer,
                    allowDemoDetector = com.volleyballlab.player.BuildConfig.DEBUG,
                )
            }
        }
    }
}
