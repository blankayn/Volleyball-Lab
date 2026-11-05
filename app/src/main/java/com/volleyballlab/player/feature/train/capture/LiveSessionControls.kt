package com.volleyballlab.player.feature.train.capture

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.pose.analysis.LivePostureUi
import com.volleyballlab.player.ui.theme.LocalReduceMotion
import com.volleyballlab.player.ui.theme.RustPrimary40
import com.volleyballlab.player.ui.theme.VbTabularNumerals

internal val LiveSessionPanel = Color(0xFF25303C)
private val LiveSessionOutline = Color(0xFF53606D)
private val LiveSessionMuted = Color(0xFFBCC7D3)
private val LiveSessionProgress = Color(0xFF8BD3C0)

/** Camera and skeleton share one viewport; coaching never covers the athlete. */
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
    cameraContent: @Composable () -> Unit = {},
) {
    BoxWithConstraints(
        modifier = modifier.background(Brush.verticalGradient(listOf(Color(0xFF10151D), Color(0xFF1B2634))))
            .systemBarsPadding(),
    ) {
        // Long coaching notes and larger accessibility text scroll in their own
        // panel, keeping both the camera and the finish action on screen.
        val compact = maxHeight < 700.dp || LocalDensity.current.fontScale > 1.15f
        val guidanceHeight = if (compact) (maxHeight * 0.23f).coerceIn(100.dp, 160.dp)
            else (maxHeight * 0.36f).coerceAtLeast(110.dp)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SessionHeader(state, voiceMuted, onToggleMute, onBack, onCancelCountdown, compact)

            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("capture-viewfinder"),
                shape = RoundedCornerShape(22.dp), color = Color.Black,
                border = BorderStroke(1.dp, LiveSessionOutline),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // Keep this slot mounted through framing, counting, recording
                    // and turns. State updates must not recreate the camera.
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        // Capture uses a 3:4 sensor viewport. Fit that whole image
                        // here rather than cropping off feet on shorter screens.
                        val cameraWidth = minOf(maxWidth, maxHeight * 0.75f)
                        Box(Modifier.size(cameraWidth, cameraWidth / 0.75f).testTag("capture-camera-content")) { cameraContent() }
                    }
                    val recording = state as? CaptureUiState.Recording
                    val view = recording?.rotation?.requestedAngle ?: recording?.angle ?: CameraAngle.Front
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                        shape = RoundedCornerShape(12.dp), color = Color(0xBB111821),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                    ) {
                        Text(
                            if (guidedOptions.enabled) viewLabel(view) else stringResource(R.string.capture_free_practice),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelLarge, color = Color.White,
                        )
                    }
                    if (state is CaptureUiState.Framing) {
                        SessionIconButton(
                            onClick = onSwitchCamera,
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        ) {
                            Icon(Icons.Rounded.Cameraswitch, stringResource(R.string.capture_switch_camera))
                        }
                    }
                    when (state) {
                        is CaptureUiState.Framing -> if (state.isHoldingStill) HoldStillRing(state)
                        is CaptureUiState.CountingDown -> CountdownDisplay(state.secondsLeft)
                        is CaptureUiState.Recording -> state.rotation?.secondsLeft?.let { CountdownDisplay(it) }
                        CaptureUiState.Initialising -> CircularProgressIndicator(color = Color.White)
                        CaptureUiState.Analysing -> AnalysingDisplay()
                        CaptureUiState.SaveFailed -> SaveFailedCard(onRetrySave)
                        is CaptureUiState.Unavailable -> UnavailableCard(state, onRetry, onUseDemo)
                        is CaptureUiState.Finished -> Unit
                    }
                }
            }

            when (state) {
                is CaptureUiState.Recording -> {
                    SessionRepStats(state, guidedOptions, compact)
                    Column(Modifier.fillMaxWidth().heightIn(max = guidanceHeight).clip(RoundedCornerShape(20.dp))
                        .verticalScroll(rememberScrollState())) {
                        if (state.rotation != null) {
                            SessionRotationGuidance(state.rotation, onRetryAngle, onSkipAngle)
                        } else {
                            LivePostureCard(livePosture, state.suspensionHint ?: coachingText, Modifier.fillMaxWidth())
                        }
                        if (compact) SessionMetricsLine(state, Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                        if (coachContext.hasHumanCoach) SessionCoachContext(coachContext)
                    }
                    SessionPrimaryButton(onStop, stringResource(R.string.capture_finish_session))
                }
                is CaptureUiState.Framing -> {
                    Column(Modifier.fillMaxWidth().heightIn(max = guidanceHeight).clip(RoundedCornerShape(20.dp))
                        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GuidedSetupCard(guidedOptions, state.angleReady, onGuidedChanged, onRepTargetChanged)
                        FramingGuidance(state.quality, state.autoStartArmed)
                        if (audioUnavailable) TextButton(onClick = onSpeechSettings) {
                            Text(stringResource(R.string.capture_speech_settings), color = Color.White)
                        }
                    }
                    SessionPrimaryButton(onStart, stringResource(R.string.capture_start),
                        enabled = state.quality.isAcceptable && state.angleReady)
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun SessionHeader(
    state: CaptureUiState,
    voiceMuted: Boolean,
    onToggleMute: () -> Unit,
    onBack: () -> Unit,
    onCancelCountdown: () -> Unit,
    compact: Boolean,
) {
    Box(Modifier.fillMaxWidth().height(if (compact) 72.dp else 82.dp), contentAlignment = Alignment.Center) {
        SessionIconButton(
            onClick = if (state is CaptureUiState.CountingDown) onCancelCountdown else onBack,
            modifier = Modifier.align(Alignment.CenterStart),
        ) { Icon(Icons.Rounded.Close, stringResource(R.string.capture_cancel)) }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.capture_live_title), style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                color = Color.White, fontWeight = FontWeight.SemiBold)
            if (state is CaptureUiState.Recording) SessionTimer(state.elapsedMs)
            else Text(stringResource(if (state is CaptureUiState.CountingDown) R.string.capture_get_ready
                else R.string.capture_setup_label), style = MaterialTheme.typography.labelMedium, color = LiveSessionMuted)
        }
        SessionIconButton(onToggleMute, Modifier.align(Alignment.CenterEnd)) {
            Icon(if (voiceMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                stringResource(if (voiceMuted) R.string.capture_unmute else R.string.capture_mute))
        }
    }
}

@Composable
private fun SessionIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp).background(Color.White.copy(alpha = 0.08f), CircleShape),
        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White), content = content)
}

