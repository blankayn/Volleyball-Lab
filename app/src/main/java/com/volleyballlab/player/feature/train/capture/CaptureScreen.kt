package com.volleyballlab.player.feature.train.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.ui.coaching.CoachingAudio
import com.volleyballlab.player.ui.coaching.CoachingAudioStatus
import com.volleyballlab.player.pose.analysis.PoseGuide
import com.volleyballlab.player.pose.analysis.ReferenceMoment
import com.volleyballlab.player.ui.coaching.SpeechPriority
import com.volleyballlab.player.ui.coaching.SpeechStyle
import com.volleyballlab.player.pose.analysis.CoachingEventType
import com.volleyballlab.player.pose.analysis.CoachPhrasebook
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.volleyballlab.player.pose.PoseDetectorUnavailableException
import com.volleyballlab.player.pose.analysis.LivePostureUi
import com.volleyballlab.player.pose.quality.PoseQuality
import com.volleyballlab.player.ui.theme.LocalReduceMotion
import com.volleyballlab.player.ui.theme.VbButtonShape
import com.volleyballlab.player.ui.theme.VbMotion
import com.volleyballlab.player.pose.analysis.CuePlainWords
import com.volleyballlab.player.ui.component.CueGauge
import com.volleyballlab.player.ui.theme.VbTabularNumerals
import com.volleyballlab.player.ui.theme.vbColors
import com.volleyballlab.player.ui.theme.vbTween
import java.util.Locale

