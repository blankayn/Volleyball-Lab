package com.volleyballlab.player.feature.train.result

import android.content.Context
import android.graphics.Paint
import android.media.MediaPlayer
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.R
import com.volleyballlab.player.data.session.VideoReviewCache
import com.volleyballlab.player.domain.model.CueUnit
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.model.SkeletonBones
import com.volleyballlab.player.pose.review.RecordedVideoAnalyzer
import com.volleyballlab.player.pose.review.ReviewFit
import com.volleyballlab.player.pose.review.ReviewIssue
import com.volleyballlab.player.pose.review.ReviewPose
import com.volleyballlab.player.pose.review.VideoReview
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private sealed interface ReviewState {
    data object Loading : ReviewState
    data class AnalysingOlderRecording(val progress: Float = 0f) : ReviewState
    data class Ready(val review: VideoReview) : ReviewState
    data object Unavailable : ReviewState
}

private class VideoReviewViewModel(context: Context, private val path: String, private val skill: Skill) : ViewModel() {
    private val cache = VideoReviewCache(context.applicationContext)
    private val analyzer = RecordedVideoAnalyzer(context.applicationContext)
    val state = MutableStateFlow<ReviewState>(ReviewState.Loading)
    private var job: Job? = null

    init { load() }

    fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            state.value = ReviewState.Loading
            try {
                val review = cache.read(path, skill) ?: run {
                    state.value = ReviewState.AnalysingOlderRecording()
                    analyzer.analyse(path, skill) {
                        state.value = ReviewState.AnalysingOlderRecording(it)
                    }
                }.also {
                    try { cache.write(path, skill, it) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { Log.w("VideoReview", "Could not cache playback analysis", error) }
                }
                state.value = ReviewState.Ready(review)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                Log.w("VideoReview", "Playback analysis unavailable", error)
                state.value = ReviewState.Unavailable
            }
            catch (error: LinkageError) {
                Log.w("VideoReview", "Pose runtime unavailable on this device", error)
                state.value = ReviewState.Unavailable
            }
        }
    }
}

@Composable
fun AnnotatedSessionVideo(session: SessionResult, path: String) {
    val context = LocalContext.current
    val model: VideoReviewViewModel = viewModel(
        key = "video-review-${session.id}",
        factory = viewModelFactory { initializer { VideoReviewViewModel(context, path, session.skill) } },
    )
    val state by model.state.collectAsStateWithLifecycle()
    val review = (state as? ReviewState.Ready)?.review
    var skeleton by rememberSaveable(path) { mutableStateOf(true) }
    var shownIssues by rememberSaveable(path) { mutableIntStateOf(8) }
    val playback = remember(path) { ReviewPlayback() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReviewPlayer(path, playback, review, skeleton)
        when (val current = state) {
            ReviewState.Loading -> Unit
            is ReviewState.AnalysingOlderRecording -> {
                LinearProgressIndicator(progress = { current.progress }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.review_preparing, (current.progress * 100).toInt()), style = MaterialTheme.typography.bodySmall)
            }
            ReviewState.Unavailable -> {
                Text(stringResource(R.string.review_unavailable), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = model::load) { Text(stringResource(R.string.review_retry)) }
            }
            is ReviewState.Ready -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.review_skeleton), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Switch(checked = skeleton, onCheckedChange = { skeleton = it })
                }
                Text(stringResource(R.string.review_legend), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(if (current.review.fromCapture) R.string.review_captured_feedback_note else R.string.review_feedback_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (current.review.frames.none { it.points.size == 33 }) {
                    Text(stringResource(R.string.review_no_pose), style = MaterialTheme.typography.bodyMedium)
                } else if (current.review.issues.isEmpty()) {
                    Text(stringResource(if (current.review.assessedCueCount == 0) R.string.review_not_measurable else R.string.review_no_corrections),
                        style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(stringResource(R.string.review_corrections), style = MaterialTheme.typography.titleSmall)
                    current.review.issues.take(shownIssues).forEach { issue ->
                        ReviewIssueCard(issue, onJump = { skeleton = true; playback.seek(issue.timestampMs) })
                    }
                    if (shownIssues < current.review.issues.size) {
                        TextButton(onClick = { shownIssues += 12 }) { Text(stringResource(R.string.review_more)) }
                    }
                }
            }
        }
    }
}

