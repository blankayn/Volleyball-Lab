package com.volleyballlab.player.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.volleyballlab.player.R
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The volleyball wash that sits behind a scrolling page.
 *
 * A flat fill is what the coach shell had, and on a long page of white cards that
 * reads as an absence rather than a background. This gives the page a subject
 * without giving it anything to read: a real ball bleeding off the top-right
 * corner with a burst of colour behind it, a warm wash under the header, and a
 * second ball anchoring the bottom left so the composition does not sag toward one
 * corner.
 *
 * ## The ball is a photograph; the splash is drawn
 *
 * Two different jobs. `R.drawable.ballpng` is a real ball on a transparent
 * background - nothing hand-drawn was going to match it, and a photographic
 * subject is exactly what makes the page feel like it belongs to a sport rather
 * than to a spreadsheet. The splash behind it has to be drawn, because it takes
 * the *scheme's* primary colour: baked into the asset it would be one fixed orange
 * that stops agreeing with the brand the moment the palette moves.
 *
 * ## The alphas, and why the ball is louder than everything else here
 *
 * The wash and the splash stay between 7% and 12%, because the hero heading and
 * the section titles sit *directly* on this with no card beneath them, and body
 * copy over a busy field is the classic way a decorated background quietly costs a
 * screen its legibility.
 *
 * The balls are allowed considerably more - see [HERO_ALPHA] - and get away with
 * it for one reason: both are positioned so that most of each is off-canvas, in
 * corners no text occupies. The top-right ball sits opposite the greeting, and the
 * bottom-left one sits behind cards that are fully opaque. Move either toward the
 * middle of the page and the alpha has to come down with it.
 *
 * ## It does not scroll
 *
 * Deliberately placed behind the scrolling content rather than inside it, so the
 * ball stays put while cards move over it. A motif that scrolls has to either
 * repeat or run out, and both look like a mistake.
 */
@Composable
fun VolleyballBackdrop(
    modifier: Modifier = Modifier,
    baseColor: Color = MaterialTheme.colorScheme.background,
    splashColor: Color = MaterialTheme.colorScheme.primary,
    accentColor: Color = MaterialTheme.colorScheme.secondary,
) {
    val ball = ImageBitmap.imageResource(R.drawable.ballpng)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            // Pure decoration with no information in it. Left visible to
            // accessibility services it would be one more node for a screen reader
            // to step through on the way to the content that matters.
            .semantics { hideFromAccessibility() },
    ) {
        drawRect(color = baseColor)

        // The warm wash behind the header. Radial rather than linear, and anchored
        // just off the top-right corner, so the brightest part sits beside the
        // greeting instead of behind it - the heading keeps the flat page colour it
        // was contrast-checked against.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(splashColor.copy(alpha = 0.12f), Color.Transparent),
                center = Offset(size.width * 0.92f, size.height * 0.02f),
                radius = size.width * 1.05f,
            ),
        )

        // The hero ball, top-right, with only about a third of it on screen. That
        // crop is what keeps it reading as a background rather than as a picture of
        // a ball someone forgot to place.
        val heroWidth = size.width * 1.35f
        val heroCentre = Offset(size.width * 0.98f, size.height * 0.045f)
        splash(
            centre = heroCentre,
            radius = heroWidth * BALL_RADIUS_IN_IMAGE,
            color = splashColor,
            alpha = 0.11f,
        )
        drawBall(ball, heroCentre, heroWidth, rotationDeg = 0f, alpha = HERO_ALPHA)

        // The counterweight, bottom-left: smaller, fainter, its splash in the cool
        // secondary. Two identical bursts would read as a repeating pattern; one
        // dominant and one answering it reads as a composition. The rotation stops
        // the two balls looking like the same sticker used twice.
        val tailWidth = size.width * 0.95f
        val tailCentre = Offset(-size.width * 0.06f, size.height * 0.96f)
        splash(
            centre = tailCentre,
            radius = tailWidth * BALL_RADIUS_IN_IMAGE,
            color = accentColor,
            alpha = 0.07f,
        )
        drawBall(ball, tailCentre, tailWidth, rotationDeg = -18f, alpha = TAIL_ALPHA)
    }
}

/**
 * Draws the ball asset centred on [centre], scaled to [width] and keeping its
 * aspect ratio.
 *
 * The height comes from the bitmap rather than from a constant, so the ball cannot
 * be quietly stretched into an egg if the artwork is ever re-exported at a
 * different shape.
 */