@Composable
fun CaptureScreen(
    skill: Skill,
    drillId: String,
    onSessionComplete: (sessionId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    assignmentId: String? = null,
) {
    com.volleyballlab.player.feature.train.drill.DrillCameraAccessGate(skill, drillId, onBack, modifier) {
        GrantedCaptureScreen(skill, drillId, onSessionComplete, onBack, modifier, assignmentId)
    }
}

@Composable
private fun GrantedCaptureScreen(
    skill: Skill,
    drillId: String,
    onSessionComplete: (sessionId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    assignmentId: String? = null,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        permissionRequested = true
    }

    if (!hasCameraPermission) {
        CameraPermissionGate(
            // Distinguishes "not asked yet" from "asked and refused". They need
            // different copy and different actions: one offers the system prompt,
            // the other can only send the user to settings, because Android will
            // not show the prompt again.
            previouslyDenied = permissionRequested,
            onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    )
                )
            },
            onBack = onBack,
            modifier = modifier,
        )
        return
    }

    val viewModel: CaptureViewModel = viewModel(
        key = "capture-${skill.name}-$drillId-$assignmentId",
        factory = CaptureViewModel.factory(skill, drillId, assignmentId),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val frame by viewModel.poseFrame.collectAsStateWithLifecycle()
    val livePosture by viewModel.livePosture.collectAsStateWithLifecycle()
    val coachContext by viewModel.coachContext.collectAsStateWithLifecycle()
    val guidedOptions by viewModel.guidedOptions.collectAsStateWithLifecycle()
    val coachingMessage by viewModel.coachingMessage.collectAsStateWithLifecycle()
    val coachingPlan by viewModel.coachingPlan.collectAsStateWithLifecycle()
    val repFeedback by viewModel.repFeedback.collectAsStateWithLifecycle()
    val speechWindowOpen by viewModel.speechWindowOpen.collectAsStateWithLifecycle()
    val referenceFigure by viewModel.referenceFigure.collectAsStateWithLifecycle()
    val preferences by viewModel.coachingPreferences.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val feedbackView = LocalView.current
    var muted by remember { mutableStateOf(false) }
    val submittedEvents = remember { mutableMapOf<String, Long>() }
    val audio = remember(context, feedbackView) {
        CoachingAudio(context, onValidRepHaptic = {
            feedbackView.performHapticFeedback(
                if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM
                else android.view.HapticFeedbackConstants.VIRTUAL_KEY,
            )
        }, onDropped = { key -> submittedEvents.remove(key)?.let(viewModel::onSpeechDropped) })
    }
    val audioStatus by audio.status.collectAsStateWithLifecycle()
    val isRecording = state is CaptureUiState.Recording
    // Analysing stays active so a closing sentence already being spoken can finish.
    val audioSessionActive = isRecording || state is CaptureUiState.Framing || state is CaptureUiState.CountingDown ||
        state is CaptureUiState.Analysing
    val isTurning = (state as? CaptureUiState.Recording)?.rotation != null
    var wasTurning by remember { mutableStateOf(false) }
    var wasTrackingLost by remember { mutableStateOf(false) }
    LaunchedEffect(audio, isTurning, livePosture.trackingLost) {
        // Drop a pending turn/tracking instruction once that guidance is obsolete.
        if (wasTurning && !isTurning) audio.stop()
        // On recovery, let the sentence being spoken finish rather than cutting it off.
        else if (wasTrackingLost && !livePosture.trackingLost) audio.stopAfterCurrent()
        wasTurning = isTurning
        wasTrackingLost = livePosture.trackingLost
    }
    DisposableEffect(audio, lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) viewModel.setCaptureForeground(false)
            if (event == Lifecycle.Event.ON_RESUME) viewModel.setCaptureForeground(true)
        }
        lifecycleOwner.lifecycle.addObserver(audio)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(audio)
            lifecycleOwner.lifecycle.removeObserver(observer)
            audio.close()
        }
    }
    LaunchedEffect(audio, preferences, muted, audioSessionActive) {
        audio.setPreferences(preferences)
        audio.setMuted(muted)
        audio.setSessionActive(audioSessionActive)
        viewModel.setVoiceGuidanceEnabled(preferences.voiceEnabled && !muted)
    }
    LaunchedEffect(audio, speechWindowOpen) {
        audio.setCorrectionWindow(speechWindowOpen)
    }
    val rotation = (state as? CaptureUiState.Recording)?.rotation
    DisposableEffect(audio, rotation?.guidanceId) {
        onDispose { audio.stop() }
    }
    LaunchedEffect(audio, rotation?.guidanceId, rotation?.loadingCoaching, rotation?.speechPending) {
        val turn = rotation ?: return@LaunchedEffect
        if (turn.loadingCoaching || !turn.speechPending || !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        viewModel.onRotationSpeechStarted(turn.guidanceId)
        val debrief = turn.segments.ifEmpty { listOf(turn.message.removeSuffix(turn.instruction).trim()) }
        // The instruction may wait briefly for AI advice, never longer than the bounded hold.
        audio.playGuidance(debrief, instruction = turn.instruction,
            lateJoinWaitMs = if (turn.awaitingAdvice) 1_500 else 0) {
            viewModel.onRotationSpeechFinished(turn.guidanceId)
        }
    }
    LaunchedEffect(viewModel, audio) {
        viewModel.lateAdviceSpeech.collect { (guidanceId, text) ->
            val current = (viewModel.uiState.value as? CaptureUiState.Recording)?.rotation
            // Joins the turn before its instruction, or stays on the card.
            if (current?.guidanceId == guidanceId && current.speechPending) audio.appendToGuidance(text)
        }
    }
    val finishingSessionId = (state as? CaptureUiState.Recording)?.takeIf { it.finishingAfterRep }?.sessionId
    LaunchedEffect(audio, finishingSessionId) {
        val id = finishingSessionId ?: return@LaunchedEffect
        val closing = (viewModel.uiState.value as? CaptureUiState.Recording)?.closing.orEmpty()
        audio.playGuidance(closing) { viewModel.onFinalRepSpeechFinished(id) }
    }
    val opening = (state as? CaptureUiState.CountingDown)?.instruction
    LaunchedEffect(opening) {
        if (opening != null) {
            val brief = (state as? CaptureUiState.CountingDown)?.segments.orEmpty().ifEmpty { listOf(opening) }
            if (preferences.voiceEnabled && !muted && audioStatus != CoachingAudioStatus.Unavailable && audioStatus != CoachingAudioStatus.Degraded) audio.playGuidance(brief) { viewModel.onOpeningFinished() }
            else { delay(2_500); viewModel.onOpeningFinished() }
        }
    }
    // Setup is coached too: the main framing problem once it has persisted, and
    // the moment the hold starts. The pacer's repeat rule prevents nagging.
    val framing = state as? CaptureUiState.Framing
    val framingIssue = framing?.quality?.primaryIssue
    val framingIssueText = framingIssue?.let { stringResource(it.message) }
    LaunchedEffect(audio, framingIssueText) {
        val text = framingIssueText ?: return@LaunchedEffect
        delay(1_500)
        audio.setCorrectionWindow(true)
        audio.submit(text, "framing:$text", SpeechPriority.Milestone)
    }
    val holdingStill = framing?.isHoldingStill == true
    LaunchedEffect(audio, holdingStill) {
        if (!holdingStill) return@LaunchedEffect
        audio.setCorrectionWindow(true)
        audio.submit(CoachPhrasebook.HOLD_STILL, "framing:hold", SpeechPriority.Milestone)
    }
    val countdown = (state as? CaptureUiState.CountingDown)?.secondsLeft ?: rotation?.secondsLeft
    LaunchedEffect(audio, countdown, rotation?.guidanceId) {
        countdown?.takeIf { it > 0 }?.let {
            audio.announceCountdown(it)
            if (preferences.hapticsEnabled) feedbackView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
    }
    LaunchedEffect(viewModel, audio, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch {
                var refusalSpeaking = false
                viewModel.coachingEvents.collect { event ->
                    val recording = viewModel.uiState.value as? CaptureUiState.Recording ?: return@collect
                    // The target rep is included in the complete angle/final turn.
                    if (event.repNumber != null && (recording.rotation != null || recording.finishingAfterRep)) return@collect
                    val delivery = speechDelivery(event.type, event.repNumber != null)
                    val priority = delivery.priority
                    if (priority == SpeechPriority.Rotation || priority == SpeechPriority.Tracking) audio.stop()
                    // A refusal still being spoken must not run into the count that follows it.
                    if (event.repNumber != null && refusalSpeaking) audio.stop()
                    refusalSpeaking = event.type == CoachingEventType.NotCounted
                    // Session lines that are not about a rep do not wait for the rep window.
                    audio.setCorrectionWindow(priority == SpeechPriority.Tracking || event.repNumber == null ||
                        viewModel.speechWindowOpen.value)
                    val key = event.repNumber?.let { "rep:${recording.sessionId}:$it" }
                        ?: "${event.type}:${event.text.lowercase()}"
                    // Remember which event this key carries, so a line that is
                    // never spoken can be handed back to the director.
                    if (submittedEvents.size > 32) submittedEvents.clear()
                    submittedEvents[key] = event.id
                    // A short cue is spoken once the full instruction has been heard;
                    // "Say again" still repeats the full one.
                    audio.submit(event.speech, key, priority, delivery.expiresInMs, delivery.style, repeatText = event.text)
                }
            }
            launch { viewModel.validRepEvents.collect { audio.feedbackValidRep() } }
        }
    }
    LaunchedEffect(coachingMessage) {
        val message = coachingMessage ?: return@LaunchedEffect
        delay(6_000)
        viewModel.clearCoachingMessage(message.id.toString())
    }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_FRONT) }

    LaunchedEffect(state) {
        (state as? CaptureUiState.Finished)?.let { onSessionComplete(it.sessionId) }
    }

    CaptureControls(
        state = state,
        livePosture = livePosture,
        coachContext = coachContext,
        guidedOptions = guidedOptions,
        coachingText = coachingMessage?.text,
        coachLine = coachingMessage?.takeIf { it.repNumber == null }?.text,
        notCounted = coachingMessage?.takeIf { it.type == CoachingEventType.NotCounted }?.text,
        onSayAgain = { audio.repeatLast() },
        coachingPlan = coachingPlan,
        repFeedback = repFeedback,
        voiceMuted = muted || !preferences.voiceEnabled,
        voiceStatus = when (audioStatus) {
            CoachingAudioStatus.Ready -> "Voice ready"
            CoachingAudioStatus.Initializing -> "Preparing voice…"
            else -> "Voice unavailable — visual coaching remains available"
        },
        audioUnavailable = audioStatus == CoachingAudioStatus.Unavailable || audioStatus == CoachingAudioStatus.Degraded,
        onToggleMute = {
            if (preferences.voiceEnabled) muted = !muted
            else android.widget.Toast.makeText(context, "Enable voice coaching in Profile", android.widget.Toast.LENGTH_SHORT).show()
        },
        onSpeechSettings = { runCatching { context.startActivity(CoachingAudio.settingsIntent()) } },
        onGuidedChanged = viewModel::setGuidedAngles,
        onRepTargetChanged = viewModel::setRepsPerAngle,
        dominantArm = preferences.dominantArm,
        onDominantArmChanged = viewModel::setDominantArm,
        onRetryAngle = viewModel::onRetryAngle,
        onSkipAngle = viewModel::onSkipAngle,
        onStart = viewModel::onStartRequested,
        onStop = { viewModel.onStopRequested() },
        onEndEarly = viewModel::onEndEarlyConfirmed,
        onCancelCountdown = viewModel::onCancelCountdown,
        onRetry = viewModel::onRetryDetector,
        onRetrySave = viewModel::onRetrySaveRequested,
        onUseDemo = viewModel::onUseDemoDetector,
        onSwitchCamera = {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
        },
        onBack = onBack,
        modifier = modifier.fillMaxSize(),
        cameraContent = {
            CameraPreview(
                lensFacing = lensFacing,
                onFrame = viewModel::onCameraFrame,
                recordingId = (state as? CaptureUiState.Recording)?.sessionId,
                onVideoFinalized = viewModel::onVideoFinalized,
                onVideoClock = viewModel::onVideoClock,
                modifier = Modifier.fillMaxSize(),
            )
            val currentFrame = frame
            val recording = state as? CaptureUiState.Recording
            // The whole-body reference and the single correction limb never share
            // the picture: one target at a time.
            val reference = referenceFigure
            val guide = if (reference == null && preferences.poseGuideEnabled && currentFrame != null &&
                recording != null && recording.rotation == null) {
                PoseGuide.limbs(currentFrame, livePosture, skill, recording.angle)
            } else emptyList()
            PoseOverlay(frame = currentFrame, posture = livePosture, guide = guide, reference = reference,
                modifier = Modifier.fillMaxSize())
            // Says what the gold drawing is, every time it is on screen.
            val legend = when {
                reference?.moment == ReferenceMoment.Start -> R.string.coaching_reference_start
                reference?.moment == ReferenceMoment.Finish -> R.string.coaching_reference_finish
                guide.isNotEmpty() -> R.string.coaching_guide_legend
                else -> null
            }
            if (legend != null && currentFrame != null) androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Text(stringResource(legend), color = Color(0xFFFFD87A),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.testTag("capture-reference-legend")
                        .background(Color.Black.copy(alpha = .65f)).padding(horizontal = 8.dp, vertical = 4.dp))
            }
        },
    )
}

