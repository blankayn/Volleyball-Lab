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
import com.volleyballlab.player.domain.repository.ProfileRepository
import com.volleyballlab.player.domain.model.AthleteProfile
import com.volleyballlab.player.pose.analysis.LiveCoachingPlan
import com.volleyballlab.player.pose.analysis.LiveTrackingGate
import com.volleyballlab.player.pose.analysis.LiveRepFeedback
import com.volleyballlab.player.pose.analysis.PersonalizedLiveCoaching
import com.volleyballlab.player.pose.analysis.PersonalElbowBaseline
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
import com.volleyballlab.player.pose.analysis.CoachPhrasebook
import com.volleyballlab.player.pose.analysis.CoachingEventType
import com.volleyballlab.player.pose.analysis.SessionWrapUp
import com.volleyballlab.player.ui.coaching.SpeechTiming
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import com.volleyballlab.player.pose.analysis.CoachingEvent
import com.volleyballlab.player.pose.analysis.Facing
import com.volleyballlab.player.pose.analysis.FacingGuide
import com.volleyballlab.player.pose.analysis.GuideFigure
import com.volleyballlab.player.pose.analysis.MovementFault
import com.volleyballlab.player.pose.analysis.ProgressiveInstructions
import com.volleyballlab.player.pose.analysis.SpokenCue
import com.volleyballlab.player.pose.analysis.ReferenceFigure
import com.volleyballlab.player.pose.analysis.ReferenceFigureTracker
import com.volleyballlab.player.pose.analysis.ReferenceMoment
import com.volleyballlab.player.pose.analysis.ReferencePoses
import com.volleyballlab.player.pose.analysis.ReferenceShow
import com.volleyballlab.player.pose.analysis.Side
import com.volleyballlab.player.pose.analysis.Stance
import com.volleyballlab.player.pose.analysis.LiveCoachingAdvice
import com.volleyballlab.player.pose.analysis.LiveCoachingEvidenceBuffer
import com.volleyballlab.player.pose.analysis.LiveCoachingTurnContext
import com.volleyballlab.player.data.ai.LiveCoachingRepository
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
    data class CountingDown(
        val secondsLeft: Int,
        val instruction: String? = null,
        /** The opening brief, one sentence per segment, for speech with pauses. */
        val segments: List<String> = emptyList(),
    ) : CaptureUiState

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
        val blockedReason: String? = null,
        val finishingAfterRep: Boolean = false,
        /** The closing turn, spoken before the recording stops. */
        val closing: List<String> = emptyList(),
        /** Optional AI carry-forward for the closing card. Never spoken, never awaited. */
        val closingAdvice: LiveCoachingAdvice? = null,
        /** Which way the athlete faces for the current view, in their own left and right. */
        val facing: Facing = Facing.Forward,
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
    private val liveCoachingRepository: LiveCoachingRepository? = null,
    private val profileRepository: ProfileRepository? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow<CaptureUiState>(CaptureUiState.Initialising)
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()
    val coachingPreferences: StateFlow<CoachingPreferences> = coachingPreferencesRepository?.preferences
        ?.stateIn(viewModelScope, SharingStarted.Eagerly, CoachingPreferences())
        ?: MutableStateFlow(CoachingPreferences())
    private val _coachingPlan = MutableStateFlow(LiveCoachingPlan.forAthlete(skill, AthleteProfile.Anonymous))
    val coachingPlan: StateFlow<LiveCoachingPlan> = _coachingPlan.asStateFlow()
    private val _repFeedback = MutableStateFlow<LiveRepFeedback?>(null)
    val repFeedback: StateFlow<LiveRepFeedback?> = _repFeedback.asStateFlow()
    private val _speechWindowOpen = MutableStateFlow(false)
    val speechWindowOpen: StateFlow<Boolean> = _speechWindowOpen.asStateFlow()

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

    /**
     * The reference figure to draw over the camera, or null for none.
     *
     * Like [poseFrame], this updates at camera frame rate and feeds one Canvas,
     * so it is kept out of [uiState].
     */
    private val _referenceFigure = MutableStateFlow<GuideFigure?>(null)
    val referenceFigure: StateFlow<GuideFigure?> = _referenceFigure.asStateFlow()
    private val figureTracker = ReferenceFigureTracker()
    /** What this athlete has already been taught this session; see [publish]. */
    private val instructions = ProgressiveInstructions()
    private var shownReference: Pair<ReferenceShow, Facing>? = null
    private var referenceSinceMs = 0L

    private val _guidedOptions = MutableStateFlow(GuidedCaptureOptions())
    val guidedOptions: StateFlow<GuidedCaptureOptions> = _guidedOptions.asStateFlow()
    private val _coachingMessage = MutableStateFlow<CoachingEvent?>(null)
    val coachingMessage: StateFlow<CoachingEvent?> = _coachingMessage.asStateFlow()
    private val speechChannel = Channel<CoachingEvent>(Channel.CONFLATED)
    val coachingEvents = speechChannel.receiveAsFlow()
    private val hapticChannel = Channel<Unit>(Channel.CONFLATED)
    val validRepEvents = hapticChannel.receiveAsFlow()
    private val orientationDetector = CameraOrientationDetector()
    private val trackingGate = LiveTrackingGate()
    private val orientationGate = OrientationStabilityGate()
    private var latestOrientation: OrientationEstimate? = null
    private var setupAngleReady = false
    private val director = com.volleyballlab.player.pose.analysis.CoachDirector()
    private var openingFinished: CompletableDeferred<Unit>? = null
    private var spokenGuidanceId: Long? = null
    fun onOpeningFinished() { openingFinished?.complete(Unit) }
    fun onRotationSpeechStarted(id: Long) { spokenGuidanceId = id }
    private var reliableSinceMs: Long? = null
    private var lastFrameMs: Long? = null
    private var requiredSide: Side? = null
    private var preferredSide: Side? = null
    /**
     * The stance the current view is performed in. Fixed when the view begins,
     * so a later rep that reads the other hand as higher cannot flip the
     * required side under an athlete who is standing exactly where they were told.
     */
    private var stageStance: Stance = FacingGuide.stance(skill, CameraAngle.Front)
    /** The last movement that was turned away, and when: (fault, timestamp). */
    private var lastNotCounted: Pair<MovementFault, Long>? = null
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
    private var idleTicks = 0
    private var idleSpoken = false
    private var closingEndedEarly = false
    private val lateAdviceChannel = Channel<Pair<Long, String>>(Channel.CONFLATED)
    /** AI advice that arrived while its turn was still being spoken: (guidance id, text). */
    val lateAdviceSpeech = lateAdviceChannel.receiveAsFlow()
    private var lastFrameRecordingElapsedMs = 0L
    private val stageEvidence = LiveCoachingEvidenceBuffer()
    private var liveCoachingJob: Job? = null
    private var guidanceSequence = 0L
    private var voiceGuidanceEnabled = false
    private var coachingSequence = 0L
    private var finalRepJob: Job? = null
    private val directorEvents = mutableMapOf<Long, Long>()


    private val qualityValidator = PoseQualityValidator()
    private val repCounter = LiveRepCounter(SkillAnalyzers.forSkill(skill))

    /** Prototype, debug builds only: see [PersonalElbowBaseline] for the evidence and its limits. */
    private val elbowBaselineEnabled = com.volleyballlab.player.BuildConfig.DEBUG &&
        (skill == Skill.Serving || skill == Skill.Spiking)
    private val elbowBaseline = PersonalElbowBaseline()
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
        viewModelScope.launch {
            // Local only: the athlete's saved choices, what this device remembers of
            // earlier sessions on this drill, and the linked coach's drill title.
            combine(
                profileRepository?.profile ?: flowOf(AthleteProfile.Anonymous),
                sessionRepository.sessions.catch { emit(emptyList()) },
                _coachContext,
            ) { profile, sessions, coach ->
                LiveCoachingPlan.forAthlete(skill, profile)
                    .withDrill(drillId)
                    .withHistory(sessions, skill, drillId)
                    .withCoach(coach.coachName, coach.assignment?.takeIf { it.skill == skill }?.title)
            }.collect { plan ->
                // Freeze the chosen plan at the countdown for a consistent session.
                if (_uiState.value is CaptureUiState.Framing || _uiState.value is CaptureUiState.Initialising) {
                    _coachingPlan.value = plan
                }
            }
        }
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
        _referenceFigure.value = null
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
        elbowBaseline.reset()
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
        val activeDetector = detector
        if (activeDetector == null) bitmap.recycle() else activeDetector.submit(bitmap, timestampMs, mirrored)
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
                if (replayFrames.size < 7_300 && (replayFrames.lastOrNull()?.cameraTimestampMs?.let {
                        frame.cameraTimestampMs - it >= 50L
                    } != false)) replayFrames += frame
                processRecordingFrame(frame, state)
            }

            else -> Unit
        }
        // After the frame is processed, so the figure reflects what it just decided.
        updateReferenceFigure(frame)
    }

    /**
     * What the reference figure should show right now, and which way it faces,
     * or null for nothing.
     *
     * It appears at the moments an athlete is being told where to be: the
     * countdown, a turn to a new view, standing the wrong way, a movement that
     * was not counted, and a long wait with no rep. It never appears while a
     * movement is under way - they are not looking at the screen then, and a
     * target that moves while they do is a distraction, not a guide.
     */
    private fun referenceCue(): Pair<ReferenceShow, Facing>? {
        val observed = latestOrientation ?: return null
        val guided = _guidedOptions.value.enabled
        return when (val state = _uiState.value) {
            // Before the first rep: what one rep is, start to finish.
            is CaptureUiState.CountingDown -> ReferenceShow.Demonstrate to
                FacingGuide.shown(FacingGuide.stance(skill, CameraAngle.Front).takeIf { guided }, observed)

            is CaptureUiState.Recording -> when {
                state.finishingAfterRep -> null
                // Turning: the stance to turn into, then the rep from that side.
                state.rotation != null ->
                    (if (state.rotation.secondsLeft != null) ReferenceShow.Demonstrate else ReferenceShow.Start) to
                        FacingGuide.shown(stageStance, observed)
                // Facing the wrong way: the right way, in the figure rather than in words.
                state.suspensionHint != null ->
                    ReferenceShow.Start to (if (guided) stageStance.facing else FacingGuide.shown(null, observed))
                repCounter.currentPhase == null -> null
                else -> _coachingMessage.value?.reference?.let { show ->
                    show to FacingGuide.shown(stageStance.takeIf { guided }, observed)
                }
            }

            else -> null
        }
    }

    private fun updateReferenceFigure(frame: PoseFrame) {
        val cue = if (coachingPreferences.value.poseGuideEnabled) referenceCue() else null
        if (cue == null) {
            shownReference = null
            figureTracker.reset()
            _referenceFigure.value = null
            return
        }
        if (cue != shownReference) {
            shownReference = cue
            referenceSinceMs = frame.timestampMs
        }
        val (body, placement) = figureTracker.update(frame) ?: run { _referenceFigure.value = null; return }
        val (show, facing) = cue
        val moment = when (show) {
            ReferenceShow.Start -> ReferenceMoment.Start
            ReferenceShow.Finish -> ReferenceMoment.Finish
            ReferenceShow.Demonstrate ->
                if ((frame.timestampMs - referenceSinceMs) / DEMONSTRATE_HOLD_MS % 2L == 0L) ReferenceMoment.Start
                else ReferenceMoment.Finish
        }
        // Side-on, the arm nearest the lens is the hitting arm; that is the rule
        // the side view is set up by. Otherwise, the arm they have been hitting with.
        val hitting = FacingGuide.nearSide(facing) ?: preferredSide ?: Side.Right
        _referenceFigure.value = ReferenceFigure.layout(
            ReferencePoses.pose(skill, moment, hitting, body), moment, body, placement, facing,
            frame.imageWidth, frame.imageHeight,
        )
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
        if (active) {
            val state = _uiState.value as? CaptureUiState.Recording
            val rotation = state?.rotation
            if (rotation != null) _uiState.value = state.copy(rotation = rotation.copy(
                speechPending = voiceGuidanceEnabled && rotation.guidanceId != spokenGuidanceId, loadingCoaching = false))
            return
        }
        liveCoachingJob?.cancel()
        _coachingMessage.value = null
        _referenceFigure.value = null
        while (speechChannel.tryReceive().isSuccess) { /* discard old prompts */ }
        while (hapticChannel.tryReceive().isSuccess) { /* discard old successes */ }
        reliableSinceMs = null
        orientationGate.reset()
        trackingGate.reset()
        setupAngleReady = false
        val recording = _uiState.value as? CaptureUiState.Recording
        if (recording != null) {
            interruptTracking()
            rotationCountdownJob?.cancel()
            _uiState.value = recording.copy(rotation = recording.rotation?.copy(secondsLeft = null,
                loadingCoaching = false, speechPending = false))
        }
    }

    fun setVoiceGuidanceEnabled(enabled: Boolean) {
        voiceGuidanceEnabled = enabled
        if (!enabled) {
            val state = _uiState.value as? CaptureUiState.Recording ?: return
            val rotation = state.rotation ?: return
            _uiState.value = state.copy(rotation = rotation.copy(speechPending = false))
            orientationGate.reset()
        }
    }

    fun onRotationSpeechFinished(id: Long) {
        if (!captureForeground) return
        val state = _uiState.value as? CaptureUiState.Recording ?: return
        val rotation = state.rotation?.takeIf { it.guidanceId == id && it.speechPending && !it.loadingCoaching } ?: return
        _uiState.value = state.copy(rotation = rotation.copy(speechPending = false))
        // Confirm the view after speaking; an earlier pose cannot start a countdown.
        orientationGate.reset()
    }

    /**
     * @param cue the full and short wording of [event], when it has both. The
     *   card always shows the full sentence; what is spoken depends on whether
     *   the athlete has already heard it. See [ProgressiveInstructions].
     */
    private fun publish(event: CoachingEvent?, speak: Boolean = true, directorId: Long? = null, cue: SpokenCue? = null) {
        if (event == null) return
        var current = event.copy(id = ++coachingSequence)
        // Only speech the athlete can actually hear teaches them anything. A
        // muted session reads the full sentence on the card instead.
        if (speak && cue != null && voiceGuidanceEnabled) {
            current = current.copy(spokenText = instructions.speak(cue, current.timestampMs, current.id))
        }
        _coachingMessage.value = current
        if (directorId != null) {
            if (directorEvents.size > 16) directorEvents.clear()
            directorEvents[current.id] = directorId
        }
        if (speak) speechChannel.trySend(current)
    }

    /** The screen could not speak this event. The director may offer its sentence again. */
    fun onSpeechDropped(eventId: Long) {
        instructions.dropped(eventId)
        directorEvents.remove(eventId)?.let(director::onSpeechDropped)
    }

    private fun processRecordingFrame(frame: PoseFrame, state: CaptureUiState.Recording) {
        if (state.finishingAfterRep) return
        lastFrameRecordingElapsedMs = state.elapsedMs
        val estimate = requireNotNull(latestOrientation)
        val quality = qualityValidator.validate(frame)
        if (state.rotation != null) {
            val ready = orientationGate.update(estimate, state.rotation.requestedAngle, frame.timestampMs, requiredSide)
            _uiState.value = state.copy(quality = quality, orientationConfidence = estimate.confidence)
            if (ready && state.rotation.secondsLeft == null && !state.rotation.loadingCoaching &&
                !state.rotation.speechPending) beginRotationCountdown()
            if (!ready && state.rotation.secondsLeft != null) {
                rotationCountdownJob?.cancel()
                _uiState.value = state.copy(rotation = state.rotation.copy(secondsLeft = null), quality = quality)
            }
            return
        }
        // Moving athletes are allowed to move; the setup validator's Unstable
        // verdict does not invalidate a rep. Framing and landmark evidence do.
        // A view the detector is not sure of is treated as not established.
        val observedView = if (estimate.confidence >= 0.7f) estimate else estimate.copy(angle = CameraAngle.Unknown)
        // Guided capture asks for one stance per view. Free practice accepts any
        // view the cues can be read from, but not a body turned part-way between.
        val facingCorrection = if (_guidedOptions.value.enabled) FacingGuide.correctionCue(skill, stageStance, observedView)
            else FacingGuide.freePracticeCue(observedView)
        // Facing is judged at rest and only once it has lasted; side-on rules are
        // kept through the swing; a brief unusable frame is skipped rather than
        // ending the rep. See LiveTrackingGate for why each rule exists.
        // (repCounter.currentPhase is null while a movement is in progress.)
        val decision = trackingGate.update(frame.timestampMs, estimate, facingCorrection,
            inRep = repCounter.currentPhase == null, limbVisibility = frame.torsoAndLimbVisibility())
        if (decision.outcome == LiveTrackingGate.Outcome.Skip) {
            _uiState.value = state.copy(quality = quality, orientationConfidence = estimate.confidence)
            return
        }
        val wrongView = decision.outcome == LiveTrackingGate.Outcome.WrongFacing
        if (decision.outcome != LiveTrackingGate.Outcome.Track) {
            interruptTracking()
            // Nothing is counted while the athlete faces the wrong way; they are
            // told which way to turn, on the card and aloud.
            val turn = facingCorrection.takeIf { wrongView }
            _uiState.value = state.copy(quality = quality, orientationConfidence = estimate.confidence,
                stageMetrics = stageMetrics, totalMetrics = totalMetrics, suspensionHint = turn?.full)
            publish(director.onTracking(frame.timestampMs, state.angle.takeIf { wrongView }, turn?.full), cue = turn)
            return
        }
        if (reliableSinceMs == null) reliableSinceMs = frame.timestampMs
        if (frame.timestampMs - requireNotNull(reliableSinceMs) < 1_000L) return
        // Shortly after the settle, so the recovery line is not cleared with the stale prompt.
        if (frame.timestampMs - requireNotNull(reliableSinceMs) >= 1_400L) {
            publish(director.onTrackingRestored(frame.timestampMs))
        }
        val frameLimit = if (_guidedOptions.value.enabled) MAX_GUIDED_RECORDED_FRAMES else MAX_RECORDED_FRAMES
        if (recordedFrames.size + chunks.sumOf { it.frames.size } >= frameLimit) {
            onStopRequested(force = true)
            return
        }
        if (stageStartMs == null) stageStartMs = frame.timestampMs
        recordedFrames += frame
        val counted = repCounter.onFrame(frame)
        repCounter.lastRejection?.let { onMovementNotCounted(it, frame.timestampMs) }
        _speechWindowOpen.value = repCounter.currentPhase != null
        val actualAngle = if (estimate.confidence >= 0.65f) estimate.angle else CameraAngle.Unknown
        _livePosture.value = postureCoach.update(frame, repCounter.currentPhase, actualAngle)
        // Live geometry remains visual; only completed reps may trigger corrective speech.
        if (counted) {
            repCounter.lastCompletedWindow?.let { window ->
                val assessment = postureCoach.assessCompletedRep(window,
                    if (_guidedOptions.value.enabled) state.angle else actualAngle)
                stageEvidence.add(assessment)
                stageMetrics = stageMetrics.completed(assessment.score?.roundToInt(), assessment.isAssessable, assessment.isGood)
                totalMetrics = totalMetrics.completed(assessment.score?.roundToInt(), assessment.isAssessable, assessment.isGood)
                idleTicks = 0
                idleSpoken = false
                _livePosture.value = postureCoach.updateCompletedRep(assessment, frame.timestampMs)
                val repAngle = if (_guidedOptions.value.enabled) state.angle else actualAngle
                val stageTarget = _guidedOptions.value.repsPerAngle.takeIf { _guidedOptions.value.enabled }
                val direction = director.onRep(assessment, frame.timestampMs, repAngle, _coachingPlan.value.focusCueIds,
                    com.volleyballlab.player.pose.analysis.RepContext(stageMetrics.reps, stageTarget, state.stageIndex,
                        stageMetrics.streak, stageMetrics.bestStreak, stageMetrics.validReps))
                // Prototype: an elbow clearly below this athlete's own best reps in
                // this view takes the turn only when the director has nothing to say
                // and this is not the quiet retry after a directed rep.
                val elbowTurn = if (elbowBaselineEnabled) {
                    (elbowBaseline.observe(window, repAngle to state.stageIndex) as? PersonalElbowBaseline.Check.Dropped)
                        ?.takeIf { it.announce && direction == null && _repFeedback.value?.directed != true }
                        ?.let {
                            elbowBaseline.announced()
                            CoachingEvent(0, CoachingEventType.Correction, CoachPhrasebook.ELBOW_BELOW_BEST,
                                frame.timestampMs, 1, repNumber = totalMetrics.reps)
                        }
                } else null
                val turn = direction ?: elbowTurn
                val feedback = PersonalizedLiveCoaching.feedback(
                    assessment, repAngle, _coachingPlan.value, totalMetrics.reps, stageMetrics.reps,
                    stageTarget, turn, previous = _repFeedback.value,
                )
                _repFeedback.value = feedback
                publish(CoachingEvent(0, feedback.type, feedback.spokenText, frame.timestampMs,
                    turn?.priority ?: 0, repNumber = totalMetrics.reps,
                    reference = ReferenceShow.Finish.takeIf { elbowTurn != null && direction == null }),
                    speak = !GuidedCaptureProgress.reachedTarget(_guidedOptions.value, stageMetrics),
                    directorId = direction?.id)
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
            if (next == null) finishWithClosing(endedEarly = false, finalRep = _repFeedback.value?.spokenText)
            else requestRotation(next)
        }
    }

    /**
     * A movement finished and was not the skill. Nothing is added to any count,
     * score or streak; the athlete is told the one change that would have made
     * it a rep.
     *
     * The card always shows it, because a movement that silently fails to count
     * reads as a broken counter. Speech is paced: the same reason is not repeated
     * within [NOT_COUNTED_REPEAT_MS], and two different ones are kept
     * [NOT_COUNTED_GAP_MS] apart, so a string of wrong attempts is coached
     * rather than narrated.
     */
    private fun onMovementNotCounted(fault: MovementFault, timestampMs: Long) {
        // They are moving, so "I'm waiting for a rep" would be the wrong thing to say.
        idleTicks = 0
        val last = lastNotCounted
        val sinceLast = last?.let { timestampMs - it.second }
        val speak = sinceLast == null || sinceLast >= NOT_COUNTED_REPEAT_MS ||
            last?.first != fault && sinceLast >= NOT_COUNTED_GAP_MS
        if (speak) lastNotCounted = fault to timestampMs
        // The finish position goes up with the words, unless the trouble was the
        // camera's view of them rather than where their body was.
        val picture = ReferenceShow.Finish.takeUnless {
            fault == MovementFault.ArmsNotVisible || fault == MovementFault.LegsNotVisible
        }
        publish(CoachingEvent(0, CoachingEventType.NotCounted, CoachPhrasebook.notCounted(fault), timestampMs, 1,
            reference = picture), speak = speak, cue = CoachPhrasebook.notCountedCue(fault))
    }

    /**
     * Every ordinary finish ends on a closing turn: what was counted, anything a
     * view recorded as resolved, and the one thing to carry forward. The
     * recording limit and forced stops skip it and stay immediate.
     */
    private fun finishWithClosing(endedEarly: Boolean, finalRep: String? = null) {
        val state = _uiState.value as? CaptureUiState.Recording ?: return
        if (state.finishingAfterRep) return
        // Record this view's note before the closing reads the notes.
        if (state.rotation == null) finishStage(state)
        rotationCountdownJob?.cancel()
        liveCoachingJob?.cancel()
        _speechWindowOpen.value = false
        closingEndedEarly = endedEarly
        val segments = listOfNotNull(finalRep) + SessionWrapUp.closing(_coachingPlan.value, totalMetrics.reps,
            totalMetrics.validReps, totalMetrics.bestStreak, director.stageNotes(), endedEarly)
        _uiState.value = state.copy(finishingAfterRep = true, closing = segments, rotation = null, blockedReason = null)
        // The local closing is always what is spoken. An AI carry-forward only upgrades the card.
        val evidence = stageEvidence.snapshot(skill, state.angle)
        if (!endedEarly && liveCoachingRepository != null && coachingPreferences.value.detailedAiCoachingEnabled &&
            evidence.reps.size >= 3) {
            val context = turnContext(LiveCoachingTurnContext.Turn.Closing, state.stageIndex, evidence.focus()?.cueId)
            liveCoachingJob = viewModelScope.launch {
                val advice = try {
                    kotlinx.coroutines.withTimeoutOrNull(8_000) { liveCoachingRepository.advise(evidence, false, context) }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                advice?.voicePath?.let { File(it).delete() }
                val current = _uiState.value as? CaptureUiState.Recording ?: return@launch
                if (advice != null && current.sessionId == state.sessionId && current.finishingAfterRep) {
                    _uiState.value = current.copy(closingAdvice = advice.copy(voicePath = null))
                }
            }
        }
        finalRepJob?.cancel()
        finalRepJob = viewModelScope.launch {
            // Visual-only sessions and lost speech callbacks must still finish.
            delay(if (voiceGuidanceEnabled) SpeechTiming.turnMs(segments) + CLOSING_GRACE_MS else 1_500)
            onFinalRepSpeechFinished(state.sessionId)
        }
    }

    /** Closed values only: an enum, a flag and catalog ids. Nothing about the person. */
    private fun turnContext(turn: LiveCoachingTurnContext.Turn, stageIndex: Int, focus: com.volleyballlab.player.domain.model.CueId?) =
        LiveCoachingTurnContext(turn, stageIndex.coerceIn(0, 2), _coachingPlan.value.experience,
            focus != null && focus in _coachingPlan.value.focusCueIds, director.stageCorrected())

    fun onFinalRepSpeechFinished(sessionId: String) {
        val state = _uiState.value as? CaptureUiState.Recording ?: return
        if (state.sessionId == sessionId && state.finishingAfterRep) completeRecording(closingEndedEarly)
    }

    /** The athlete confirmed "Save and end" on an incomplete guided session. */
    fun onEndEarlyConfirmed() = finishWithClosing(endedEarly = true)

    private fun flushChunk() {
        if (recordedFrames.isNotEmpty()) chunks += CaptureChunk(currentChunkStage, currentChunkAngle, recordedFrames.toList())
        recordedFrames.clear()
    }

    private fun interruptTracking() {
        _speechWindowOpen.value = false
        flushChunk()
        reliableSinceMs = null
        repCounter.resetSegment()
        director.interruptObservation()
        postureCoach.clearStale()
        _livePosture.value = LivePostureUi.Idle.copy(trackingLost = true)
        _coachingMessage.value = null
        _repFeedback.value = null
        stageMetrics = stageMetrics.interrupted()
        totalMetrics = totalMetrics.interrupted()
    }

    private fun finishStage(state: CaptureUiState.Recording) {
        flushChunk()
        val first = stageStartMs ?: return
        val last = chunks.lastOrNull { it.stageIndex == state.stageIndex }?.frames?.lastOrNull()?.timestampMs ?: first
        if (stageSummaries.none { it.stageIndex == state.stageIndex }) {
            stageSummaries += SessionAngleStage(
                state.stageIndex, state.angle, first, last, stageMetrics.reps, stageMetrics.validReps,
                stageMetrics.bestStreak, stageMetrics.estimatedFormScore,
            )
            director.endStage(state.angle, stageMetrics.reps, stageMetrics.validReps, stageMetrics.bestStreak)
        }
    }

    private fun requestRotation(index: Int) {
        val state = _uiState.value as? CaptureUiState.Recording ?: return
        _speechWindowOpen.value = false
        liveCoachingJob?.cancel()
        val evidence = stageEvidence.snapshot(skill, state.angle)
        stageEvidence.reset()
        val angle = GuidedCaptureProgress.angles[index]
        orientationGate.reset()
        trackingGate.reset()
        qualityValidator.reset()
        repCounter.reset()
        elbowBaseline.reset()
        postureCoach.reset()
        reliableSinceMs = null
        rotationElapsedMs = 0L
        // The hitting arm comes from the reps already seen. The one-armed skills need
        // that arm nearest the lens; until one has been observed the turn is only
        // suggested, and the other skills are measurable from either profile.
        stageStance = FacingGuide.stance(skill, angle, preferredSide)
        requiredSide = stageStance.requiredNearSide
        val direction = FacingGuide.instruction(skill, stageStance)
        // The target rep shares the turn's audio lane, so its count and verdict
        // cannot be cut off by the camera-angle instruction.
        val finalRep = _repFeedback.value?.takeIf {
            GuidedCaptureProgress.reachedTarget(_guidedOptions.value, stageMetrics)
        }
        // The break is a debrief: the count, one line about this view, and the one
        // measured takeaway, in the direction the measurements actually missed.
        val focus = evidence.focus()
        val takeaway = focus?.let { CoachPhrasebook.instructions(it.cueId, it.measured).first() }
        val debrief = SessionWrapUp.debrief(state.angle, stageMetrics.reps, stageMetrics.validReps,
            stageMetrics.estimatedFormScore != null, takeaway,
            director.heldAllStage(_coachingPlan.value.focusCueIds))
        val segments = listOfNotNull(finalRep?.spokenText, debrief)
        val message = (segments + direction).joinToString(" ")
        _coachingMessage.value = null
        _livePosture.value = LivePostureUi.Idle
        val detailed = coachingPreferences.value.detailedAiCoachingEnabled
        val localAdvice = if (focus != null && takeaway != null) LiveCoachingAdvice(focus.cueId, takeaway) else null
        val id = ++guidanceSequence
        // A clean view can still earn an AI praise turn, so a focus is not required.
        val load = detailed && liveCoachingRepository != null && evidence.reps.size >= 3
        val turnContext = turnContext(LiveCoachingTurnContext.Turn.Break, state.stageIndex, focus?.cueId)
        _uiState.value = state.copy(rotation = RotationUi(angle, index, message = message, instruction = direction,
            guidanceId = id, loadingCoaching = false, speechPending = voiceGuidanceEnabled, advice = localAdvice,
            required = GuidedCaptureProgress.isRequired(index), segments = segments, awaitingAdvice = load,
            facing = stageStance.facing))
        if (load) liveCoachingJob = viewModelScope.launch {
            val advice = try {
                kotlinx.coroutines.withTimeoutOrNull(8_000) { liveCoachingRepository.advise(evidence, false, turnContext) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            advice?.voicePath?.let { File(it).delete() }
            val current = _uiState.value as? CaptureUiState.Recording
            val rotation = current?.rotation?.takeIf { it.guidanceId == id }
            if (rotation == null || !captureForeground) return@launch
            val shown = advice?.copy(voicePath = null)
            _uiState.value = current.copy(rotation = rotation.copy(advice = shown ?: localAdvice, awaitingAdvice = false))
            // Late-join: spoken only if this turn's own speech is still running.
            // Otherwise the advice stays on the card and never gates the turn.
            if (shown != null && shown.aiGenerated && rotation.speechPending) lateAdviceChannel.trySend(id to shown.text)
        }
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
            if (!captureForeground || rotation.loadingCoaching || rotation.speechPending) return@launch
            director.beginStage()
            currentChunkAngle = rotation.requestedAngle
            currentChunkStage = rotation.stageIndex
            stageStartMs = null
            stageMetrics = LiveCaptureMetrics()
            stageEvidence.reset()
            idleTicks = 0
            totalMetrics = totalMetrics.interrupted()
            repCounter.reset()
            elbowBaseline.reset()
            postureCoach.reset()
            _coachingMessage.value = null
            _repFeedback.value = null
            reliableSinceMs = lastFrameMs
            lastNotCounted = null
            _uiState.value = state.copy(angle = rotation.requestedAngle, stageIndex = rotation.stageIndex,
                rotation = null, stageMetrics = stageMetrics, totalMetrics = totalMetrics,
                facing = rotation.facing)
            // After the screen has dropped the turn's own audio.
            delay(400)
            val resumed = _uiState.value as? CaptureUiState.Recording ?: return@launch
            if (captureForeground && resumed.rotation == null && !resumed.finishingAfterRep) {
                publish(CoachingEvent(0, CoachingEventType.RepMilestone, CoachPhrasebook.SAME_AGAIN, lastFrameMs ?: 0L, 0))
            }
        }
    }

    fun onRetryAngle() {
        val state = _uiState.value as? CaptureUiState.Recording ?: return
        val rotation = state.rotation ?: return
        rotationCountdownJob?.cancel()
        orientationGate.reset()
        rotationElapsedMs = 0L
        _uiState.value = state.copy(rotation = rotation.copy(elapsedMs = 0, secondsLeft = null,
            guidanceId = if (rotation.loadingCoaching) rotation.guidanceId else ++guidanceSequence,
            speechPending = voiceGuidanceEnabled))
    }

    fun onSkipAngle() {
        val state = _uiState.value as? CaptureUiState.Recording ?: return
        val rotation = state.rotation ?: return
        if (!GuidedCaptureProgress.canSkip(rotation.stageIndex)) {
            _uiState.value = state.copy(rotation = rotation.copy(blockedReason = "Side view is required for this review."))
            return
        }
        rotationCountdownJob?.cancel()
        val next = GuidedCaptureProgress.nextStage(rotation.stageIndex)
        if (next == null) finishWithClosing(endedEarly = false) else requestRotation(next)
    }

    /** Clear old landmarks and feedback when MediaPipe stops producing poses. */
    private suspend fun runPoseWatchdog() {
        while (true) {
            delay(HOLD_TICK_MS)
            if (ticksSincePose < POSE_STALE_TICKS) ticksSincePose++
            if (ticksSincePose < POSE_STALE_TICKS) continue

            if (_poseFrame.value != null) {
                _poseFrame.value = null
                // No athlete to stand the figure on; an old one must not linger.
                _referenceFigure.value = null
                figureTracker.reset()
                qualityValidator.reset()
            }
            val recording = _uiState.value as? CaptureUiState.Recording ?: continue
            if (recording.finishingAfterRep) continue
            if (recording.quality != PoseQuality.NotYetKnown) {
                _uiState.value = recording.copy(quality = PoseQuality.NotYetKnown)
            }
            if (!_livePosture.value.trackingLost && recording.rotation == null) {
                interruptTracking()
                publish(director.onTracking(lastFrameMs ?: 0L))
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
            openingFinished = CompletableDeferred()
            val brief = _coachingPlan.value.opening(skill,
                _guidedOptions.value.repsPerAngle.takeIf { _guidedOptions.value.enabled })
            _uiState.value = CaptureUiState.CountingDown(0, brief.joinToString(" "), brief)
            // Bounded by the same estimate that bounds the speech itself.
            kotlinx.coroutines.withTimeoutOrNull(SpeechTiming.turnMs(brief) + CLOSING_GRACE_MS) { openingFinished?.await() }
            openingFinished = null
            if (!captureForeground) { enterFraming(armAutoStart = false); return@launch }
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
        stageEvidence.reset()
        idleTicks = 0
        idleSpoken = false
        lastFrameRecordingElapsedMs = 0L
        currentChunkStage = 0
        currentChunkAngle = CameraAngle.Front
        stageStartMs = null
        reliableSinceMs = null
        trackingGate.reset()
        stageStance = FacingGuide.stance(skill, CameraAngle.Front)
        requiredSide = null
        preferredSide = null
        lastNotCounted = null
        director.reset()
        director.experience = _coachingPlan.value.experience
        instructions.reset(_coachingPlan.value.experience)
        _speechWindowOpen.value = false
        _coachingMessage.value = null
        _repFeedback.value = null
        recordingSessionId = UUID.randomUUID().toString()
        videoFinalization = CompletableDeferred()
        repCounter.reset()
        elbowBaseline.reset()
        postureCoach.reset()
        _livePosture.value = LivePostureUi.Idle
        recordingStartedAtEpochMs = System.currentTimeMillis()
        _uiState.value = CaptureUiState.Recording(
            sessionId = requireNotNull(recordingSessionId),
            elapsedMs = 0L,
            liveRepCount = 0,
            quality = PoseQuality.NotYetKnown,
        )
        publish(CoachingEvent(0, CoachingEventType.RepMilestone, CoachPhrasebook.GO, lastFrameMs ?: 0L, 0))

        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                delay(TIMER_TICK_MS)
                val elapsed = System.currentTimeMillis() - startedAt
                val state = _uiState.value as? CaptureUiState.Recording ?: break
                // One nudge per idle spell: tracked and ready, but no rep for a while.
                val waiting = state.rotation == null && !state.finishingAfterRep && captureForeground &&
                    reliableSinceMs != null && !_livePosture.value.trackingLost
                idleTicks = if (waiting) idleTicks + 1 else 0
                if (idleTicks >= IDLE_TICKS && !idleSpoken) {
                    idleSpoken = true
                    // Standing still earns nothing; say what a rep of this skill is.
                    publish(CoachingEvent(0, CoachingEventType.RepMilestone, CoachPhrasebook.idle(skill), lastFrameMs ?: 0L, 0,
                        reference = ReferenceShow.Demonstrate), cue = CoachPhrasebook.idleCue(skill))
                }
                val rotation = state.rotation?.let {
                    rotationElapsedMs += TIMER_TICK_MS
                    it.copy(elapsedMs = rotationElapsedMs)
                }
                _uiState.value = state.copy(elapsedMs = elapsed, rotation = rotation)
                val limit = GuidedCaptureProgress.recordingLimit(_guidedOptions.value)
                if (elapsed >= limit) {
                    onStopRequested(force = true)
                    break
                }
            }
        }
    }

    /** Stops recording and runs the post-hoc scoring pass. */
    fun onStopRequested(force: Boolean = false) {
        val state = _uiState.value as? CaptureUiState.Recording ?: return
        if (!force && _guidedOptions.value.enabled) {
            if (state.rotation?.required == true) {
                _uiState.value = state.copy(rotation = state.rotation.copy(blockedReason = "Turn sideways to continue, or save an incomplete session."))
                return
            }
            if (state.rotation == null) when (val decision = GuidedCaptureProgress.stopDecision(state.stageIndex, stageMetrics.reps, _guidedOptions.value)) {
                is GuidedCaptureProgress.StopDecision.ContinueToSide -> { finishStage(state); requestRotation(decision.stageIndex); return }
                is GuidedCaptureProgress.StopDecision.Blocked -> {
                    _uiState.value = state.copy(blockedReason = "${decision.repsRemaining} more reps from the side, then you can finish.")
                    return
                }
                GuidedCaptureProgress.StopDecision.Finish -> Unit
            }
        }
        // A forced stop, or a second tap during the closing, finishes at once.
        if (force || state.finishingAfterRep) completeRecording(endedEarly = force)
        else finishWithClosing(endedEarly = false)
    }

    private fun completeRecording(endedEarly: Boolean) {
        val state = _uiState.value as? CaptureUiState.Recording ?: return
        timerJob?.cancel()
        rotationCountdownJob?.cancel()
        liveCoachingJob?.cancel()
        finalRepJob?.cancel()
        if (state.rotation == null) finishStage(state)
        flushChunk()
        _coachingMessage.value = null
        _livePosture.value = LivePostureUi.Idle
        _uiState.value = CaptureUiState.Analysing

        pendingCompletion = CaptureCompletion(
            input = CaptureCompletionInput(
                sessionId = requireNotNull(recordingSessionId), skill = skill, drillId = drillId,
                assignmentId = assignmentId, startedAtEpochMs = recordingStartedAtEpochMs,
                chunks = chunks.toList(), guidedAngles = _guidedOptions.value.enabled,
                angleStages = stageSummaries.toList(), replayFrames = replayFrames.toList(),
                targetReps = _guidedOptions.value.repsPerAngle, endedEarly = endedEarly,
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
        liveCoachingJob?.cancel()
        finalRepJob?.cancel()
        speechChannel.close()
        hapticChannel.close()
        lateAdviceChannel.close()
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
        private const val IDLE_TICKS = 120
        private const val CLOSING_GRACE_MS = 2_000L
        private const val NOT_COUNTED_GAP_MS = 3_000L
        private const val NOT_COUNTED_REPEAT_MS = 8_000L

        /** How long each of the two positions is held when a rep is being demonstrated. */
        const val DEMONSTRATE_HOLD_MS = 1_400L

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
                    liveCoachingRepository = container.liveCoachingRepository,
                    profileRepository = container.profileRepository,
                )
            }
        }
    }
}
