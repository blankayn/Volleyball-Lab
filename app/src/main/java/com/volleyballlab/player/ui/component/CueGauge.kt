package com.volleyballlab.player.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.pose.analysis.CuePlainWords
import com.volleyballlab.player.ui.theme.vbColors

/**
 * Where a measurement sits relative to its practice range, without a number: a
 * track, the practice range highlighted on it, and a dot for the athlete. Green
 * inside the range, amber outside. Screen readers hear the same words as the label.
 *
 * @param onDark true over the camera preview or video, where the track is drawn in white.
 */
@Composable
fun CueGauge(reading: CuePlainWords.Reading, modifier: Modifier = Modifier, onDark: Boolean = false) {
    val track = if (onDark) Color.White.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainerHighest
    val range = if (onDark) Color.White.copy(alpha = 0.5f) else MaterialTheme.vbColors.success.copy(alpha = 0.3f)
    val dot = if (reading.inRange) MaterialTheme.vbColors.success else MaterialTheme.vbColors.warning
    val outline = if (onDark) Color.Black.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface
    Canvas(
        modifier
            .fillMaxWidth()
            .height(12.dp)
            .clearAndSetSemantics { contentDescription = reading.line },
    ) {
        val barHeight = size.height * 0.5f
        val top = (size.height - barHeight) / 2f
        val corner = CornerRadius(barHeight / 2f)
        drawRoundRect(track, Offset(0f, top), Size(size.width, barHeight), corner)
        val start = reading.rangeStart * size.width
        drawRoundRect(range, Offset(start, top), Size((reading.rangeEnd - reading.rangeStart) * size.width, barHeight), corner)
        val radius = size.height / 2f
        val x = (reading.position * size.width).coerceIn(radius, size.width - radius)
        drawCircle(outline, radius, Offset(x, size.height / 2f))
        drawCircle(dot, radius * 0.72f, Offset(x, size.height / 2f))
    }
}