internal data class SpeechDelivery(val priority: SpeechPriority, val style: SpeechStyle, val expiresInMs: Long)

/** How one coaching event is queued and voiced. A correction outranks a plain count. */
internal fun speechDelivery(type: CoachingEventType, isRep: Boolean): SpeechDelivery = when {
    type == CoachingEventType.Rotation -> SpeechDelivery(SpeechPriority.Rotation, SpeechStyle.Guidance, 15_000)
    type == CoachingEventType.Tracking -> SpeechDelivery(SpeechPriority.Tracking, SpeechStyle.Urgent, 5_000)
    isRep && type == CoachingEventType.Correction -> SpeechDelivery(SpeechPriority.Coaching, SpeechStyle.Correction, 6_000)
    isRep && type == CoachingEventType.Improvement -> SpeechDelivery(SpeechPriority.Coaching, SpeechStyle.Praise, 6_000)
    isRep -> SpeechDelivery(SpeechPriority.RepFeedback, SpeechStyle.Count, 3_000)
    // The same lane and priority as a count, so the newest outcome replaces an
    // older one, and it goes stale at once: a refusal heard after the next rep
    // has been added sounds like that rep was the wrong one.
    type == CoachingEventType.NotCounted -> SpeechDelivery(SpeechPriority.RepFeedback, SpeechStyle.Correction, 1_500)
    type == CoachingEventType.Improvement -> SpeechDelivery(SpeechPriority.Improvement, SpeechStyle.Praise, 5_000)
    type == CoachingEventType.RepMilestone -> SpeechDelivery(SpeechPriority.Milestone, SpeechStyle.Guidance, 5_000)
    else -> SpeechDelivery(SpeechPriority.Correction, SpeechStyle.Correction, 5_000)
}

