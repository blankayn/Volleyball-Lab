package com.volleyballlab.player.feature.train.result

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.pose.review.*
import kotlinx.coroutines.delay

/** Orchestrates source-video seeks; shares the existing decoder and skeleton renderer. */
@Composable
internal fun CoachReelPlayer(path: String, review: VideoReview, playback: ReviewPlayback, showMeasurements: Boolean = false) {
    val clips = remember(review) { CoachReel.build(review) }
    var index by rememberSaveable(path) { mutableIntStateOf(0) }
    var running by remember(path) { mutableStateOf(false) }
    var replay by remember { mutableIntStateOf(0) }
    var freezing by remember { mutableStateOf(false) }
    val clip = clips.getOrNull(index)
    DisposableEffect(playback) {
        playback.cancelReel = { running = false }
        onDispose { playback.cancelReel = {} }
    }
    LaunchedEffect(running, index, replay) {
        freezing = false
        if (!running || clip == null) return@LaunchedEffect
        suspend fun seek(time: Long): Boolean {
            playback.seek(time)
            var waited = 0
            while (playback.seeking && waited < 3_000) { delay(25); waited += 25 }
            return !playback.seeking && playback.prepared
        }
        if (!seek(clip.sourceStartMs)) { running = false; return@LaunchedEffect }
        playback.toggle()
        while (running && playback.positionMs < clip.freezeAtMs && playback.playing) delay(20)
        if (!running || !seek(clip.freezeAtMs)) { running = false; return@LaunchedEffect }
        freezing = true
        delay(1_500)
        freezing = false
        if (!running) return@LaunchedEffect
        playback.toggle()
        while (running && playback.positionMs < clip.sourceEndMs && playback.playing) delay(20)
        playback.pause()
        if (index + 1 < clips.size) index++ else running = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(review.guided?.status ?: if (review.guidedCapture == false) "Free practice - no angle review" else "View requirements were not recorded for this session.", style = MaterialTheme.typography.bodySmall)
        if (clips.isEmpty()) {
            Text("No measurable correction clips are available. You can still watch the full recording.")
        } else {
            Text("Practice ranges are provisional; these notes cover only measurable posture cues.", style = MaterialTheme.typography.bodySmall)
            if (review.issues.map { it.repNumber }.distinct().size > clips.size) Text("Showing up to six priority corrections", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { running = false; playback.seek(0) }) { Text("Full recording") }
            }
            clip?.let {
                Text("${index + 1} / ${clips.size} · ${it.label} · ${reviewTime(it.freezeAtMs)}${if (freezing) " · Review moment" else ""}", style = MaterialTheme.typography.labelLarge)
                CoachLearningNote(it.issue, showMeasurements)
                Row {
                    TextButton(onClick = { running = true; replay++ }) { Text("Replay clip") }
                    TextButton(onClick = { running = false; playback.pause(); index = (index + 1) % clips.size }) { Text("Next clip") }
                }
            }
        }
    }
}
