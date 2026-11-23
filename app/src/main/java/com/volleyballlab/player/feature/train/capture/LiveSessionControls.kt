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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
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
import com.volleyballlab.player.pose.analysis.CoachPhrasebook
import com.volleyballlab.player.pose.analysis.Facing
import com.volleyballlab.player.pose.analysis.FacingGuide
import com.volleyballlab.player.pose.analysis.LivePostureUi
import com.volleyballlab.player.pose.analysis.LiveCoachingPlan
import com.volleyballlab.player.pose.analysis.LiveRepFeedback
import com.volleyballlab.player.pose.analysis.LiveRepVerdict
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
    voiceStatus: String = "",
    onEndEarly: () -> Unit = onStop,
    coachingPlan: LiveCoachingPlan? = null,
    repFeedback: LiveRepFeedback? = null,
    /** A coach line that is not about one rep, shown beside the rep verdict. */
    coachLine: String? = null,
    onSayAgain: () -> Unit = {},
    /** Why the movement that just ended was not counted as a rep, while that is still current. */
    notCounted: String? = null,
) {
    var confirmEarly by remember { mutableStateOf(false) }
    if (confirmEarly) AlertDialog(
        onDismissRequest = { confirmEarly = false },
        title = { Text("Save this session early?") },
        text = { Text("Your recording is saved. Missing required views will be labelled as an incomplete assessment.") },
        confirmButton = { TextButton(onClick = { confirmEarly = false; onEndEarly() }) { Text("Save and end") } },
        dismissButton = { TextButton(onClick = { confirmEarly = false }) { Text("Keep practicing") } },
    )
    BoxWithConstraints(
        modifier = modifier.background(Brush.verticalGradient(listOf(Color(0xFF10151D), Color(0xFF1B2634))))
            .systemBarsPadding(),
    ) {
        // Long coaching notes and larger accessibility text scroll in their own
        // panel, keeping both the camera and the finish action on screen.
        val compact = maxHeight < 700.dp || LocalDensity.current.fontScale > 1.15f
        // The camera is the point of this screen. Guidance gets a bounded share
        // and scrolls inside it, so the viewfinder never collapses.
        val guidanceHeight = if (compact) (maxHeight * 0.2f).coerceIn(92.dp, 150.dp)
            else (maxHeight * 0.24f).coerceIn(110.dp, 220.dp)
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
                    val progress = recording?.displayedProgress(guidedOptions)
                    val view = progress?.angle ?: CameraAngle.Front
                    // The view and which way to face for it, so "Side view" is never a guess.
                    val facing = FacingGuide.label(progress?.facing ?: Facing.Forward)
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp).testTag("capture-view-label"),
                        shape = RoundedCornerShape(12.dp), color = Color(0xBB111821),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                    ) {
                        Text(
                            if (guidedOptions.enabled) "${viewLabel(view)} · $facing" else stringResource(R.string.capture_free_practice),
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
                        is CaptureUiState.CountingDown -> if (state.instruction != null) {
                            Text(state.instruction, color = Color.White, modifier = Modifier.padding(20.dp).background(Color.Black.copy(alpha = 0.7f)).padding(12.dp))
                        } else CountdownDisplay(state.secondsLeft)
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
                    // During a turn the upcoming view has no counts yet; the space goes to the camera.
                    if (state.rotation == null) SessionRepStats(state, guidedOptions, compact)
                    Column(Modifier.fillMaxWidth().heightIn(max = guidanceHeight).clip(RoundedCornerShape(20.dp))
                        .verticalScroll(rememberScrollState())) {
                        if (state.finishingAfterRep) {
                            SessionClosing(state, coachingPlan)
                        } else if (state.rotation != null) {
                            SessionRotationGuidance(state.rotation, onRetryAngle, onSkipAngle)
                        } else if (notCounted != null && !livePosture.trackingLost) {
                            // Ahead of the last rep's verdict, which does not describe this movement.
                            SessionNotCounted(notCounted, coachingPlan)
                        } else if (repFeedback != null && !livePosture.trackingLost) {
                            SessionRepFeedback(repFeedback, coachingPlan, coachLine)
                        } else {
                            LivePostureCard(livePosture, state.suspensionHint ?: coachingText, Modifier.fillMaxWidth())
                        }
                        if (compact) SessionMetricsLine(state, Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                        if (coachContext.hasHumanCoach) SessionCoachContext(coachContext)
                    }
                    state.blockedReason?.let { Text(it, color = Color.White) }
                    if (guidedOptions.enabled && (state.blockedReason != null || state.rotation?.required == true)) {
                        TextButton(onClick = { confirmEarly = true }) { Text("End session early", color = Color.White) }
                    }
                    // One row: "Say again" is touch only and sits beside the finish action
                    // instead of taking a row of its own from the camera.
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!voiceMuted && !audioUnavailable && !state.finishingAfterRep && state.rotation == null) {
                            OutlinedButton(onSayAgain, Modifier.heightIn(min = 56.dp).testTag("live-say-again"),
                                shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                                Icon(Icons.Rounded.Refresh, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Say again", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        SessionPrimaryButton(onStop, if (guidedOptions.enabled && state.angle == CameraAngle.Front && state.rotation == null)
                            "Continue to side" else stringResource(R.string.capture_finish_session), modifier = Modifier.weight(1f))
                    }
                }
                is CaptureUiState.Framing -> {
                    Column(Modifier.fillMaxWidth().heightIn(max = guidanceHeight).clip(RoundedCornerShape(20.dp))
                        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GuidedSetupCard(guidedOptions, state.angleReady, onGuidedChanged, onRepTargetChanged)
                        coachingPlan?.let { plan ->
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(plan.title, color = Color.White, style = MaterialTheme.typography.titleSmall)
                                Text(plan.focusLabel, color = LiveSessionProgress, style = MaterialTheme.typography.bodySmall)
                                Text("Spoken rep counts, form feedback and one correction at a time.",
                                    color = LiveSessionMuted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        FramingGuidance(state.quality, state.autoStartArmed)
                        if (voiceStatus.isNotEmpty()) Text(voiceStatus, color = Color.White, style = MaterialTheme.typography.bodySmall)
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
private fun SessionRepFeedback(feedback: LiveRepFeedback, plan: LiveCoachingPlan?, coachLine: String? = null) {
    val verdictColor = when (feedback.verdict) {
        LiveRepVerdict.Good -> LiveSessionProgress
        LiveRepVerdict.Adjust -> Color(0xFFFFD166)
        LiveRepVerdict.Partial, LiveRepVerdict.Unrated -> LiveSessionMuted
    }
    Surface(Modifier.fillMaxWidth().testTag("live-rep-feedback"), shape = RoundedCornerShape(20.dp), color = LiveSessionPanel) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(plan?.title ?: "Your live coach", style = MaterialTheme.typography.labelMedium, color = LiveSessionMuted)
            Text("${feedback.countText} · ${feedback.verdict.label}", style = MaterialTheme.typography.titleMedium,
                color = verdictColor, fontWeight = FontWeight.SemiBold)
            Text(feedback.detail, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            coachLine?.takeIf { it.isNotBlank() && it != feedback.detail }?.let {
                Text("Coach says: $it", style = MaterialTheme.typography.bodySmall, color = LiveSessionProgress)
            }
            plan?.let { Text(it.focusLabel, style = MaterialTheme.typography.labelSmall, color = LiveSessionMuted) }
        }
    }
}

/**
 * A movement that finished but was not the skill. It shows what was missing and
 * deliberately no rep number, verdict or score: nothing was earned.
 */
@Composable
private fun SessionNotCounted(text: String, plan: LiveCoachingPlan?) {
    Surface(Modifier.fillMaxWidth().testTag("live-not-counted"), shape = RoundedCornerShape(20.dp), color = LiveSessionPanel) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(plan?.title ?: "Your live coach", style = MaterialTheme.typography.labelMedium, color = LiveSessionMuted)
            Text("Not counted", style = MaterialTheme.typography.titleMedium, color = Color(0xFFFFD166),
                fontWeight = FontWeight.SemiBold)
            Text(text.removePrefix(CoachPhrasebook.NOT_COUNTED_PREFIX), style = MaterialTheme.typography.bodyMedium,
                color = Color.White)
        }
    }
}

/** The closing turn in writing, for a muted session and for the optional AI carry-forward. */
@Composable
private fun SessionClosing(state: CaptureUiState.Recording, plan: LiveCoachingPlan?) {
    Surface(Modifier.fillMaxWidth().testTag("live-session-closing"), shape = RoundedCornerShape(20.dp), color = LiveSessionPanel) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(plan?.title ?: "Your live coach", style = MaterialTheme.typography.labelMedium, color = LiveSessionMuted)
            state.closing.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White) }
            state.closingAdvice?.let { advice ->
                Text(stringResource(if (advice.aiGenerated) R.string.coaching_ai_advice else R.string.coaching_local_advice),
                    style = MaterialTheme.typography.labelMedium, color = LiveSessionProgress)
                Text(advice.text, style = MaterialTheme.typography.bodyMedium, color = Color.White)
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
    val metrics = state.displayedProgress(options).metrics
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
@Suppress("UNUSED_PARAMETER")
private fun SessionRotationGuidance(rotation: RotationUi, onRetry: () -> Unit, onSkip: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = LiveSessionPanel, border = BorderStroke(1.dp, LiveSessionOutline)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.055f)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Info, null, tint = LiveSessionProgress, modifier = Modifier.size(22.dp))
                Text(when {
                    rotation.loadingCoaching -> stringResource(R.string.coaching_preparing)
                    rotation.speechPending -> stringResource(R.string.coaching_listen)
                    rotation.secondsLeft != null -> stringResource(R.string.capture_angle_countdown, rotation.secondsLeft)
                    rotation.timedOut -> stringResource(R.string.capture_angle_unclear)
                    else -> stringResource(R.string.capture_next_view)
                }, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rotation.advice?.let { advice ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(if (advice.aiGenerated) R.string.coaching_ai_advice else R.string.coaching_local_advice),
                            style = MaterialTheme.typography.labelMedium, color = LiveSessionProgress)
                        Text(advice.text, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Cameraswitch, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    Text(rotation.instruction, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                }
                if (rotation.required) Text("Side view required", style = MaterialTheme.typography.labelMedium, color = LiveSessionMuted)
                rotation.blockedReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White) }
                // The view is confirmed from the camera, so there is nothing to retry by hand.
                if (!rotation.required) TextButton(onSkip, Modifier.heightIn(min = 44.dp)) {
                    Icon(Icons.Rounded.SkipNext, null, Modifier.size(20.dp), tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.capture_skip_angle), color = Color.White)
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
private fun SessionPrimaryButton(onClick: () -> Unit, text: String, enabled: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth()) {
    Button(onClick, enabled = enabled, modifier = modifier.heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(
            containerColor = RustPrimary40, contentColor = Color.White,
            disabledContainerColor = LiveSessionPanel, disabledContentColor = LiveSessionMuted,
        )) {
        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun viewLabel(angle: CameraAngle): String = stringResource(when (angle) {
    CameraAngle.Front -> R.string.capture_front_view
    CameraAngle.Side -> R.string.capture_side_view
    // No live session asks for a rear view.
    CameraAngle.Rear, CameraAngle.Unknown -> R.string.capture_setup_label
})
