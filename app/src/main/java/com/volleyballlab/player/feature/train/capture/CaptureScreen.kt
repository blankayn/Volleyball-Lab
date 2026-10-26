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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.CueUnit
import com.volleyballlab.player.domain.model.FormCue
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.ui.coaching.CoachingAudio
import com.volleyballlab.player.ui.coaching.CoachingAudioStatus
import com.volleyballlab.player.ui.coaching.SpeechPriority
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.volleyballlab.player.pose.PoseDetectorUnavailableException
import com.volleyballlab.player.pose.analysis.LivePostureUi
import com.volleyballlab.player.pose.quality.PoseQuality
import com.volleyballlab.player.ui.component.AnimatedCounter
import com.volleyballlab.player.ui.theme.LocalReduceMotion
import com.volleyballlab.player.ui.theme.VbButtonShape
import com.volleyballlab.player.ui.theme.VbMotion
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
    val preferences by viewModel.coachingPreferences.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val feedbackView = LocalView.current
    var muted by remember { mutableStateOf(false) }
    val audio = remember(context, feedbackView) {
        CoachingAudio(context) {
            feedbackView.performHapticFeedback(
                if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM
                else android.view.HapticFeedbackConstants.VIRTUAL_KEY,
            )
        }
    }
    val audioStatus by audio.status.collectAsStateWithLifecycle()
    val isRecording = state is CaptureUiState.Recording
    val isTurning = (state as? CaptureUiState.Recording)?.rotation != null
    var wasTurning by remember { mutableStateOf(false) }
    var wasTrackingLost by remember { mutableStateOf(false) }
    LaunchedEffect(audio, isTurning, livePosture.trackingLost) {
        // Drop a pending turn/tracking instruction once that guidance is obsolete.
        if (wasTurning && !isTurning || wasTrackingLost && !livePosture.trackingLost) audio.stop()
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
    LaunchedEffect(audio, preferences, muted, isRecording) {
        audio.setPreferences(preferences)
        audio.setMuted(muted)
        audio.setSessionActive(isRecording)
    }
    LaunchedEffect(viewModel, audio, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch {
                viewModel.coachingEvents.collect { event ->
                    val kind = event.type.name.lowercase()
                    val priority = when {
                        "rotation" in kind -> SpeechPriority.Rotation
                        "tracking" in kind -> SpeechPriority.Tracking
                        "improv" in kind -> SpeechPriority.Improvement
                        "milestone" in kind -> SpeechPriority.Milestone
                        else -> SpeechPriority.Correction
                    }
                    if (priority == SpeechPriority.Rotation || priority == SpeechPriority.Tracking) audio.stop()
                    audio.submit(event.text, "${event.type}:${event.text.lowercase()}", priority,
                        expiresInMs = if (priority == SpeechPriority.Rotation) 15_000 else 5_000)
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

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(
            lensFacing = lensFacing,
            onFrame = viewModel::onCameraFrame,
            recordingId = (state as? CaptureUiState.Recording)?.sessionId,
            onVideoFinalized = viewModel::onVideoFinalized,
            onVideoClock = viewModel::onVideoClock,
            modifier = Modifier.fillMaxSize(),
        )
        PoseOverlay(frame = frame, posture = livePosture, modifier = Modifier.fillMaxSize())

        CaptureControls(
            state = state,
            livePosture = livePosture,
            coachContext = coachContext,
            guidedOptions = guidedOptions,
            coachingText = coachingMessage?.text,
            voiceMuted = muted || !preferences.voiceEnabled,
            audioUnavailable = audioStatus == CoachingAudioStatus.Unavailable,
            onToggleMute = {
                if (preferences.voiceEnabled) muted = !muted
                else android.widget.Toast.makeText(context, "Enable voice coaching in Profile", android.widget.Toast.LENGTH_SHORT).show()
            },
            onSpeechSettings = { runCatching { context.startActivity(CoachingAudio.settingsIntent()) } },
            onGuidedChanged = viewModel::setGuidedAngles,
            onRepTargetChanged = viewModel::setRepsPerAngle,
            onRetryAngle = viewModel::onRetryAngle,
            onSkipAngle = viewModel::onSkipAngle,
            onStart = viewModel::onStartRequested,
            onStop = viewModel::onStopRequested,
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
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun CaptureControls(
    state: CaptureUiState,
    livePosture: LivePostureUi,
    coachContext: LiveCoachContext,
    guidedOptions: GuidedCaptureOptions,
    coachingText: String?,
    voiceMuted: Boolean,
    audioUnavailable: Boolean,
    onToggleMute: () -> Unit,
    onSpeechSettings: () -> Unit,
    onGuidedChanged: (Boolean) -> Unit,
    onRepTargetChanged: (Int) -> Unit,
    onRetryAngle: () -> Unit,
    onSkipAngle: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancelCountdown: () -> Unit,
    onRetry: () -> Unit,
    onUseDemo: () -> Unit,
    onSwitchCamera: () -> Unit,
    onBack: () -> Unit,
    onRetrySave: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            TopBar(
                state = state,
                onBack = onBack,
                onSwitchCamera = onSwitchCamera,
                onCancelCountdown = onCancelCountdown,
                voiceMuted = voiceMuted,
                onToggleMute = onToggleMute,
            )

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (state) {
                    is CaptureUiState.Recording -> if (state.rotation != null) {
                        RotationCard(state.rotation, onRetryAngle, onSkipAngle, onStop)
                    } else LivePostureCard(posture = livePosture, coachingText = state.suspensionHint ?: coachingText,
                        modifier = Modifier.padding(horizontal = 24.dp))
                    is CaptureUiState.Analysing -> AnalysingDisplay()
                    is CaptureUiState.SaveFailed -> SaveFailedCard(onRetrySave)
                    is CaptureUiState.Unavailable -> UnavailableCard(
                        state = state,
                        onRetry = onRetry,
                        onUseDemo = onUseDemo,
                    )

                    else -> Unit
                }
            }

            BottomBar(
                state = state,
                coachContext = coachContext,
                guidedOptions = guidedOptions,
                audioUnavailable = audioUnavailable,
                onSpeechSettings = onSpeechSettings,
                onGuidedChanged = onGuidedChanged,
                onRepTargetChanged = onRepTargetChanged,
                onStart = onStart,
                onStop = onStop,
            )
        }

        // Anchor both start cues to the camera's centre, independently of the
        // changing top/bottom controls and their widths.
        when (state) {
            is CaptureUiState.Framing -> if (state.isHoldingStill) HoldStillRing(state)
            is CaptureUiState.CountingDown -> CountdownDisplay(state.secondsLeft)
            else -> Unit
        }
    }
}

@Composable
private fun TopBar(
    state: CaptureUiState,
    onBack: () -> Unit,
    onSwitchCamera: () -> Unit,
    onCancelCountdown: () -> Unit,
    voiceMuted: Boolean,
    onToggleMute: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = if (state is CaptureUiState.CountingDown) onCancelCountdown else onBack,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.capture_cancel),
                tint = Color.White,
            )
        }
        Spacer(Modifier.weight(1f))
        if (state is CaptureUiState.Recording) RecordingIndicator(state.elapsedMs)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onToggleMute) {
            Icon(if (voiceMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = if (voiceMuted) "Unmute voice coaching" else "Mute voice coaching",
                tint = Color.White)
        }
        // Hidden during recording: switching lenses mid-drill rebinds the camera,
        // which drops frames and resets tracking, silently corrupting the session.
        AnimatedVisibility(visible = state is CaptureUiState.Framing) {
            IconButton(onClick = onSwitchCamera) {
                Icon(
                    imageVector = Icons.Rounded.Cameraswitch,
                    contentDescription = stringResource(R.string.capture_switch_camera),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun RecordingIndicator(elapsedMs: Long) {
    val transition = rememberInfiniteTransition(label = "recordingPulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = VbMotion.Standard),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recordingPulseAlpha",
    )

    Surface(
        shape = CircleShape,
        color = MaterialTheme.vbColors.cameraScrim,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .alpha(pulse)
                    .background(MaterialTheme.colorScheme.error, CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = formatElapsed(elapsedMs),
                style = MaterialTheme.typography.titleMedium.merge(VbTabularNumerals),
                color = Color.White,
            )
        }
    }
}

@Composable
private fun CountdownDisplay(secondsLeft: Int) {
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
private fun HoldStillRing(state: CaptureUiState.Framing) {
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
private fun AnalysingDisplay() {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.vbColors.cameraScrim) {
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
private fun SaveFailedCard(onRetry: () -> Unit) {
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
private fun UnavailableCard(
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
                text = stringResource(R.string.error_model_missing_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (state.reason) {
                    PoseDetectorUnavailableException.Reason.ModelMissing ->
                        stringResource(R.string.error_model_missing_body)

                    PoseDetectorUnavailableException.Reason.InitialisationFailed ->
                        stringResource(R.string.error_camera_title)
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
private fun GuidedSetupCard(
    options: GuidedCaptureOptions,
    angleReady: Boolean,
    onGuidedChanged: (Boolean) -> Unit,
    onRepTargetChanged: (Int) -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.vbColors.cameraScrim) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Guided angles", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text("Front → side → rear", style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.75f))
                }
                Switch(checked = options.enabled, onCheckedChange = onGuidedChanged)
            }
            if (options.enabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10).forEach { reps ->
                        FilterChip(selected = options.repsPerAngle == reps,
                            onClick = { onRepTargetChanged(reps) }, label = { Text("$reps reps / angle") })
                    }
                }
                Text(if (angleReady) "Front view ready" else "Face the camera, with your whole body visible",
                    style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
        }
    }
}

@Composable
private fun RotationCard(rotation: RotationUi, onRetry: () -> Unit, onSkip: () -> Unit, onFinish: () -> Unit) {
    Surface(modifier = Modifier.padding(horizontal = 24.dp), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.vbColors.cameraScrim) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${rotation.requestedAngle.name} view", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Spacer(Modifier.height(12.dp))
            Text(rotation.message, color = Color.White, style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            if (rotation.secondsLeft != null) CountdownDisplay(rotation.secondsLeft)
            else Text(if (rotation.timedOut) "The angle is still unclear. Adjust your position, retry, or skip this view."
                else "Checking your angle. Hold steady once you’ve turned.",
                color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
            if (rotation.timedOut) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onRetry) { Text("Retry", color = Color.White) }
                    TextButton(onClick = onSkip) { Text("Skip angle", color = Color.White) }
                }
            }
            TextButton(onClick = onFinish) { Text("Finish session", color = Color.White) }
        }
    }
}

@Composable
private fun BottomBar(
    state: CaptureUiState,
    coachContext: LiveCoachContext,
    guidedOptions: GuidedCaptureOptions,
    audioUnavailable: Boolean,
    onSpeechSettings: () -> Unit,
    onGuidedChanged: (Boolean) -> Unit,
    onRepTargetChanged: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            is CaptureUiState.Framing -> {
                GuidedSetupCard(guidedOptions, state.angleReady, onGuidedChanged, onRepTargetChanged)
                Spacer(Modifier.height(10.dp))
                if (audioUnavailable) TextButton(onClick = onSpeechSettings) {
                    Text("Voice unavailable · Open speech settings", color = Color.White)
                }
                FramingGuidance(quality = state.quality, autoStartArmed = state.autoStartArmed)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onStart,
                    enabled = state.quality.isAcceptable && state.angleReady,
                    shape = VbButtonShape,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(stringResource(R.string.capture_start))
                }
            }

            is CaptureUiState.Recording -> {
                // Who is coaching this set: the linked human coach when there is
                // one (name + their drill + their latest note), else the
                // on-device coach. One line, above the count, so the athlete
                // knows whose instructions the card above is speaking with.
                LiveCoachStrip(
                    context = coachContext,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (guidedOptions.enabled) {
                        "${state.angle.name} view · ${state.stageMetrics.reps}/${guidedOptions.repsPerAngle} reps"
                    } else "${state.totalMetrics.reps} completed reps",
                    style = MaterialTheme.typography.titleMedium, color = Color.White,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AnimatedCounter(
                        value = state.totalMetrics.validReps,
                        style = MaterialTheme.typography.headlineSmall.copy(color = Color.White),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "good reps · streak ${state.totalMetrics.streak}\nBest ${state.totalMetrics.bestStreak} · form ${state.totalMetrics.estimatedFormScore?.let { "$it/100" } ?: "—"} (estimated)",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onStop,
                    shape = VbButtonShape,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.capture_stop))
                }
            }

            else -> Unit
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
private fun LivePostureCard(
    posture: LivePostureUi,
    coachingText: String? = null,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.cd_live_posture)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.vbColors.cameraScrim,
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
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
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = formatLiveMeasured(cue, measured),
                            style = MaterialTheme.typography.labelMedium.merge(VbTabularNumerals),
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center,
                        )
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