@Composable
internal fun CountdownDisplay(secondsLeft: Int) {
    val reduceMotion = LocalReduceMotion.current
    AnimatedContent(
        targetState = secondsLeft,
        contentAlignment = Alignment.Center,
        transitionSpec = {
            // Each digit swells in and shrinks away, so the count reads as a
            // rhythm even in a noisy gym where the athlete cannot hear anything.
            val slow = VbMotion.tween<Float>(VbMotion.Medium, reduceMotion = reduceMotion)
            val quick = VbMotion.tween<Float>(VbMotion.Short, reduceMotion = reduceMotion)
            (scaleIn(slow, initialScale = 0.6f) + fadeIn(quick)) togetherWith
                (scaleOut(slow, targetScale = 1.4f) + fadeOut(quick))
        },
        label = "countdown",
    ) { value ->
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.displayLarge,
            color = Color.White,
        )
    }
}

/**
 * The hands-free start gate, drawn as a ring that fills while the athlete holds
 * their framing.
 *
 * Large, centred and numeric because the person reading it is standing several
 * metres away with no way to reach the phone. The ring answers the question a
 * bare number cannot - "is this thing actually counting, or have I broken it?" -
 * since it visibly empties the instant they drift out of frame.
 */
@Composable
internal fun HoldStillRing(state: CaptureUiState.Framing) {
    // Smooths the gate's 100 ms steps into continuous motion. Collapses to a snap
    // under reduce-motion, where the number still carries the whole message.
    val progress by animateFloatAsState(
        targetValue = state.holdProgress,
        animationSpec = vbTween(durationMillis = VbMotion.Short, easing = VbMotion.Standard),
        label = "holdProgress",
    )
    val ringColor = MaterialTheme.vbColors.cueGood
    val scrimColor = MaterialTheme.vbColors.cameraScrim
    val description = stringResource(R.string.cd_hold_still, state.holdSecondsLeft)

    Box(
        modifier = Modifier
            .size(HoldRingDiameter)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = HoldRingStroke.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2f
            val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
            val topLeft = Offset(inset, inset)

            // The same scrim every other overlay on this screen sits on. Without
            // it the ring is white-on-whatever, and a sunlit gym floor behind the
            // athlete is exactly the background that wins that fight.
            drawCircle(color = scrimColor, radius = size.minDimension / 2f)

            drawArc(
                color = Color.White.copy(alpha = 0.3f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            drawArc(
                color = ringColor,
                // From twelve o'clock, the way every timer the athlete has ever
                // seen runs.
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = state.holdSecondsLeft.toString(),
                style = MaterialTheme.typography.displayMedium.merge(VbTabularNumerals),
                color = Color.White,
            )
            Text(
                text = stringResource(R.string.capture_hold_still),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun AnalysingDisplay() {
    Surface(shape = MaterialTheme.shapes.large, color = LiveSessionPanel) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.result_analysing),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
        }
    }
}

@Composable
internal fun SaveFailedCard(onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.padding(24.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.capture_save_failed_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.capture_save_failed_body), textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry, shape = VbButtonShape) {
                Text(stringResource(R.string.capture_retry_save))
            }
        }
    }
}

