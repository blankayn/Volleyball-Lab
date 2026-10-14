package com.volleyballlab.player.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.volleyballlab.player.ui.theme.LocalReduceMotion
import kotlin.math.PI
import kotlin.math.sin

/** The drawing reads these clocks directly, keeping the card's text out of the animation loop. */
class CourtHeroMotion internal constructor(
    val ambientSeconds: State<Float>,
    val courtSeconds: State<Float>,
)

@Composable
fun rememberCourtHeroMotion(accelerateCourt: Boolean): CourtHeroMotion {
    val lifecycleOwner = LocalLifecycleOwner.current
    val reduceMotion = LocalReduceMotion.current
    val accelerated = rememberUpdatedState(accelerateCourt)
    val ambient = remember { mutableFloatStateOf(0f) }
    val court = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(lifecycleOwner, reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var previousFrame = 0L
            while (true) {
                withFrameNanos { frame ->
                    val deltaSeconds = if (previousFrame == 0L) 0f else
                        ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, .05f)
                    previousFrame = frame
                    ambient.floatValue = (ambient.floatValue + deltaSeconds) % 3600f
                    court.floatValue = (court.floatValue + deltaSeconds *
                        if (accelerated.value) 8f else 1f) % 7f
                }
            }
        }
    }
    return remember { CourtHeroMotion(ambient, court) }
}

/** Artwork uses the reference card's 515 x 402 coordinate space. */
@Composable
fun CourtHeroArtwork(motion: CourtHeroMotion, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val ambient = motion.ambientSeconds.value
        val courtPhase = motion.courtSeconds.value / 7f
        withTransform({ scale(size.width / 515f, size.height / 402f, pivot = Offset.Zero) }) {
            drawRect(Brush.horizontalGradient(0f to Color(0xFFAA360D), .25f to Color(0xFFD1450D),
                .45f to Color(0xFFE55710), .72f to Color(0xFFFF8918), 1f to Color(0xFFFF9925),
                startX = 0f, endX = 515f), size = Size(515f, 402f))
            drawCircle(Brush.radialGradient(0f to Color(0xFFFF5500).copy(alpha = .85f),
                .45f to Color(0xFFFF5500).copy(alpha = .45f), .82f to Color.Transparent,
                1f to Color.Transparent, center = Offset(130f, -115f), radius = 330f),
                radius = 330f, center = Offset(130f, -115f))
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFC346).copy(alpha = .48f), Color.Transparent),
                center = Offset(477f, 78f), radius = 280f), radius = 280f, center = Offset(477f, 78f))

            // The court advances toward the viewer. Crossfade the small final offset
            // into the next loop so the reference geometry never jumps backward.
            val resetBlend = ((courtPhase - .85f) / .15f).coerceIn(0f, 1f)
            drawReferenceCourt(courtPhase, 1f - resetBlend)
            if (resetBlend > 0f) drawReferenceCourt(courtPhase - 1f, resetBlend)

            val topLeft = Offset(372f, 167f)
            val topRight = Offset(533f, 135f)
            val bottomLeft = Offset(372f, 193f)
            val bottomRight = Offset(533f, 199f)
            drawLine(Color.White.copy(alpha = .40f), Offset(362f, 161f), Offset(362f, 225f), 2.8f)
            drawLine(Color.White.copy(alpha = .31f), topLeft, topRight, 2f)
            drawLine(Color.White.copy(alpha = .25f), bottomLeft, bottomRight, 2f)
            drawLine(Color.White.copy(alpha = .23f), topLeft, bottomLeft, 1.8f)
            for (index in 1 until 30) {
                val t = index / 30f
                drawLine(Color(0xFFB3450D).copy(alpha = .18f), lerp(topLeft, topRight, t),
                    lerp(bottomLeft, bottomRight, t), .85f)
            }
            for (index in 1 until 6) {
                val t = index / 6f
                drawLine(Color(0xFFB3450D).copy(alpha = .16f), lerp(topLeft, bottomLeft, t),
                    lerp(topRight, bottomRight, t), .85f)
            }

            val ballCenter = Offset(444f + sin(ambient * (2.0 * PI / 12.0)).toFloat() * 6f,
                76f + sin(ambient * (2.0 * PI / 5.0)).toFloat() * 3f)
            val radius = 58f
            val ballInk = Color.White.copy(alpha = .15f)
            val ballClip = Path().apply { addOval(androidx.compose.ui.geometry.Rect(ballCenter - Offset(radius, radius),
                ballCenter + Offset(radius, radius))) }
            rotate(ambient * 4f, pivot = ballCenter) {
                drawCircle(ballInk, radius, ballCenter, style = Stroke(3f))
                clipPath(ballClip) {
                    // Three curved sectors, each with two panel seams, form a volleyball.
                    repeat(3) { sector ->
                        rotate(sector * 120f, pivot = ballCenter) {
                            fun seam(startX: Float, startY: Float, c1x: Float, c1y: Float,
                                c2x: Float, c2y: Float, endX: Float, endY: Float): Path = Path().apply {
                                moveTo(ballCenter.x + startX * radius, ballCenter.y + startY * radius)
                                cubicTo(ballCenter.x + c1x * radius, ballCenter.y + c1y * radius,
                                    ballCenter.x + c2x * radius, ballCenter.y + c2y * radius,
                                    ballCenter.x + endX * radius, ballCenter.y + endY * radius)
                            }
                            drawPath(seam(0f, 0f, -.13f, -.29f, -.04f, -.72f, .34f, -.94f),
                                ballInk, style = Stroke(2.7f))
                            drawPath(seam(-.14f, -.99f, -.57f, -.63f, -.60f, -.10f, -.30f, .20f),
                                ballInk, style = Stroke(2.7f))
                            drawPath(seam(-.62f, -.79f, -.87f, -.47f, -.89f, -.07f, -.67f, .16f),
                                ballInk, style = Stroke(2.7f))
                        }
                    }
                }
            }
        }
    }
}

