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
            PoseOverlay(frame = frame, posture = livePosture, modifier = Modifier.fillMaxSize())
        },
    )
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
                    Text("Front → side → rear", style = MaterialTheme.typography.labelMedium,
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

private fun formatLiveMeasured(cue: FormCue, measured: Float): String = when (cue.unit) {
    CueUnit.Degrees -> "${measured.roundToInt()}° · ${cue.target.start.roundToInt()}–${cue.target.endInclusive.roundToInt()}°"
    CueUnit.Metres -> String.format(Locale.US, "%.2f m · %.2f–%.2f m", measured, cue.target.start, cue.target.endInclusive)
    CueUnit.Ratio -> "${(measured * 100).roundToInt()}% · ${(cue.target.start * 100).roundToInt()}–${(cue.target.endInclusive * 100).roundToInt()}%"
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