@Composable
internal fun UnavailableCard(
    state: CaptureUiState.Unavailable,
    onRetry: () -> Unit,
    onUseDemo: () -> Unit,
) {
    Surface(
        modifier = Modifier.padding(24.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(if (state.reason == PoseDetectorUnavailableException.Reason.ModelMissing)
                    R.string.error_model_missing_title else R.string.error_pose_initialization_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (state.reason) {
                    PoseDetectorUnavailableException.Reason.ModelMissing ->
                        stringResource(R.string.error_model_missing_body)

                    PoseDetectorUnavailableException.Reason.InitialisationFailed ->
                        stringResource(R.string.error_pose_initialization_body)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry, shape = VbButtonShape) {
                Text(stringResource(R.string.error_generic_retry))
            }
            if (state.canUseDemoDetector) {
                TextButton(onClick = onUseDemo) {
                    Text(stringResource(R.string.capture_use_demo))
                }
                Text(
                    text = stringResource(R.string.capture_demo_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
internal fun GuidedSetupCard(
    options: GuidedCaptureOptions,
    angleReady: Boolean,
    onGuidedChanged: (Boolean) -> Unit,
    onRepTargetChanged: (Int) -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = LiveSessionPanel) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Guided angles", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text("Front → side", style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.75f))
                }
                Switch(checked = options.enabled, onCheckedChange = onGuidedChanged,
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = Color.White, checkedTrackColor = com.volleyballlab.player.ui.theme.RustPrimary40,
                        uncheckedThumbColor = Color.White, uncheckedTrackColor = Color.White.copy(alpha = 0.12f),
                    ))
            }
            if (options.enabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10).forEach { reps ->
                        FilterChip(selected = options.repsPerAngle == reps,
                            onClick = { onRepTargetChanged(reps) }, label = { Text("$reps reps / angle") },
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                labelColor = Color.White, selectedLabelColor = Color.White,
                                selectedContainerColor = Color.White.copy(alpha = 0.12f),
                            ))
                    }
                }
                Text(if (angleReady) "Front view ready" else "Face the camera, with your whole body visible",
                    style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
        }
    }
}

