package com.volleyballlab.player.feature.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.volleyballlab.player.R
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.SessionReview
import com.volleyballlab.player.feature.coach.AssignmentResultActions
import com.volleyballlab.player.feature.train.result.ResultUiState
import com.volleyballlab.player.feature.train.result.SessionResultViewModel
import com.volleyballlab.player.ui.component.StudentCard
import com.volleyballlab.player.ui.component.scoreColor
import com.volleyballlab.player.ui.component.studentBackground
import com.volleyballlab.player.ui.theme.VbButtonShape
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException

@Composable
fun SessionSummaryScreen(
    sessionId: String,
    onBack: () -> Unit,
    onOpenFeedback: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val model: SessionResultViewModel = viewModel(key = "summary-$sessionId",
        factory = SessionResultViewModel.factory(sessionId))
    val state by model.uiState.collectAsStateWithLifecycle()
    when (val current = state) {
        ResultUiState.Loading, ResultUiState.NotFound -> Column(
            modifier.fillMaxSize().studentBackground().padding(contentPadding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SummaryBackButton(onBack)
            if (current == ResultUiState.Loading) CircularProgressIndicator()
            else Text(stringResource(R.string.session_summary_unavailable))
        }
        is ResultUiState.Ready -> {
            val context = LocalContext.current
            val graph = remember(context) { (context.applicationContext as VolleyballLabApplication).container }
            val drillName by produceState<String?>(null, current.session.drillId, graph) {
                value = try { graph.drillRepository.byId(current.session.drillId)?.name }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    null
                }
            }
            SessionSummaryContent(current.session, drillName, onBack, onOpenFeedback,
                model::deleteVideo, contentPadding, modifier)
        }
    }
}