private fun DrawScope.drawBall(
    image: ImageBitmap,
    centre: Offset,
    width: Float,
    rotationDeg: Float,
    alpha: Float,
) {
    val height = width * (image.height.toFloat() / image.width.toFloat())
    rotate(degrees = rotationDeg, pivot = centre) {
        drawImage(
            image = image,
            dstOffset = IntOffset(
                x = (centre.x - width / 2f).roundToInt(),
                y = (centre.y - height / 2f).roundToInt(),
            ),
            dstSize = IntSize(width.roundToInt(), height.roundToInt()),
            alpha = alpha,
            // The asset is drawn far larger than its 540px source, so the default
            // filtering shows its pixel grid on the ball's edge.
            filterQuality = FilterQuality.High,
        )
    }
}

/**
 * The burst behind a ball: lobes breaking its outline, then droplets thrown clear.
 *
 * What turns a ball sitting in a corner into a ball that has just been struck.
 * It also does quiet structural work - the asset's edge is a hard photographic
 * cutout, and colour spilling past it is what stops that edge reading as a sticker
 * pasted onto the page.
 *
 * ## The geometry is a fixed table, not random
 *
 * Deliberately. `Math.random()` inside a draw pass re-rolls on every
 * recomposition, so the splash would shimmer and crawl every time the state behind
 * it changed - one of those bugs that is invisible in a screenshot and unmissable
 * on a device. A hand-picked table is irregular enough to look organic and
 * identical on every frame.
 *
 * Angles are degrees clockwise from the right; distances and sizes are in units of
 * the ball's own [radius], so the burst scales with whatever ball it is given.
 */
private fun DrawScope.splash(
    centre: Offset,
    radius: Float,
    color: Color,
    alpha: Float,
) {
    fun at(angleDeg: Float, distance: Float): Offset {
        val radians = Math.toRadians(angleDeg.toDouble())
        return Offset(
            x = centre.x + (cos(radians) * distance * radius).toFloat(),
            y = centre.y + (sin(radians) * distance * radius).toFloat(),
        )
    }

    // Lobes straddling the rim. Sizes vary by nearly two to one so the silhouette
    // never settles into a scalloped, evenly-toothed edge.
    val lobes = listOf(
        Triple(12f, 1.02f, 0.30f),
        Triple(48f, 0.96f, 0.21f),
        Triple(84f, 1.05f, 0.26f),
        Triple(128f, 0.98f, 0.17f),
        Triple(168f, 1.06f, 0.28f),
        Triple(205f, 0.95f, 0.20f),
        Triple(242f, 1.04f, 0.24f),
        Triple(290f, 0.99f, 0.16f),
        Triple(330f, 1.07f, 0.27f),
    )
    lobes.forEach { (angle, distance, scale) ->
        drawCircle(color, radius * scale, at(angle, distance), alpha = alpha)
    }

    // Droplets thrown clear of the ball. Drawn at a higher alpha than the lobes
    // because they are small: at the lobes' opacity they would disappear, and a
    // splash whose droplets are invisible is just a lumpy circle.
    val droplets = listOf(
        Triple(25f, 1.44f, 0.075f),
        Triple(70f, 1.33f, 0.048f),
        Triple(150f, 1.50f, 0.090f),
        Triple(200f, 1.30f, 0.055f),
        Triple(265f, 1.46f, 0.068f),
        Triple(315f, 1.37f, 0.060f),
    )
    droplets.forEach { (angle, distance, scale) ->
        drawCircle(color, radius * scale, at(angle, distance), alpha = alpha * 1.5f)
    }
}

/**
 * How much of the asset's width the ball itself occupies.
 *
 * `ballpng.webp` is 540x360 with the ball centred in transparent margin, so the
 * sphere spans a little over half the frame. The splash is positioned against the
 * *ball*, not against the bitmap, and without this the burst would ring the
 * artboard and leave a visible gap around the ball it is supposed to be hugging.
 *
 * Re-measure this if the artwork is ever recropped.
 */
private const val BALL_RADIUS_IN_IMAGE = 0.28f

/**
 * The balls carry real colour where the rest of the backdrop whispers.
 *
 * They can afford it because of where they sit - see the note on alphas in
 * [VolleyballBackdrop]. Anything above roughly 0.45 starts to compete with the
 * stat figures through the gaps between cards.
 */
private const val HERO_ALPHA = 0.38f
private const val TAIL_ALPHA = 0.20f