/**
 * The live posture correction card.
 *
 * One cue at a time, large enough to read from metres away. Green when every
 * measurable cue is inside its band, amber/red with the cue's own coaching tip
 * when one is not. The skeleton tint in [PoseOverlay] always agrees with this
 * card - same verdict, two channels - so the athlete gets colour peripherally
 * and words on a glance.
 *
 * The dot strip below shows every live cue's current band at a glance. It is
 * deliberately dots, not labels: naming four cues on every frame would be
 * unreadable mid-rep, while dots answer "how many things are off" without words.
 */
@Composable
internal fun LivePostureCard(
    posture: LivePostureUi,
    coachingText: String? = null,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.cd_live_posture)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = LiveSessionPanel,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.live_coach_eyebrow).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(6.dp))
            val cue = posture.displayedCue
            val reduceMotion = LocalReduceMotion.current
            AnimatedContent(
                targetState = posture,
                contentKey = { target ->
                    target.displayedCue?.id?.name ?: when {
                        target.trackingLost -> "lost"
                        target.modelWarmingUp -> "warming"
                        target.modelUncertain -> "uncertain"
                        else -> "good"
                    }
                },
                transitionSpec = {
                    val fade = VbMotion.tween<Float>(VbMotion.Short, reduceMotion = reduceMotion)
                    fadeIn(fade) togetherWith fadeOut(fade)
                },
                label = "livePostureMessage",
            ) { targetPosture ->
                val animatedCue = targetPosture.displayedCue
                when {
                    targetPosture.trackingLost && animatedCue == null -> Text(
                        text = stringResource(R.string.live_posture_tracking_lost),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.vbColors.cueWarn,
                        textAlign = TextAlign.Center,
                    )

                    targetPosture.modelWarmingUp && animatedCue == null -> Text(
                        text = stringResource(R.string.live_posture_model_warming),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.vbColors.cueWarn,
                        textAlign = TextAlign.Center,
                    )

                    targetPosture.modelUncertain && animatedCue == null -> Text(
                        text = stringResource(R.string.live_posture_model_uncertain),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.vbColors.cueWarn,
                        textAlign = TextAlign.Center,
                    )

                    animatedCue == null -> Text(
                        text = if (targetPosture.isGood) "Measured cues look steady" else "Watching your next rep",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (targetPosture.isGood) MaterialTheme.vbColors.cueGood else Color.White,
                        textAlign = TextAlign.Center,
                    )

                    else -> Text(
                        text = stringResource(animatedCue.label),
                        style = MaterialTheme.typography.titleLarge,
                        color = if ((targetPosture.score ?: 1f) < SEVERE_LIVE_SCORE) {
                            Color(0xFFFF6B5E)
                        } else {
                            MaterialTheme.vbColors.cueWarn
                        },
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            when {
                posture.trackingLost && cue == null -> if (coachingText != null) Text(
                    coachingText, style = MaterialTheme.typography.titleMedium, color = Color.White,
                    textAlign = TextAlign.Center,
                )

                posture.modelWarmingUp && cue == null -> Text(
                    text = stringResource(R.string.live_posture_model_warming_sub),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                )

                posture.modelUncertain && cue == null -> Text(
                    text = stringResource(R.string.live_posture_model_uncertain_sub),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                )

                cue == null -> Text(
                        text = coachingText ?: "I’ll check the cues I can see as you move.",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                )

                else -> {
                    Text(
                        text = coachingText ?: stringResource(cue.coachingTip),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    posture.measured?.let { measured ->
                        val reading = CuePlainWords.read(cue, measured)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = reading.line,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(4.dp))
                        CueGauge(reading, Modifier.widthIn(max = 220.dp), onDark = true)
                    }
                }
            }
            if (posture.statuses.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LiveCueDots(posture)
            }
        }
    }
}