/** A saved-data summary: opening history never starts another video-analysis pass. */
@Composable
fun SessionSummaryContent(
    session: SessionResult,
    drillName: String?,
    onBack: () -> Unit,
    onOpenFeedback: () -> Unit,
    onDeleteVideo: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val review = remember(session) { SessionReview.from(session) }
    val assessedReps = remember(session) { session.reps.count { it.formScore(session.skill) != null } }
    val repeatedMeasurements = remember(session) {
        session.reps.flatMap { it.cueScores }.filter { it.score != null }
            .groupingBy { it.cueId }.eachCount().any { it.value >= SessionReview.MIN_REPS_FOR_PANEL }
    }
    val videoAvailable = remember(session.videoPath) { session.videoPath?.let { File(it).isFile } == true }
    var confirmDeleteVideo by remember { mutableStateOf(false) }
    val locale = LocalLocale.current.platformLocale
    val zone = ZoneId.systemDefault()
    val date = remember(session.startedAtEpochMs, locale, zone) {
        Instant.ofEpochMilli(session.startedAtEpochMs).atZone(zone)
            .format(DateTimeFormatter.ofPattern("d MMM yyyy · h:mm a", locale))
    }
    val horizontal = Modifier.padding(horizontal = 20.dp)
    LazyColumn(
        modifier.fillMaxSize().studentBackground(),
        contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(horizontal, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryBackButton(onBack)
                Text(stringResource(R.string.session_summary_title), fontSize = 28.sp,
                    lineHeight = 34.sp, fontWeight = FontWeight.Bold)
                Text(date, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            StudentCard(horizontal.fillMaxWidth()) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(session.skill.displayName), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    if (!drillName.isNullOrBlank()) Text(drillName, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.session_summary_estimated_form),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(review.formScore?.let { stringResource(R.string.session_summary_score_percent, it) }
                            ?: stringResource(R.string.session_summary_unassessed),
                            fontSize = if (review.formScore == null) 28.sp else 48.sp,
                            lineHeight = if (review.formScore == null) 34.sp else 56.sp,
                            fontWeight = FontWeight.Bold,
                            color = review.formScore?.let { scoreColor(it) } ?: MaterialTheme.colorScheme.onSurface)
                        if (review.formScore != null) Text(stringResource(R.string.session_summary_basis,
                            assessedReps, session.repCount), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(stringResource(when {
                        session.repCount == 0 -> R.string.session_summary_no_reps
                        review.formScore == null -> R.string.session_summary_no_estimate
                        assessedReps < SessionReview.MIN_REPS_FOR_PANEL -> R.string.session_summary_limited
                        review.formScore >= 75 -> R.string.session_summary_strong
                        review.formScore >= 50 -> R.string.session_summary_balanced
                        else -> R.string.session_summary_developing
                    }), style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SummaryMetric(session.repCount.toString(), stringResource(R.string.session_summary_reps), Modifier.weight(1f))
                        SummaryMetric(formatSessionDuration(session.durationMs), stringResource(R.string.session_summary_duration), Modifier.weight(1f))
                        SummaryMetric(session.bestRep()?.formScore(session.skill)?.let { "$it%" } ?: "—",
                            stringResource(R.string.session_summary_best), Modifier.weight(1f))
                    }
                }
            }
        }
        item { SessionExplanationCard(session.id, horizontal) }
        if (review.framingIsPoor || (review.formScore == null && session.repCount > 0)) {
            item {
                SummarySection(stringResource(R.string.session_summary_capture_title), Icons.Rounded.Videocam,
                    MaterialTheme.colorScheme.primary, horizontal) {
                    Text(stringResource(R.string.session_summary_capture_body), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            SummarySection(stringResource(R.string.session_summary_strengths), Icons.Rounded.CheckCircle,
                Color(0xFF278056), horizontal) {
                if (review.strengths.isEmpty()) Text(stringResource(if (repeatedMeasurements)
                    R.string.session_summary_no_strength else R.string.session_summary_insufficient),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else review.strengths.take(2).forEach { point ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(point.cue.label), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.session_summary_measured, point.scoreOutOfHundred, point.measuredReps),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            SummarySection(stringResource(R.string.session_summary_improve), Icons.Rounded.TrackChanges,
                MaterialTheme.colorScheme.primary, horizontal) {
                val priority = review.corrections.firstOrNull()
                if (priority == null) Text(stringResource(if (repeatedMeasurements)
                    R.string.session_summary_no_correction else R.string.session_summary_insufficient),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    Text(stringResource(R.string.session_summary_priority), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(stringResource(priority.cue.label), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(priority.cue.coachingTip), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.session_summary_measured, priority.scoreOutOfHundred, priority.measuredReps),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (session.assignmentId != null) item {
            AssignmentResultActions(session.assignmentId, session, horizontal)
        }
        item {
            Button(onOpenFeedback, modifier = horizontal.fillMaxWidth(), shape = VbButtonShape) {
                Icon(Icons.Rounded.PlayCircleOutline, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (videoAvailable) R.string.session_summary_video_feedback
                    else R.string.session_summary_full_feedback))
            }
        }
        if (videoAvailable) item {
            TextButton(onClick = { confirmDeleteVideo = true }, modifier = horizontal.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.session_delete_video),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (confirmDeleteVideo) {
        AlertDialog(
            onDismissRequest = { confirmDeleteVideo = false },
            title = { Text(stringResource(R.string.session_delete_video_title)) },
            text = { Text(stringResource(R.string.session_delete_video_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteVideo = false
                        onDeleteVideo()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.session_delete_video_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteVideo = false }) {
                    Text(stringResource(R.string.session_delete_video_cancel))
                }
            },
        )
    }
}

@Composable
private fun SummaryBackButton(onBack: () -> Unit) {
    TextButton(onBack, contentPadding = PaddingValues(0.dp)) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.session_summary_back))
    }
}

@Composable
private fun SummaryMetric(value: String, label: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SummarySection(title: String, icon: ImageVector, accent: Color, modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    StudentCard(modifier.fillMaxWidth(), accent = accent) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(32.dp).background(accent.copy(alpha = .10f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(18.dp), tint = accent)
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

private fun formatSessionDuration(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0L) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