/** All player operations run on the UI thread; the overlay follows actual playback position. */
private class ReviewPlayback {
    var view: VideoView? = null
    var media: MediaPlayer? = null
    var prepared by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var seeking by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)

    fun pause() { view?.pause(); playing = false }
    fun seek(timeMs: Long) {
        if (!prepared) return
        pause()
        seeking = true
        // Closest-frame seeking matters for annotation jumps; keyframe-only seeks drift.
        media?.seekTo(timeMs.coerceIn(0, durationMs), MediaPlayer.SEEK_CLOSEST)
    }
    fun toggle() {
        if (!prepared || seeking) return
        if (playing) pause() else { view?.start(); playing = true }
    }
}

@Composable
private fun ReviewPlayer(path: String, playback: ReviewPlayback, review: VideoReview?, skeleton: Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val seekDescription = stringResource(R.string.review_seek)
    val host = remember(path) {
        FrameLayout(context).apply {
            addView(VideoView(context).apply {
                playback.view = this
                setOnPreparedListener { media ->
                    playback.media = media
                    playback.durationMs = duration.toLong().coerceAtLeast(1)
                    playback.prepared = true
                    playback.failed = false
                    media.setOnSeekCompleteListener {
                        playback.positionMs = currentPosition.toLong()
                        playback.seeking = false
                    }
                    playback.seek(0)
                }
                setOnCompletionListener { playback.playing = false; playback.positionMs = playback.durationMs }
                setOnErrorListener { _, _, _ ->
                    playback.prepared = false; playback.failed = true; playback.playing = false
                    true
                }
                setVideoPath(path)
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        }
    }
    DisposableEffect(host, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) playback.pause()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            playback.view?.stopPlayback()
            playback.media = null
            playback.view = null
            playback.prepared = false
            playback.playing = false
        }
    }
    LaunchedEffect(host) {
        while (true) {
            if (playback.prepared && !playback.seeking) {
                playback.positionMs = playback.view?.currentPosition?.toLong() ?: 0
                playback.playing = playback.view?.isPlaying == true
            }
            delay(33)
        }
    }
    val pose = if (skeleton && playback.prepared && !playback.seeking) review?.frameAt(playback.positionMs) else null
    val issues = if (skeleton && playback.prepared && !playback.seeking) review?.issuesAt(playback.positionMs).orEmpty() else emptyList()
    val active = issues.minByOrNull { it.score }
    val label = active?.let { FormCueCatalog.byId(it.cueId) }?.let { stringResource(it.label) }
    var scrub by remember(path) { mutableStateOf<Float?>(null) }
    Column {
        Box(Modifier.fillMaxWidth().height(360.dp).background(Color.Black)) {
            AndroidView(factory = { host }, modifier = Modifier.fillMaxSize())
            if (review != null && pose != null) {
                ReviewSkeleton(pose, review.width, review.height, issues, label, Modifier.fillMaxSize())
            }
            if (review != null && skeleton && playback.prepared && pose == null && !playback.seeking) {
                Text(stringResource(R.string.review_pose_unavailable_here), color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = 0.7f)).padding(6.dp))
            }
        }
        Box(Modifier.fillMaxWidth()) {
            Slider(
                value = scrub ?: playback.positionMs.toFloat(),
                onValueChange = { playback.pause(); scrub = it },
                onValueChangeFinished = { scrub?.let { playback.seek(it.toLong()) }; scrub = null },
                valueRange = 0f..playback.durationMs.coerceAtLeast(1).toFloat(),
                enabled = playback.prepared,
                modifier = Modifier.semantics { contentDescription = seekDescription },
            )
            if (review != null && playback.durationMs > 0) {
                Canvas(Modifier.fillMaxWidth().height(5.dp).align(Alignment.BottomCenter).padding(horizontal = 10.dp)) {
                    review.issues.forEach { issue ->
                        val x = size.width * issue.timestampMs / playback.durationMs
                        drawCircle(issueColor(issue), 2.dp.toPx(), Offset(x, size.height / 2))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = playback::toggle, enabled = playback.prepared && !playback.seeking) {
                Text(stringResource(if (playback.playing) R.string.review_pause else R.string.review_play))
            }
            Text("${reviewTime(playback.positionMs)} / ${reviewTime(playback.durationMs)}", style = MaterialTheme.typography.labelMedium)
        }
        if (playback.failed) {
            Text(stringResource(R.string.review_playback_failed), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { playback.view?.setVideoPath(path) }) { Text(stringResource(R.string.review_retry)) }
        }
    }
}

