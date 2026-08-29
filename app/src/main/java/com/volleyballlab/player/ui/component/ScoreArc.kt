package com.volleyballlab.player.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.R
import com.volleyballlab.player.ui.theme.VbMotion
import com.volleyballlab.player.ui.theme.VbTabularNumerals
import com.volleyballlab.player.ui.theme.vbColors
import com.volleyballlab.player.ui.theme.vbTween
import kotlin.math.roundToInt

/**
 * The session form score, as an arc that fills from zero.
 *
 * ## Reading the design
 *
 * A 270-degree arc with a gap at the bottom rather than a full ring. The gap
 * gives the scale an unambiguous start and end, so a 95 is visibly near a
 * boundary rather than looking almost the same as a full circle.
 *
 * Colour is banded, not continuous. A smoothly interpolated hue would imply a
 * precision the underlying thresholds do not have - these are first-pass values
 * pending the coach interviews - so three honest bands are a truer
 * representation than a gradient.
 *
 * The whole component carries one content description and hides its internals
 * from accessibility services, so a screen reader announces "form score 82 out
 * of 100" instead of reading a bare number with no context.
 */
@Composable
fun ScoreArc(
    score: Int,
    modifier: Modifier = Modifier,
    diameter: androidx.compose.ui.unit.Dp = 168.dp,
    label: String? = null,
) {
    val target = (score.coerceIn(0, 100)) / 100f
    val progress = remember { Animatable(0f) }

    // Captured in composition because vbTween is a composable read; it cannot be
    // called from inside the effect body.
    val revealSpec = vbTween<Float>(
        durationMillis = VbMotion.ExtraLong,
        easing = VbMotion.EmphasizedDecelerate,
    )

    LaunchedEffect(target) {
        progress.animateTo(target, animationSpec = revealSpec)
    }

    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val arcColor = scoreColor(score)
    val description = stringResource(R.string.cd_score_arc, score)

    Box(
        modifier = modifier
            .size(diameter)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(diameter)) {
            val stroke = Stroke(width = STROKE_DP.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2f
            val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
            val topLeft = Offset(inset, inset)

            drawArc(
                color = trackColor,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            drawArc(
                color = arcColor,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE * progress.value,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Counts up alongside the arc rather than appearing at its final
            // value, so the two halves of the component agree at every instant.
            Text(
                text = (progress.value * 100).roundToInt().toString(),
                style = MaterialTheme.typography.displayMedium.merge(VbTabularNumerals),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Band colour for a score.
 *
 * Thresholds match the language used on the results screen: below 50 is a
 * technique problem worth stopping to fix, 50 to 74 is workable but drifting, 75
 * and above is sound.
 */
@Composable
fun scoreColor(score: Int): Color = when {
    score < POOR_CEILING -> MaterialTheme.colorScheme.error
    score < GOOD_FLOOR -> MaterialTheme.vbColors.warning
    else -> MaterialTheme.vbColors.success
}

private const val START_ANGLE = 135f
private const val SWEEP_ANGLE = 270f
private const val STROKE_DP = 14
private const val POOR_CEILING = 50
private const val GOOD_FLOOR = 75