/**
 * Who is in the session with the athlete, in one strip.
 *
 * A linked human coach outranks the device: name, the drill they set, and their
 * latest note when there is one. With no link, the strip says the on-device
 * coach is judging - so the card above is never a voice from nowhere, and the
 * copy never implies a person is watching live when nobody is.
 */
@Composable
private fun LiveCoachStrip(
    context: LiveCoachContext,
    modifier: Modifier = Modifier,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.vbColors.cameraScrim) {
        Column(
            modifier = modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val name = context.displayName
            if (name == null) {
                Text(
                    text = stringResource(R.string.live_coach_device),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.live_coach_device_sub),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    text = stringResource(R.string.live_coach_human_title, name),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                context.assignment?.let { assignment ->
                    Text(
                        text = stringResource(
                            R.string.live_coach_assignment,
                            assignment.title,
                            assignment.targetSets,
                            assignment.targetReps,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                    )
                }
                context.latestNote?.text?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(
                        text = "“${note.take(COACH_NOTE_PREVIEW)}”",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.vbColors.cueGood,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private fun formatLiveMeasured(cue: FormCue, measured: Float): String = when (cue.unit) {
    CueUnit.Degrees -> "${measured.roundToInt()}° · ${cue.target.start.roundToInt()}–${cue.target.endInclusive.roundToInt()}°"
    CueUnit.Metres -> String.format(Locale.US, "%.2f m · %.2f–%.2f m", measured, cue.target.start, cue.target.endInclusive)
    CueUnit.Ratio -> "${(measured * 100).roundToInt()}% · ${(cue.target.start * 100).roundToInt()}–${(cue.target.endInclusive * 100).roundToInt()}%"
}

private const val SEVERE_LIVE_SCORE = 0.35f
private const val COACH_NOTE_PREVIEW = 90

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
private fun FramingGuidance(quality: PoseQuality, autoStartArmed: Boolean) {
    val issue = quality.primaryIssue
    val message = if (issue == null) {
        stringResource(R.string.quality_ok)
    } else {
        stringResource(issue.message)
    }

    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.vbColors.cameraScrim) {
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

private fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / 1_000
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
