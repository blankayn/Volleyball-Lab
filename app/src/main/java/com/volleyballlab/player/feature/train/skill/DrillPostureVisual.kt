package com.volleyballlab.player.feature.train.skill

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.DrillPostureSamples
import com.volleyballlab.player.domain.model.PostureSheet
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

/** Four posture snapshots, displayed independently of workout timers and scores. */
@Composable
fun DrillPostureVisual(drillId: String, modifier: Modifier = Modifier) {
    val sample = DrillPostureSamples.forDrill(drillId) ?: return
    var phase by rememberSaveable(drillId) { mutableIntStateOf(0) }
    var playing by rememberSaveable(drillId) { mutableStateOf(false) }
    var leftHanded by rememberSaveable(drillId) { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, drillId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) playing = false
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(playing, drillId) {
        while (playing) {
            delay(1_800)
            phase = (phase + 1) % sample.steps.size
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Crossfade(targetState = phase, animationSpec = tween(180), label = "posture sample") { step ->
            DrillPostureFrame(drillId, step, Modifier.fillMaxWidth().height(260.dp), leftHanded)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { playing = !playing }) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (playing) "Pause sample" else "Play sample")
            }
            Spacer(Modifier.weight(1f))
            Text("Step ${phase + 1} of 4", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            sample.steps.forEachIndexed { index, step ->
                FilterChip(selected = phase == index, onClick = { playing = false; phase = index }, label = { Text("${index + 1} · ${step.label}") })
            }
        }
        Text(sample.steps[phase].cue, style = MaterialTheme.typography.bodyMedium)
        if (sample.supportsHandedness) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!leftHanded, { leftHanded = false }, label = { Text("Right-handed") })
                FilterChip(leftHanded, { leftHanded = true }, label = { Text("Left-handed") })
            }
        }
    }
}

@Composable
internal fun DrillPostureFrame(drillId: String, phase: Int, modifier: Modifier, leftHanded: Boolean = false) {
    val sample = DrillPostureSamples.forDrill(drillId) ?: return
    val resource = when (sample.sheet) {
        PostureSheet.Serving -> R.drawable.drill_visual_serving
        PostureSheet.Receiving -> R.drawable.drill_visual_receiving
        PostureSheet.Setting -> R.drawable.drill_visual_setting
        PostureSheet.Spiking -> R.drawable.drill_visual_spiking
        PostureSheet.Blocking -> R.drawable.drill_visual_blocking
    }
    val image = ImageBitmap.imageResource(resource)
    val region = remember(sample, phase, image.width, image.height) { sample.frameRegion(phase, image.width, image.height) }
    val label = sample.steps[phase.coerceIn(0, 3)].label
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFFEAF4FE))) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { scaleX = if (leftHanded) -1f else 1f }
            .semantics { contentDescription = "$label posture sample${if (sample.supportsHandedness) if (leftHanded) ", left-handed" else ", right-handed" else ""}" }) {
            val scale = min(size.width / region.width, size.height / region.height)
            val destination = IntSize((region.width * scale).roundToInt(), (region.height * scale).roundToInt())
            drawImage(image, srcOffset = IntOffset(region.x, region.y), srcSize = IntSize(region.width, region.height),
                dstOffset = IntOffset(((size.width - destination.width) / 2).roundToInt(), ((size.height - destination.height) / 2).roundToInt()),
                dstSize = destination, filterQuality = FilterQuality.High)
        }
    }
}