@Composable
private fun LiveCueDots(posture: LivePostureUi, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        posture.statuses.forEach { status ->
            val dot = when (val score = status.score) {
                null -> Color.White.copy(alpha = 0.25f)
                in 0f..SEVERE_LIVE_SCORE -> Color(0xFFFF6B5E)
                in 0f..0.80f -> MaterialTheme.vbColors.cueWarn
                else -> MaterialTheme.vbColors.cueGood
            }
            val active = status.cue.id == posture.displayedCue?.id
            Box(
                Modifier
                    .size(if (active) 12.dp else 8.dp)
                    .background(dot, CircleShape),
            )
        }
    }
}

private const val SEVERE_LIVE_SCORE = 0.35f

/**
 * The live framing feedback.
 *
 * Shows one instruction at a time, chosen by [PoseQuality.primaryIssue]. Stacking
 * every simultaneous problem would read as the app being broken rather than as
 * something the athlete can fix, and issues are ordered so that acting on the top
 * one usually clears the rest.
 *
 * The footer line is what tells an athlete who is out of frame that fixing their
 * position is the whole job - that there is no walk back to the phone waiting for
 * them afterwards.
 */
@Composable
internal fun FramingGuidance(quality: PoseQuality, autoStartArmed: Boolean) {
    val issue = quality.primaryIssue
    val message = if (issue == null) {
        stringResource(R.string.quality_ok)
    } else {
        stringResource(issue.message)
    }

    Surface(shape = MaterialTheme.shapes.large, color = LiveSessionPanel) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val reduceMotion = LocalReduceMotion.current
            AnimatedContent(
                targetState = message,
                transitionSpec = {
                    val fade = VbMotion.tween<Float>(VbMotion.Short, reduceMotion = reduceMotion)
                    fadeIn(fade) togetherWith fadeOut(fade)
                },
                label = "framingMessage",
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (issue == null) MaterialTheme.vbColors.cueGood else Color.White,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(10.dp))
            // A meter rather than a pass/fail light: it shows which direction to
            // move and how far, which "move back" alone does not.
            LinearProgressIndicator(
                progress = { quality.bodyCoverage.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = if (issue == null) {
                    MaterialTheme.vbColors.cueGood
                } else {
                    MaterialTheme.vbColors.cueWarn
                },
                trackColor = Color.White.copy(alpha = 0.25f),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(
                    if (autoStartArmed) {
                        R.string.capture_auto_start_hint
                    } else {
                        R.string.capture_auto_start_off_hint
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CameraPermissionGate(
    previouslyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.camera_permission_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            // States plainly that frames stay on the device. This is a real
            // commitment the app keeps - analysis is entirely on-device - and it
            // is the question an athlete filming themselves actually has.
            text = stringResource(
                if (previouslyDenied) {
                    R.string.camera_permission_denied_body
                } else {
                    R.string.camera_permission_body
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = if (previouslyDenied) onOpenSettings else onRequest,
            shape = VbButtonShape,
        ) {
            Text(
                stringResource(
                    if (previouslyDenied) {
                        R.string.camera_permission_settings
                    } else {
                        R.string.camera_permission_grant
                    }
                )
            )
        }
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.capture_cancel))
        }
    }
}

/** Sized to stay readable from the far side of a court, not to fit a phone held in the hand. */
private val HoldRingDiameter = 200.dp
private val HoldRingStroke = 8.dp

internal fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / 1_000
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