@Composable
private fun SessionTimer(elapsedMs: Long) {
    val reduceMotion = LocalReduceMotion.current
    val pulse by rememberInfiniteTransition(label = "recordingPulse").animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "recordingPulseAlpha")
    Surface(shape = CircleShape, color = Color(0xFF111821), border = BorderStroke(1.dp, LiveSessionOutline)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(8.dp).alpha(if (reduceMotion) 1f else pulse).background(Color(0xFFFF625B), CircleShape))
            Text(formatElapsed(elapsedMs), style = MaterialTheme.typography.titleMedium.merge(VbTabularNumerals), color = Color.White)
        }
    }
}

@Composable
private fun SessionRepStats(state: CaptureUiState.Recording, options: GuidedCaptureOptions, compact: Boolean) {
    val metrics = if (options.enabled) state.stageMetrics else state.totalMetrics
    val numberStyle = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium
    Surface(shape = RoundedCornerShape(20.dp), color = LiveSessionPanel, border = BorderStroke(1.dp, LiveSessionOutline)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(if (compact) 1f else 1.2f).semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
                    Box(Modifier.size(if (compact) 32.dp else 40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { if (options.enabled)
                            (metrics.reps.toFloat() / options.repsPerAngle).coerceIn(0f, 1f) else 0f },
                            modifier = Modifier.fillMaxSize(), color = LiveSessionProgress,
                            trackColor = Color.White.copy(alpha = 0.12f), strokeWidth = 4.dp)
                    }
                    Column {
                        Text(if (options.enabled) stringResource(R.string.capture_rep_target, metrics.reps, options.repsPerAngle)
                            else stringResource(R.string.capture_rep_count, metrics.reps),
                            style = numberStyle.merge(VbTabularNumerals),
                            fontWeight = FontWeight.Bold, color = Color.White)
                        Text(stringResource(R.string.capture_completed), style = MaterialTheme.typography.bodySmall, color = LiveSessionMuted)
                    }
                }
                VerticalDivider(Modifier.height(40.dp), color = LiveSessionOutline)
                Row(Modifier.weight(1f).padding(start = if (compact) 12.dp else 16.dp).semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)) {
                    Icon(Icons.Rounded.Star, null, tint = Color(0xFFFFD166), modifier = Modifier.size(if (compact) 24.dp else 32.dp))
                    Column {
                        Text(metrics.validReps.toString(), style = numberStyle.merge(VbTabularNumerals),
                            fontWeight = FontWeight.Bold, color = Color.White)
                        Text(stringResource(R.string.capture_good_reps), style = MaterialTheme.typography.bodySmall, color = LiveSessionMuted)
                    }
                }
            }
            if (!compact) SessionMetricsLine(state)
        }
    }
}

