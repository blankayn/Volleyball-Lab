package com.volleyballlab.player.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Static art direction for the student pages.
 *
 * The previous ambient clock redrew a full-screen gradient on every frame, on
 * every tab. The pools and seams are decoration; holding a fixed phase removes
 * that perpetual work without changing the composition.
 */
@Composable
private fun studentMotion(): State<Float> = rememberUpdatedState(.35f)

/** Low-contrast pools of the existing brand colours, behind the scrollable student pages. */
@Composable
fun Modifier.studentBackground(): Modifier {
    val colors = MaterialTheme.colorScheme
    val motion = studentMotion()
    return drawBehind {
        drawRect(colors.background)
        val phase = motion.value
        val top = Offset(size.width * (.88f - phase * .12f), size.height * .12f)
        drawCircle(Brush.radialGradient(listOf(colors.primary.copy(alpha = .085f), Color.Transparent),
            center = top, radius = size.width * .85f), radius = size.width * .85f, center = top)
        val bottom = Offset(size.width * (.08f + phase * .1f), size.height * .8f)
        drawCircle(Brush.radialGradient(listOf(colors.secondary.copy(alpha = .045f), Color.Transparent),
            center = bottom, radius = size.width * .8f), radius = size.width * .8f, center = bottom)
        drawCircle(colors.primary.copy(alpha = .045f), size.width * .64f,
            Offset(size.width * 1.18f, size.height * .38f + phase * 12.dp.toPx()), style = Stroke(1.dp.toPx()))
    }
}

/** Shared card treatment. Buttons retain the app's existing press and ripple feedback. */
@Composable
fun StudentCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    color: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
    accent: Color = MaterialTheme.colorScheme.primary,
    border: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val outline = border ?: BorderStroke(1.dp, Brush.linearGradient(listOf(
        accent.copy(alpha = .18f), MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f), accent.copy(alpha = .06f))))
    val decorated: @Composable () -> Unit = {
        Box(Modifier.drawBehind {
            val center = Offset(size.width, 0f)
            val radius = size.width * .65f
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .055f), Color.Transparent), center, radius), radius, center)
        }, propagateMinConstraints = true) { content() }
    }
    if (onClick == null) {
        Surface(modifier, shape = shape, color = color, border = outline, shadowElevation = 2.dp, content = decorated)
    } else {
        Surface(onClick = onClick, modifier = modifier.pressScale(interaction, .985f), shape = shape,
            color = color, border = outline, shadowElevation = 2.dp, interactionSource = interaction, content = decorated)
    }
}

/** Oversized volleyball seams drift slowly inside feature cards, never over controls. */
@Composable
fun CourtCardArtwork(modifier: Modifier = Modifier, ink: Color = Color.White) {
    val motion = studentMotion()
    Canvas(modifier) {
        val phase = motion.value
        val center = Offset(size.width * .94f, size.height * (.3f + phase * .07f))
        val radius = size.width * .43f
        drawCircle(ink.copy(alpha = .035f), radius, center)
        rotate(phase * 12f - 6f, center) {
            drawCircle(ink.copy(alpha = .13f), radius, center, style = Stroke(1.dp.toPx()))
            drawCircle(ink.copy(alpha = .09f), radius * .72f, center, style = Stroke(1.dp.toPx()))
            drawArc(ink.copy(alpha = .15f), 65f, 150f, false,
                Offset(center.x - radius * .7f, center.y - radius), Size(radius, radius * 2), style = Stroke(1.dp.toPx()))
            drawLine(ink.copy(alpha = .09f), Offset(center.x - radius, center.y + radius * .35f),
                Offset(center.x + radius, center.y - radius * .25f), 1.dp.toPx())
        }
    }
}

@Composable
fun StudentPageHeading(eyebrow: String, title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(eyebrow, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
