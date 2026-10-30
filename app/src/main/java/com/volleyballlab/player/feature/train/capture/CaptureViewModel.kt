package com.volleyballlab.player.feature.train.capture

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.di.PoseDetectorFactory
import com.volleyballlab.player.domain.model.CoachAssignment
import com.volleyballlab.player.domain.model.CoachNote
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.SessionAngleStage
import com.volleyballlab.player.domain.repository.CoachRepository
import com.volleyballlab.player.domain.repository.SessionRepository
import com.volleyballlab.player.domain.repository.CoachingPreferencesRepository
import com.volleyballlab.player.domain.repository.CoachingPreferences
import com.volleyballlab.player.pose.PoseDetector
import com.volleyballlab.player.pose.PoseDetectorUnavailableException
import com.volleyballlab.player.pose.analysis.LivePostureCoach
import com.volleyballlab.player.pose.analysis.LivePostureUi
import com.volleyballlab.player.pose.analysis.LiveRepCounter
import com.volleyballlab.player.pose.analysis.ReceivingReadyPostureEvaluator
import com.volleyballlab.player.pose.analysis.SessionScorer
import com.volleyballlab.player.pose.analysis.SkillAnalyzers
import com.volleyballlab.player.pose.analysis.CameraOrientationDetector
import com.volleyballlab.player.pose.analysis.OrientationEstimate
import com.volleyballlab.player.pose.analysis.OrientationStabilityGate
import com.volleyballlab.player.pose.analysis.ReactiveCoachingEngine
import com.volleyballlab.player.pose.analysis.CoachingEvent
import com.volleyballlab.player.pose.analysis.Side
import com.volleyballlab.player.pose.model.PoseFrame
import com.volleyballlab.player.pose.review.CapturedReviewWriter
import com.volleyballlab.player.pose.review.RecordingVideoClock
import com.volleyballlab.player.pose.quality.PoseQuality
import com.volleyballlab.player.pose.quality.PoseQualityValidator
import java.io.File
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
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
        val angleReady: Boolean = true,
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
        val isHoldingStill: Boolean get() = autoStartArmed && quality.isAcceptable && angleReady
    }

    /** Recording starts when this reaches zero. */
    data class CountingDown(val secondsLeft: Int) : CaptureUiState

    data class Recording(
        val sessionId: String,
        val elapsedMs: Long,
        val liveRepCount: Int,
        val quality: PoseQuality,
        val angle: CameraAngle = CameraAngle.Front,
        val stageIndex: Int = 0,
        val orientationConfidence: Float = 0f,
        val stageMetrics: LiveCaptureMetrics = LiveCaptureMetrics(),
        val totalMetrics: LiveCaptureMetrics = LiveCaptureMetrics(),
        val rotation: RotationUi? = null,
        val suspensionHint: String? = null,
    ) : CaptureUiState

    /** Post-hoc scoring, after recording stops. */
    data object Analysing : CaptureUiState

    /** Local storage failed; the prepared result remains available for retry. */
    data object SaveFailed : CaptureUiState

    data class Finished(val sessionId: String, val repCount: Int) : CaptureUiState
}

/**
 * Who is coaching this live session.
 *
 * Two sources, kept distinct on purpose. The on-device posture coach is always
 * present - it is [LivePostureCoach] reading MediaPipe landmarks. The human coach
 * is present when the athlete has linked one: their name, the drill they set for
 * this skill, and their latest note. The live screen shows both, and never blurs
 * which is which - one is arithmetic over this rep, the other is a person.
 */
data class LiveCoachContext(
    val coachName: String? = null,
    val teamName: String? = null,
    val assignment: CoachAssignment? = null,
    val latestNote: CoachNote? = null,
) {
    val hasHumanCoach: Boolean get() = !coachName.isNullOrBlank() || !teamName.isNullOrBlank()

    /** "Coach Maya", "Your team", or null when there is no linked coach. */
    val displayName: String? get() = coachName?.takeIf { it.isNotBlank() }
        ?: teamName?.takeIf { it.isNotBlank() }
}