@Composable
private fun SessionMetricsLine(state: CaptureUiState.Recording, modifier: Modifier = Modifier) {
    Text(stringResource(R.string.capture_session_metrics, state.totalMetrics.streak, state.totalMetrics.bestStreak,
        state.totalMetrics.estimatedFormScore?.let { "$it/100" } ?: "—"),
        modifier = modifier, style = MaterialTheme.typography.labelSmall, color = LiveSessionMuted)
}

@Composable
private fun SessionRotationGuidance(rotation: RotationUi, onRetry: () -> Unit, onSkip: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = LiveSessionPanel, border = BorderStroke(1.dp, LiveSessionOutline)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.055f)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Info, null, tint = LiveSessionProgress, modifier = Modifier.size(22.dp))
                Text(when {
                    rotation.secondsLeft != null -> stringResource(R.string.capture_angle_countdown, rotation.secondsLeft)
                    rotation.timedOut -> stringResource(R.string.capture_angle_unclear)
                    else -> stringResource(R.string.capture_next_view)
                }, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Cameraswitch, null, tint = Color.White, modifier = Modifier.size(28.dp))
                    Text(rotation.instruction, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onRetry, Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.capture_retry_angle))
                    }
                    OutlinedButton(onSkip, Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                        Icon(Icons.Rounded.SkipNext, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.capture_skip_angle))
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCoachContext(context: LiveCoachContext) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.live_coach_human_title, context.displayName.orEmpty()),
            style = MaterialTheme.typography.labelMedium, color = LiveSessionMuted)
        context.assignment?.let {
            Text(stringResource(R.string.live_coach_assignment, it.title, it.targetSets, it.targetReps),
                style = MaterialTheme.typography.bodySmall, color = Color.White)
        }
        context.latestNote?.text?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = LiveSessionProgress)
        }
    }
}

@Composable
private fun SessionPrimaryButton(onClick: () -> Unit, text: String, enabled: Boolean = true) {
    Button(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(
            containerColor = RustPrimary40, contentColor = Color.White,
            disabledContainerColor = LiveSessionPanel, disabledContentColor = LiveSessionMuted,
        )) {
        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun viewLabel(angle: CameraAngle): String = stringResource(when (angle) {
    CameraAngle.Front -> R.string.capture_front_view
    CameraAngle.Side -> R.string.capture_side_view
    CameraAngle.Rear -> R.string.capture_rear_view
    CameraAngle.Unknown -> R.string.capture_setup_label
})