private fun issueColor(issue: ReviewIssue) = if (issue.score < 0.35f) Color(0xFFFF6B5E) else Color(0xFFFFC857)

@Composable
private fun ReviewSkeleton(pose: ReviewPose, width: Int, height: Int, issues: List<ReviewIssue>, label: String?, modifier: Modifier) {
    val description = stringResource(R.string.review_overlay_description)
    Canvas(modifier.semantics { contentDescription = if (label == null) description else "$description: $label" }) {
        val fit = ReviewFit.inside(size.width, size.height, width, height)
        fun offset(index: Int): Offset {
            val (x, y) = fit.map(pose.points[index])
            return Offset(x, y)
        }
        fun color(points: Set<Int>): Color = issues.filter { it.points.containsAll(points) }
            .minByOrNull { it.score }?.let(::issueColor) ?: Color(0xFF65D9EF)
        SkeletonBones.forEach { (a, b) ->
            if (pose.points[a.ordinal].visible && pose.points[b.ordinal].visible) {
                drawLine(Color.Black.copy(alpha = 0.65f), offset(a.ordinal), offset(b.ordinal), 5.dp.toPx(), StrokeCap.Round)
                drawLine(color(setOf(a.ordinal, b.ordinal)), offset(a.ordinal), offset(b.ordinal), 3.dp.toPx(), StrokeCap.Round)
            }
        }
        pose.points.forEachIndexed { index, point ->
            if (point.visible && (index > 10 || issues.any { index in it.points })) {
                drawCircle(Color.Black, 4.dp.toPx(), offset(index))
                drawCircle(color(setOf(index)), 3.dp.toPx(), offset(index))
                if (issues.any { index in it.points }) {
                    drawCircle(color(setOf(index)).copy(alpha = 0.25f), 10.dp.toPx(), offset(index))
                }
            }
        }
        val issue = issues.minByOrNull { it.score }
        if (issue != null && label != null) {
            val points = issue.points.map(::offset)
            val anchor = Offset(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12.sp.toPx(); color = android.graphics.Color.WHITE }
            val maxWidth = (size.width - 24.dp.toPx()).coerceAtLeast(1f)
            val text = TextUtils.ellipsize(label, android.text.TextPaint(paint), maxWidth - 16.dp.toPx(), TextUtils.TruncateAt.END).toString()
            val boxWidth = (paint.measureText(text) + 16.dp.toPx()).coerceAtMost(maxWidth)
            val boxHeight = 28.dp.toPx()
            val left = (anchor.x - boxWidth / 2).coerceIn(8.dp.toPx(), (size.width - boxWidth - 8.dp.toPx()).coerceAtLeast(8.dp.toPx()))
            val top = (anchor.y - 60.dp.toPx()).coerceIn(8.dp.toPx(), (size.height - boxHeight - 8.dp.toPx()).coerceAtLeast(8.dp.toPx()))
            drawLine(issueColor(issue), anchor, Offset(left + boxWidth / 2, top + boxHeight), 2.dp.toPx())
            drawContext.canvas.nativeCanvas.apply {
                drawRoundRect(left, top, left + boxWidth, top + boxHeight, 6.dp.toPx(), 6.dp.toPx(),
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(230, 25, 25, 25) })
                drawText(text, left + 8.dp.toPx(), top + (boxHeight - paint.ascent() - paint.descent()) / 2, paint)
            }
        }
    }
}

@Composable
private fun ReviewIssueCard(issue: ReviewIssue, onJump: () -> Unit) {
    val cue = FormCueCatalog.byId(issue.cueId) ?: return
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.review_issue_at, issue.repNumber, reviewTime(issue.timestampMs)), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(cue.label), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(cue.coachingTip), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.result_measured_vs_target, reviewValue(issue.measured, cue.unit),
                "${reviewValue(cue.target.start, cue.unit)} – ${reviewValue(cue.target.endInclusive, cue.unit)}"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onJump) { Text(stringResource(R.string.review_show_moment)) }
        }
    }
}

private fun reviewTime(timeMs: Long): String = String.format(Locale.US, "%d:%02d.%01d", timeMs / 60_000, timeMs / 1_000 % 60, timeMs / 100 % 10)
private fun reviewValue(value: Float, unit: CueUnit): String = when (unit) {
    CueUnit.Degrees -> String.format(Locale.US, "%.0f°", value)
    CueUnit.Metres -> String.format(Locale.US, "%.2f m", value)
    CueUnit.Ratio -> String.format(Locale.US, "%.0f%%", value * 100)
}