/**
 * Owns the capture session: the detector, the framing gate, the recording buffer
 * and the handoff to scoring.
 *
 * ## The three-tier analysis, in one place
 *
 * This is where the resolution of conflict 1 in the capstone document actually
 * lives. The document is contradictory about whether analysis is live or
 * post-hoc, and the answer implemented here is both, at different fidelity:
 *
 * - **While recording**, [LiveRepCounter] runs on every frame and drives the rep
 *   badge, [LivePostureCoach] judges posture on every frame and drives the live
 *   coaching card so the athlete can adjust mid-rep, and [PoseQualityValidator]
 *   keeps reporting framing. All three are cheap enough for camera frame rate.
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
    private val coachRepository: CoachRepository? = null,
    private val receivingPostureEvaluator: ReceivingReadyPostureEvaluator? = null,
    coachingPreferencesRepository: CoachingPreferencesRepository? = null,
    private val assignmentId: String? = null,
    private val capturedReviewWriter: CapturedReviewWriter? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow<CaptureUiState>(CaptureUiState.Initialising)
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()
    val coachingPreferences: StateFlow<CoachingPreferences> = coachingPreferencesRepository?.preferences
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CoachingPreferences())
        ?: MutableStateFlow(CoachingPreferences())

    /**
     * Live posture correction, updated while recording.
     *
     * Emits only when the displayed cue changes (or the refresh interval elapses
     * for the same cue), so the coaching card stays readable instead of
     * recomposing at camera frame rate.
     */
    private val _livePosture = MutableStateFlow(LivePostureUi.Idle)
    val livePosture: StateFlow<LivePostureUi> = _livePosture.asStateFlow()

    /**
     * The human coach in this session, if the athlete has linked one.
     *
     * Resolved once from [CoachRepository]: the link's name, the newest open
     * assignment for this skill (falling back to the newest open assignment for
     * any skill), and the newest note back. Empty when there is no link, which
     * the screen renders as on-device coaching only.
     */
    private val _coachContext = MutableStateFlow(LiveCoachContext())
    val coachContext: StateFlow<LiveCoachContext> = _coachContext.asStateFlow()

    /**
     * The latest pose, for the overlay only.
     *
     * Kept separate from [uiState] deliberately. This updates at camera frame
     * rate; folding it into the main state would recompose the entire control
     * layer thirty times a second to redraw a skeleton that lives in one Canvas.
     */
    private val _poseFrame = MutableStateFlow<PoseFrame?>(null)
    val poseFrame: StateFlow<PoseFrame?> = _poseFrame.asStateFlow()

    private val _guidedOptions = MutableStateFlow(GuidedCaptureOptions())
    val guidedOptions: StateFlow<GuidedCaptureOptions> = _guidedOptions.asStateFlow()
    private val _coachingMessage = MutableStateFlow<CoachingEvent?>(null)
    val coachingMessage: StateFlow<CoachingEvent?> = _coachingMessage.asStateFlow()
    private val speechChannel = Channel<CoachingEvent>(Channel.CONFLATED)
    val coachingEvents = speechChannel.receiveAsFlow()
    private val hapticChannel = Channel<Unit>(Channel.CONFLATED)
    val validRepEvents = hapticChannel.receiveAsFlow()
    private val orientationDetector = CameraOrientationDetector()
    private val orientationGate = OrientationStabilityGate()
    private var latestOrientation: OrientationEstimate? = null
    private var setupAngleReady = false
    private val reactiveCoach = ReactiveCoachingEngine()
    private var reliableSinceMs: Long? = null
    private var lastFrameMs: Long? = null
    private var requiredSide: Side? = null
    private var preferredSide: Side? = null
    private var rotationCountdownJob: Job? = null
    private var rotationElapsedMs = 0L
    private var chunks = mutableListOf<CaptureChunk>()
    private var stageSummaries = mutableListOf<SessionAngleStage>()
    private var currentChunkAngle = CameraAngle.Front
    private var currentChunkStage = 0
    private var stageStartMs: Long? = null
    private var totalMetrics = LiveCaptureMetrics()
    private var stageMetrics = LiveCaptureMetrics()
    private var captureForeground = true
    private var lastRepSummary = ""
    private var lastFrameRecordingElapsedMs = 0L


    private val qualityValidator = PoseQualityValidator()
    private val repCounter = LiveRepCounter(SkillAnalyzers.forSkill(skill))
    private val postureCoach = LivePostureCoach(
        skill = skill,
        receivingEvaluator = receivingPostureEvaluator,
    )

    @Volatile private var detector: PoseDetector? = null
    private var collectJob: Job? = null
    private var poseWatchdogJob: Job? = null
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
    private var recordingSessionId: String? = null
    private var pendingCompletion: CaptureCompletion? = null
    private var videoFinalization: CompletableDeferred<String?>? = null
    private val videoClock = RecordingVideoClock()
    private val replayFrames = mutableListOf<PoseFrame>()

    init {
        startDetector(useDemo = false)
        observeCoachContext()
    }

    /**
     * Resolves the human coach for this session, once.
     *
     * Null repository (unit tests) means no human coach: the context stays empty
     * and the screen coaches from on-device analysis alone.
     */
    private fun observeCoachContext() {
        val repo = coachRepository ?: return
        viewModelScope.launch {
            combine(
                repo.myCoach,
                repo.assignmentsForMe,
                repo.notesForMe,
            ) { link, assignments, notes ->
                val open = assignments.filter { it.isOpen }
                LiveCoachContext(
                    coachName = link?.coachName?.takeIf { it.isNotBlank() },
                    teamName = link?.teamName?.takeIf { it.isNotBlank() },
                    assignment = if (assignmentId != null) assignments.firstOrNull { it.id == assignmentId }
                        else open.firstOrNull { it.skill == skill } ?: open.firstOrNull(),
                    latestNote = notes.firstOrNull(),
                )
            }.collect { _coachContext.value = it }
        }
    }

    private fun startDetector(useDemo: Boolean) {
        collectJob?.cancel()
        poseWatchdogJob?.cancel()
        detector?.close()
        detector = null
        _poseFrame.value = null
        ticksSincePose = POSE_STALE_TICKS
        _uiState.value = CaptureUiState.Initialising

        val created = try {
            if (useDemo) demoDetectorFactory.create() else detectorFactory.create()
        } catch (unavailable: PoseDetectorUnavailableException) {
            Log.e("CaptureViewModel", "Pose detector unavailable: ${unavailable.reason}", unavailable)
            _uiState.value = CaptureUiState.Unavailable(
                reason = unavailable.reason,
                canUseDemoDetector = allowDemoDetector,
            )
            return
        }

        detector = created
        repCounter.reset()
        postureCoach.reset()
        _livePosture.value = LivePostureUi.Idle
        enterFraming(armAutoStart = true)

        collectJob = viewModelScope.launch {
            created.poses.collect(::onPose)
        }
        poseWatchdogJob = viewModelScope.launch { runPoseWatchdog() }
    }

    /** Called from the CameraX analyzer. Must stay cheap; it only forwards. */
    fun onCameraFrame(bitmap: Bitmap, timestampMs: Long, mirrored: Boolean) {
        detector?.submit(bitmap, timestampMs, mirrored)
    }

    private fun onPose(frame: PoseFrame) {
        if (!captureForeground) return
        ticksSincePose = 0
        _poseFrame.value = frame
        lastFrameMs = frame.timestampMs
        latestOrientation = orientationDetector.detect(frame)

        when (val state = _uiState.value) {
            // Only the verdict is recorded here. The framing state itself is
            // published by the hold gate on its own tick: two writers running at
            // two different rates would race over steadyMs, and republishing at
            // camera frame rate would recompose the guidance card thirty times a
            // second to change nothing.
            is CaptureUiState.Framing -> {
                latestQuality = qualityValidator.validate(frame)
                setupAngleReady = orientationGate.update(
                    requireNotNull(latestOrientation), CameraAngle.Front, frame.timestampMs,
                )
            }

            is CaptureUiState.Recording -> {
                if (replayFrames.size < 6_100 && (replayFrames.lastOrNull()?.cameraTimestampMs?.let {
                        frame.cameraTimestampMs - it >= 50L
                    } != false)) replayFrames += frame
                processRecordingFrame(frame, state)
            }

            else -> Unit
        }
    }

    fun setGuidedAngles(enabled: Boolean) {
        if (_uiState.value !is CaptureUiState.Framing) return
        _guidedOptions.value = _guidedOptions.value.copy(enabled = enabled)
        steadyMs = 0L
        orientationGate.reset()
        setupAngleReady = false
    }

    fun setRepsPerAngle(reps: Int) {
        if (_uiState.value !is CaptureUiState.Framing || reps !in listOf(5, 10)) return
        _guidedOptions.value = _guidedOptions.value.copy(repsPerAngle = reps)
        steadyMs = 0L
    }

    fun clearCoachingMessage(id: String) {
        if (_coachingMessage.value?.id.toString() == id) _coachingMessage.value = null
    }

    fun setCaptureForeground(active: Boolean) {
        captureForeground = active
        if (active) return
        _coachingMessage.value = null
        while (speechChannel.tryReceive().isSuccess) { /* discard old prompts */ }
        while (hapticChannel.tryReceive().isSuccess) { /* discard old successes */ }
        reliableSinceMs = null
        orientationGate.reset()
        setupAngleReady = false
        val recording = _uiState.value as? CaptureUiState.Recording
        if (recording != null) {
            interruptTracking()
            reactiveCoach.cancelPendingGuidance()
            rotationCountdownJob?.cancel()
            _uiState.value = recording.copy(rotation = recording.rotation?.copy(secondsLeft = null))
        }
    }

    private fun publish(event: CoachingEvent?) {
        if (event == null) return
        _coachingMessage.value = event
        speechChannel.trySend(event)
    }

    private fun processRecordingFrame(frame: PoseFrame, state: CaptureUiState.Recording) {
        lastFrameRecordingElapsedMs = state.elapsedMs
        val estimate = requireNotNull(latestOrientation)
        val quality = qualityValidator.validate(frame)
        if (state.rotation != null) {
            val ready = orientationGate.update(estimate, state.rotation.requestedAngle, frame.timestampMs, requiredSide)
            _uiState.value = state.copy(quality = quality, orientationConfidence = estimate.confidence)
            if (ready && state.rotation.secondsLeft == null) beginRotationCountdown()
            if (!ready && state.rotation.secondsLeft != null) {
                rotationCountdownJob?.cancel()
                _uiState.value = state.copy(rotation = state.rotation.copy(secondsLeft = null), quality = quality)
            }
            return
        }
        // Moving athletes are allowed to move; the setup validator's Unstable
        // verdict does not invalidate a rep. Framing and landmark evidence do.
        val angleMatches = estimate.angle == state.angle && estimate.confidence >= 0.7f
        // A serve can briefly rotate during its swing. The requested view must
        // be established at rest, and each scored phase still has its own mask.
        val angleAligned = !_guidedOptions.value.enabled || angleMatches || repCounter.currentPhase == null
        val reliable = estimate.fullBodyVisible && frame.torsoAndLimbVisibility() >= 0.6f && angleAligned
        if (!reliable) {
            interruptTracking()
            _uiState.value = state.copy(quality = quality, orientationConfidence = estimate.confidence,
                stageMetrics = stageMetrics, totalMetrics = totalMetrics,
                suspensionHint = if (!angleAligned) "Return to the ${state.angle.name.lowercase()} view and hold steady." else null)
            publish(reactiveCoach.onTracking(true, frame.timestampMs))
            return
        }
        if (reliableSinceMs == null) reliableSinceMs = frame.timestampMs
        if (frame.timestampMs - requireNotNull(reliableSinceMs) < 1_000L) return
        val frameLimit = if (_guidedOptions.value.enabled) MAX_GUIDED_RECORDED_FRAMES else MAX_RECORDED_FRAMES
        if (recordedFrames.size + chunks.sumOf { it.frames.size } >= frameLimit) {
            onStopRequested()
            return
        }
        if (stageStartMs == null) stageStartMs = frame.timestampMs
        recordedFrames += frame
        val counted = repCounter.onFrame(frame)
        val actualAngle = if (estimate.confidence >= 0.65f) estimate.angle else CameraAngle.Unknown
        _livePosture.value = postureCoach.update(frame, repCounter.currentPhase, actualAngle)
        publish(reactiveCoach.onPosture(_livePosture.value, frame.timestampMs))
        if (counted) {
            repCounter.lastCompletedWindow?.let { window ->
                val assessment = postureCoach.assessCompletedRep(window,
                    if (_guidedOptions.value.enabled) state.angle else actualAngle)
                stageMetrics = stageMetrics.completed(assessment.score?.roundToInt(), assessment.isAssessable, assessment.isGood)
                totalMetrics = totalMetrics.completed(assessment.score?.roundToInt(), assessment.isAssessable, assessment.isGood)
                lastRepSummary = reactiveCoach.assessmentSummary(assessment)
                _livePosture.value = postureCoach.updateCompletedRep(assessment, frame.timestampMs)
                publish(reactiveCoach.onRep(assessment, totalMetrics.reps, totalMetrics.streak, frame.timestampMs))
                if (assessment.isAssessable && assessment.isGood) hapticChannel.trySend(Unit)
                if (skill == Skill.Serving || skill == Skill.Spiking) {
                    val contact = window.frameFor(com.volleyballlab.player.domain.model.RepPhase.Contact)
                    if (contact != null) preferredSide = if (
                        contact.image(com.volleyballlab.player.pose.model.BodyPoint.LEFT_WRIST).y <
                        contact.image(com.volleyballlab.player.pose.model.BodyPoint.RIGHT_WRIST).y
                    ) Side.Left else Side.Right
                }
            }
        }
        _uiState.value = state.copy(quality = quality, liveRepCount = totalMetrics.reps,
            orientationConfidence = estimate.confidence, stageMetrics = stageMetrics, totalMetrics = totalMetrics,
            suspensionHint = null)
        if (GuidedCaptureProgress.reachedTarget(_guidedOptions.value, stageMetrics)) {
            finishStage(state)
            val next = GuidedCaptureProgress.nextStage(state.stageIndex)
            if (next == null) onStopRequested() else requestRotation(next)
        }
    }

    private fun flushChunk() {
        if (recordedFrames.isNotEmpty()) chunks += CaptureChunk(currentChunkStage, currentChunkAngle, recordedFrames.toList())
        recordedFrames.clear()
    }

    private fun interruptTracking() {
        flushChunk()
        reliableSinceMs = null
        repCounter.resetSegment()
        postureCoach.clearStale()
        _livePosture.value = LivePostureUi.Idle.copy(trackingLost = true)
        _coachingMessage.value = null
        stageMetrics = stageMetrics.interrupted()
        totalMetrics = totalMetrics.interrupted()
    }

    private fun finishStage(state: CaptureUiState.Recording) {
        flushChunk()
        val first = stageStartMs ?: return
        val last = chunks.lastOrNull { it.stageIndex == state.stageIndex }?.frames?.lastOrNull()?.timestampMs ?: first
        if (stageSummaries.none { it.stageIndex == state.stageIndex }) stageSummaries += SessionAngleStage(
            state.stageIndex, state.angle, first, last, stageMetrics.reps, stageMetrics.validReps,
            stageMetrics.bestStreak, stageMetrics.estimatedFormScore,
        )
    }

    private fun requestRotation(index: Int) {
        val state = _uiState.value as? CaptureUiState.Recording ?: return
        val angle = GuidedCaptureProgress.angles[index]
        orientationGate.reset()
        qualityValidator.reset()
        repCounter.reset()
        postureCoach.reset()
        reliableSinceMs = null
        rotationElapsedMs = 0L
        requiredSide = if (angle == CameraAngle.Side && (skill == Skill.Serving || skill == Skill.Spiking)) preferredSide else null
        val direction = when (angle) {
            CameraAngle.Side -> if (requiredSide == null) "Turn sideways to the camera." else "Turn sideways with your ${requiredSide!!.name.lowercase()} hitting arm nearest the camera."
            CameraAngle.Rear -> "Now face away from the camera. Keep your whole body in view."
            else -> "Face the camera and keep your whole body in view."
        }
        val summary = if (stageMetrics.estimatedFormScore != null) {
            "${stageMetrics.reps} reps completed, ${stageMetrics.validReps} good reps. "
        } else "${stageMetrics.reps} reps completed. Some cues could not be assessed. "
        val message = summary + lastRepSummary.takeIf { it.isNotBlank() }?.let { "$it " }.orEmpty() + direction
        _coachingMessage.value = null
        _livePosture.value = LivePostureUi.Idle
        _uiState.value = state.copy(rotation = RotationUi(angle, index, message = message))
        publish(reactiveCoach.onRotation(message, lastFrameMs ?: 0L))
    }

    private fun beginRotationCountdown() {
        rotationCountdownJob?.cancel()
        rotationCountdownJob = viewModelScope.launch {
            for (second in COUNTDOWN_SECONDS downTo 1) {
                val state = _uiState.value as? CaptureUiState.Recording ?: return@launch
                val rotation = state.rotation ?: return@launch
                _uiState.value = state.copy(rotation = rotation.copy(secondsLeft = second))
                delay(1_000)
                if (ticksSincePose >= POSE_STALE_TICKS) return@launch
            }
            val state = _uiState.value as? CaptureUiState.Recording ?: return@launch
            val rotation = state.rotation ?: return@launch
            currentChunkAngle = rotation.requestedAngle
            currentChunkStage = rotation.stageIndex
            stageStartMs = null
            stageMetrics = LiveCaptureMetrics()
            lastRepSummary = ""
            totalMetrics = totalMetrics.interrupted()
            repCounter.reset()
            postureCoach.reset()
            _coachingMessage.value = null
            reactiveCoach.cancelPendingGuidance()
            reliableSinceMs = lastFrameMs
            _uiState.value = state.copy(angle = rotation.requestedAngle, stageIndex = rotation.stageIndex,
                rotation = null, stageMetrics = stageMetrics, totalMetrics = totalMetrics)
        }
    }

    fun onRetryAngle() {
        val state = _uiState.value as? CaptureUiState.Recording ?: return
        val rotation = state.rotation ?: return
        rotationCountdownJob?.cancel()
        orientationGate.reset()
        rotationElapsedMs = 0L
        _uiState.value = state.copy(rotation = rotation.copy(elapsedMs = 0, secondsLeft = null))
        publish(reactiveCoach.onRotation(rotation.message, lastFrameMs ?: 0L))
    }

    fun onSkipAngle() {
        val state = _uiState.value as? CaptureUiState.Recording ?: return
        val rotation = state.rotation ?: return
        rotationCountdownJob?.cancel()
        val next = GuidedCaptureProgress.nextStage(rotation.stageIndex)
        if (next == null) onStopRequested() else requestRotation(next)
    }

    /** Clear old landmarks and feedback when MediaPipe stops producing poses. */
    private suspend fun runPoseWatchdog() {
        while (true) {
            delay(HOLD_TICK_MS)
            if (ticksSincePose < POSE_STALE_TICKS) ticksSincePose++
            if (ticksSincePose < POSE_STALE_TICKS) continue

            if (_poseFrame.value != null) {
                _poseFrame.value = null
                qualityValidator.reset()
            }
            val recording = _uiState.value as? CaptureUiState.Recording ?: continue
            if (recording.quality != PoseQuality.NotYetKnown) {
                _uiState.value = recording.copy(quality = PoseQuality.NotYetKnown)
            }
            if (!_livePosture.value.trackingLost && recording.rotation == null) {
                interruptTracking()
                publish(reactiveCoach.onTracking(true, lastFrameMs ?: 0L))
            }
            if (recording.rotation != null) {
                rotationCountdownJob?.cancel()
                orientationGate.reset()
                _uiState.value = recording.copy(rotation = recording.rotation.copy(secondsLeft = null),
                    quality = PoseQuality.NotYetKnown)
            }
        }
    }

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
        if (_guidedOptions.value.enabled && !setupAngleReady) return
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
        orientationGate.reset()
        setupAngleReady = false
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
            if (ticksSincePose >= POSE_STALE_TICKS) {
                qualityValidator.reset()
                latestQuality = PoseQuality.NotYetKnown
            }

            val quality = latestQuality
            val angleReady = !_guidedOptions.value.enabled || setupAngleReady
            if (quality.isAcceptable && angleReady) {
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
                angleReady = angleReady,
            )

            if (autoStartArmed && quality.isAcceptable && angleReady && steadyMs >= AUTO_START_HOLD_MS) {
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
                if (ticksSincePose >= POSE_STALE_TICKS ||
                    (_guidedOptions.value.enabled && latestOrientation?.angle != CameraAngle.Front)) {
                    enterFraming(armAutoStart = true)
                    return@launch
                }
            }
            beginRecording()
        }
    }

    private fun beginRecording() {
        recordedFrames.clear()
        replayFrames.clear()
        videoClock.reset()
        _poseFrame.value?.cameraTimestampMs?.let(videoClock::started)
        chunks.clear()
        stageSummaries.clear()
        totalMetrics = LiveCaptureMetrics()
        stageMetrics = LiveCaptureMetrics()
        lastRepSummary = ""
        lastFrameRecordingElapsedMs = 0L
        currentChunkStage = 0
        currentChunkAngle = CameraAngle.Front
        stageStartMs = null
        reliableSinceMs = null
        reactiveCoach.reset()
        _coachingMessage.value = null
        recordingSessionId = UUID.randomUUID().toString()
        videoFinalization = CompletableDeferred()
        repCounter.reset()
        postureCoach.reset()
        _livePosture.value = LivePostureUi.Idle
        recordingStartedAtEpochMs = System.currentTimeMillis()
        _uiState.value = CaptureUiState.Recording(
            sessionId = requireNotNull(recordingSessionId),
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
                lastFrameMs?.let { cameraMs ->
                    publish(reactiveCoach.drainDue(cameraMs + (elapsed - lastFrameRecordingElapsedMs).coerceAtLeast(0L)))
                }
                val rotation = state.rotation?.let {
                    rotationElapsedMs += TIMER_TICK_MS
                    it.copy(elapsedMs = rotationElapsedMs)
                }
                _uiState.value = state.copy(elapsedMs = elapsed, rotation = rotation)
                val limit = if (_guidedOptions.value.enabled) MAX_GUIDED_RECORDING_MS else MAX_RECORDING_MS
                if (elapsed >= limit) {
                    onStopRequested()
                    break
                }
            }
        }
    }

    /** Stops recording and runs the post-hoc scoring pass. */
    fun onStopRequested() {
        val state = _uiState.value as? CaptureUiState.Recording ?: return
        timerJob?.cancel()
        rotationCountdownJob?.cancel()
        if (state.rotation == null) finishStage(state)
        flushChunk()
        _coachingMessage.value = null
        _livePosture.value = LivePostureUi.Idle
        reactiveCoach.cancelPendingGuidance()
        _uiState.value = CaptureUiState.Analysing

        pendingCompletion = CaptureCompletion(
            input = CaptureCompletionInput(
                sessionId = requireNotNull(recordingSessionId), skill = skill, drillId = drillId,
                assignmentId = assignmentId, startedAtEpochMs = recordingStartedAtEpochMs,
                chunks = chunks.toList(), guidedAngles = _guidedOptions.value.enabled,
                angleStages = stageSummaries.toList(), replayFrames = replayFrames.toList(),
            ),
            scorer = sessionScorer, sessions = sessionRepository,
            videoFinalization = videoFinalization, videoOrigin = videoClock::origin,
            reviewWriter = capturedReviewWriter, receivingPostureEvaluator = receivingPostureEvaluator,
        )
        viewModelScope.launch { completePendingCapture() }
    }

    fun onRetrySaveRequested() {
        if (_uiState.value != CaptureUiState.SaveFailed || pendingCompletion == null) return
        _uiState.value = CaptureUiState.Analysing
        viewModelScope.launch { completePendingCapture() }
    }

    private suspend fun completePendingCapture() {
        val completion = pendingCompletion ?: return
        val result = try {
            completion.complete()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _uiState.value = CaptureUiState.SaveFailed
            return
        }
        recordedFrames.clear()
        replayFrames.clear()
        chunks.clear()
        recordingSessionId = null
        videoFinalization = null
        pendingCompletion = null
        _uiState.value = CaptureUiState.Finished(result.id, result.repCount)
    }

    /** Completes the recording once CameraX has closed the MP4 file. */
    fun onVideoFinalized(sessionId: String, path: String?) {
        if (sessionId != recordingSessionId || videoFinalization?.complete(path) != true) {
            path?.let { File(it).delete() }
        }
    }

    fun onVideoClock(sessionId: String, cameraMs: Long, recordedMs: Long, started: Boolean) {
        if (sessionId != recordingSessionId) return
        if (started) videoClock.started(cameraMs) else videoClock.observe(cameraMs, recordedMs)
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
        poseWatchdogJob?.cancel()
        rotationCountdownJob?.cancel()
        speechChannel.close()
        hapticChannel.close()
        recordingSessionId = null
        videoFinalization?.cancel()
        videoFinalization = null
        detector?.close()
        detector = null
        receivingPostureEvaluator?.close()
    }

    companion object {
        const val COUNTDOWN_SECONDS = 3
        const val MAX_RECORDING_MS = 90_000L
        const val MAX_GUIDED_RECORDING_MS = 300_000L
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
        private const val MAX_GUIDED_RECORDED_FRAMES = 18_000

        fun factory(skill: Skill, drillId: String, assignmentId: String? = null): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer
                val receivingEvaluator = if (skill == Skill.Receiving) {
                    runCatching { container.createReceivingReadyPostureEvaluator() }
                        .onFailure { error ->
                            Log.e("CaptureViewModel", "Receiving-ready LiteRT model failed to load", error)
                        }
                        .getOrNull()
                } else {
                    null
                }
                CaptureViewModel(
                    skill = skill,
                    drillId = drillId,
                    assignmentId = assignmentId,
                    detectorFactory = container.poseDetectorFactory,
                    demoDetectorFactory = container.syntheticDetectorFactory,
                    sessionRepository = container.sessionRepository,
                    sessionScorer = container.sessionScorer,
                    allowDemoDetector = com.volleyballlab.player.BuildConfig.DEBUG,
                    coachRepository = container.coachRepository,
                    receivingPostureEvaluator = receivingEvaluator,
                    coachingPreferencesRepository = container.coachingPreferencesRepository,
                    capturedReviewWriter = container.videoReviewStore,
                )
            }
        }
    }
}