/** Soft pulse behind the white arrow, without changing the button's touch target. */
fun Modifier.courtHeroArrowGlow(motion: CourtHeroMotion): Modifier = drawBehind {
    val pulse = (sin(motion.ambientSeconds.value * (2.0 * PI / 3.6)).toFloat() + 1f) * .5f
    val radius = size.minDimension * .56f
    val center = Offset(size.width / 2f, size.height / 2f)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color(0xFFFFDC96).copy(alpha = .24f + .10f * pulse),
                Color(0xFFFF8B31).copy(alpha = .10f + .05f * pulse), Color.Transparent),
            center = center, radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

private fun lerp(start: Offset, end: Offset, fraction: Float): Offset =
    Offset(start.x + (end.x - start.x) * fraction, start.y + (end.y - start.y) * fraction)

private fun DrawScope.drawReferenceCourt(progress: Float, opacity: Float) {
    scale(1f + progress * .045f, pivot = Offset(205f, 216f)) {
        drawLine(Color.White.copy(alpha = .10f * opacity), Offset(206f, 221f), Offset(540f, 214f), 1.2f)
        drawLine(Color.White.copy(alpha = .22f * opacity), Offset(259f, 231f), Offset(539f, 215f), 1.5f)
        drawLine(Color.White.copy(alpha = .24f * opacity), Offset(259f, 231f), Offset(528f, 405f), 4f)
        drawLine(Color.White.copy(alpha = .28f * opacity), Offset(290f, 251f), Offset(546f, 222f), 1.6f)
        drawLine(Color.White.copy(alpha = .25f * opacity), Offset(382f, 310f), Offset(540f, 267f), 3.6f)
        drawLine(Color.White.copy(alpha = .20f * opacity), Offset(405f, 225f), Offset(559f, 251f), 1.5f)
        drawLine(Color.White.copy(alpha = .18f * opacity), Offset(227f, 226f), Offset(426f, 414f), 2.6f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 12f), 0f))
    }
}
