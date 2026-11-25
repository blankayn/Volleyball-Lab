package com.volleyballlab.player.feature.train.result

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.pose.analysis.CuePlainWords
import com.volleyballlab.player.pose.review.CoachReel
import com.volleyballlab.player.pose.review.CorrectionGuide
import com.volleyballlab.player.pose.review.ReelRep
import com.volleyballlab.player.pose.review.ReviewIssue
import com.volleyballlab.player.pose.review.VideoReview
import com.volleyballlab.player.ui.component.CueGauge
import kotlinx.coroutines.delay

/**
 * Coach Reel: every rep in the order it was performed. Pressing one stops the video
 * on its worst correction and explains it. Watching plays that rep alone, holding a
 * moment there, from the original recording.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CoachReelPlayer(path: String, review: VideoReview, playback: ReviewPlayback, showMeasurements: Boolean = false,
    onShow: () -> Unit = {}) {
    val reps = remember(review) { CoachReel.repList(review) }
    // Opens on the rep most worth looking at.
    var picked by rememberSaveable(path) { mutableIntStateOf(CoachReel.startingRep(reps)) }
    var running by remember(path) { mutableStateOf(false) }
    var request by remember { mutableIntStateOf(0) }
    val current = reps.firstOrNull { it.rep.repNumber == picked } ?: reps.firstOrNull()
    DisposableEffect(playback) {
        playback.cancelReel = { running = false }
        onDispose { playback.cancelReel = {} }
    }
    LaunchedEffect(running, request) {
        val rep = current
        if (!running || rep == null) return@LaunchedEffect
        suspend fun seek(time: Long): Boolean {
            playback.seek(time)
            var waited = 0
            while (playback.seeking && waited < 3_000) { delay(25); waited += 25 }
            return !playback.seeking && playback.prepared
        }
        if (!seek(rep.startMs)) { running = false; return@LaunchedEffect }
        playback.toggle()
        rep.freezeAtMs?.let { freeze ->
            while (running && playback.positionMs < freeze && playback.playing) delay(20)
            if (!running || !seek(freeze)) { running = false; return@LaunchedEffect }
            delay(1_500)
            if (!running) return@LaunchedEffect
            playback.toggle()
        }
        while (running && playback.positionMs < rep.endMs && playback.playing) delay(20)
        playback.pause()
        running = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text(stringResource(R.string.reel_title), style = MaterialTheme.typography.titleMedium)
            val flagged = reps.count { it.issues.isNotEmpty() }
            Text(
                when {
                    reps.isEmpty() -> stringResource(R.string.reel_no_reps)
                    flagged == 0 -> pluralStringResource(R.plurals.reel_all_clear, reps.size, reps.size)
                    else -> pluralStringResource(R.plurals.reel_to_work_on, reps.size, flagged, reps.size)
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            review.guided?.takeUnless { it.complete }?.let {
                Text(it.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (current == null) return@Column

        fun show(timeMs: Long) {
            running = false
            onShow()
            playback.seek(timeMs)
        }
        Text(stringResource(R.string.reel_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val bothViews = reps.mapTo(mutableSetOf()) { it.rep.angle }.count { it != CameraAngle.Unknown } > 1
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            reps.forEach { item ->
                FilterChip(
                    selected = item === current,
                    onClick = {
                        picked = item.rep.repNumber
                        show(item.freezeAtMs ?: item.issues.firstOrNull()?.timestampMs ?: item.startMs)
                    },
                    label = {
                        val rep = stringResource(R.string.reel_rep, item.rep.repNumber)
                        Text(angleName(item.rep.angle)?.takeIf { bothViews }?.let { stringResource(R.string.reel_rep_view, rep, it) } ?: rep)
                    },
                    trailingIcon = {
                        if (item.issues.isEmpty()) Icon(Icons.Rounded.Check, stringResource(R.string.reel_rep_clear), Modifier.size(16.dp))
                        else Text(item.issues.size.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    },
                )
            }
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.reel_rep, current.rep.repNumber), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    current.rep.formScore?.let { Text(stringResource(R.string.reel_form, it), style = MaterialTheme.typography.labelLarge) }
                }
                if (current.issues.isEmpty()) Text(stringResource(R.string.reel_rep_clear_detail), style = MaterialTheme.typography.bodyMedium)
                current.issues.forEachIndexed { index, issue ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    // With one correction, pressing the rep already showed it.
                    Correction(issue, showMeasurements, onShow = { show(issue.timestampMs) }.takeIf { current.issues.size > 1 })
                }
                OutlinedButton(enabled = playback.prepared, onClick = {
                    if (running) { running = false; playback.pause() } else { running = true; request++ }
                }) {
                    Text(if (running) stringResource(R.string.reel_stop) else stringResource(R.string.reel_watch, current.rep.repNumber))
                }
            }
        }
        Text(stringResource(R.string.reel_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One thing to change: what went wrong, how far off it was, why it matters and what to do. */
@Composable
private fun Correction(issue: ReviewIssue, showMeasurements: Boolean, onShow: (() -> Unit)?) {
    val cue = FormCueCatalog.byId(issue.cueId) ?: return
    val guide = CorrectionGuide.of(issue.cueId, issue.measured)
    val showLabel = stringResource(R.string.reel_show)
    Column(
        (if (onShow == null) Modifier else Modifier.clickable(onClickLabel = showLabel, onClick = onShow)).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(cue.label), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(reviewTime(issue.timestampMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showMeasurements) {
            Text(stringResource(R.string.result_measured_vs_target, reviewValue(issue.measured, cue.unit),
                "${reviewValue(cue.target.start, cue.unit)} – ${reviewValue(cue.target.endInclusive, cue.unit)}"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else CueGauge(CuePlainWords.read(cue, issue.measured))
        Explanation(R.string.reel_wrong, guide.wrong)
        Explanation(R.string.reel_why, guide.why)
        Explanation(R.string.reel_fix, guide.fix)
    }
}

@Composable
private fun Explanation(heading: Int, text: String) {
    Column {
        Text(stringResource(heading), style = MaterialTheme.typography.labelLarge)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun angleName(angle: CameraAngle): String? = when (angle) {
    CameraAngle.Front -> stringResource(R.string.capture_front_view)
    CameraAngle.Side -> stringResource(R.string.capture_side_view)
    CameraAngle.Rear -> stringResource(R.string.result_angle_rear)
    CameraAngle.Unknown -> null
}
