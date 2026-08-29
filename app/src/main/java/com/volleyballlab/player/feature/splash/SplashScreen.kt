package com.volleyballlab.player.feature.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volleyballlab.player.R
import com.volleyballlab.player.ui.component.AthleteAnimation
import com.volleyballlab.player.ui.component.AthleteScene
import com.volleyballlab.player.ui.component.rememberAthleteSceneController
import com.volleyballlab.player.ui.theme.LocalReduceMotion
import com.volleyballlab.player.ui.theme.VbFontFamily
import com.volleyballlab.player.ui.theme.VbSplashBackground
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The animated splash.
 *
 * ## Relationship to `docs/rive/splash-contract.md`
 *
 * That document specifies a `splash.riv` with a state machine `SM_Splash`, three
 * timelines and a single boolean input `isReady`. The Rive file does not exist
 * yet, so this is a Compose implementation of **the same state machine, with the
 * same timings and the same input semantics**:
 *
 * | Contract        | Here                                    |
 * |-----------------|-----------------------------------------|
 * | `Intro` 1200 ms | [playIntro]                             |
 * | `Idle` 2000 ms  | [playIdleCycle], looped                 |
 * | `Outro` 400 ms  | [playOutro]                             |
 * | `isReady`       | the [isReady] parameter                 |
 *
 * The transition the contract calls out as the one people forget - `Intro` to
 * `Outro` directly, at 100% exit time - is implemented: the intro always runs to
 * completion, and if [isReady] is already true when it ends, the idle loop is
 * skipped entirely. That is the warm-start path, and without it an app that
 * resolves auth in 300 ms would sit through a full idle cycle for no reason.
 *
 * `Idle` to `Outro` has no exit time in the contract, so the loop is cancelled
 * mid-cycle the moment [isReady] flips rather than finishing its 2000 ms.
 *
 * Swapping in the real Rive file replaces the body of this composable and
 * nothing else: the parameters and the phase semantics are already the contract's.
 *
 * @param isReady the app knows where to navigate. Per section 5 of the contract
 *   this must become true even when startup fails, or the splash hangs forever.
 * @param onFinished called once, after the outro completes.
 */
@Composable
fun SplashScreen(
    isReady: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    showAthlete: Boolean = true,
) {
    val athlete = rememberAthleteSceneController()
    var athleteReady by remember { mutableStateOf(false) }
    val athleteAlpha = remember { Animatable(0f) }

    // The splash never waits for the character. It boots alongside the brand
    // mark and fades in only if it makes it in time; if the WebView is slow, or
    // WebGL is unavailable, or the model is missing, what remains is the mark,
    // the wordmark and the tagline - a complete splash on its own. A splash that
    // waits on a 3D scene has stopped covering startup and started causing it.
    LaunchedEffect(athleteReady) {
        if (athleteReady) {
            athleteAlpha.animateTo(1f, tween(ATHLETE_FADE_MS, easing = EaseOutCubic))
        }
    }

    val markScale = remember { Animatable(INITIAL_MARK_SCALE) }
    val markAlpha = remember { Animatable(0f) }
    val wordmarkAlpha = remember { Animatable(0f) }
    val wordmarkOffset = remember { Animatable(WORDMARK_RISE_DP) }
    val taglineAlpha = remember { Animatable(0f) }
    val taglineOffset = remember { Animatable(TAGLINE_RISE_DP) }

    // rememberUpdatedState, because the effect below runs once with
    // LaunchedEffect(Unit) and must observe later changes to isReady rather than
    // capturing whatever it was at first composition.
    val readyState = rememberUpdatedState(isReady)
    val reduceMotion = LocalReduceMotion.current

    LaunchedEffect(Unit) {
        if (reduceMotion) {
            // Honour the accessibility preference by showing the brand mark
            // statically for one readable beat, then leaving. Still a splash,
            // just not a moving one.
            markScale.snapTo(1f)
            markAlpha.snapTo(1f)
            wordmarkAlpha.snapTo(1f)
            wordmarkOffset.snapTo(0f)
            taglineAlpha.snapTo(1f)
            taglineOffset.snapTo(0f)
            snapshotFlow { readyState.value }.first { it }
            delay(REDUCED_MOTION_HOLD_MS)
            onFinished()
            return@LaunchedEffect
        }

        playIntro(markScale, markAlpha, wordmarkAlpha, wordmarkOffset, taglineAlpha, taglineOffset)

        // Intro -> Idle only if we are still waiting. Otherwise fall straight
        // through to the outro, which is the contract's Intro -> Outro edge.
        if (!readyState.value) {
            val idle = launch {
                while (isActive) playIdleCycle(markScale)
            }
            snapshotFlow { readyState.value }.first { it }
            idle.cancelAndJoin()
            // The loop may have been cancelled mid-breath. Settle back to rest so
            // the outro starts from a known scale rather than from wherever the
            // idle happened to be.
            markScale.animateTo(1f, tween(IDLE_SETTLE_MS, easing = EaseInOutSine))
        }

        playOutro(markScale, markAlpha, wordmarkAlpha, wordmarkOffset, taglineAlpha, taglineOffset)
        onFinished()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Opaque for the entire outro, per section 3 of the contract. The
            // navigation transition performs the actual dismissal.
            .background(VbSplashBackground),
        contentAlignment = Alignment.Center,
    ) {
        if (!reduceMotion) {
            SplashBackdrop(Modifier.fillMaxSize())
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            // Sits slightly above optical centre, as the contract's artboard
            // notes specify. Dead centre reads as low to the eye.
            modifier = Modifier.offset(y = -CONTENT_LIFT_DP.dp),
        ) {
            // The brand mark stays up for the whole splash. It is not a stand-in
            // for the character and does not hand over to it: they sit together,
            // the mark above and the athlete below, so the logo is on screen for
            // every frame of every cold start regardless of whether the 3D scene
            // ever finishes loading.
            Image(
                painter = painterResource(R.drawable.volleyball_lab_logo),
                contentDescription = stringResource(R.string.cd_app_logo),
                modifier = Modifier
                    .size(MARK_SIZE_DP.dp)
                    .scale(markScale.value)
                    .alpha(markAlpha.value),
            )

            // One gap that serves both compositions: below the mark whether the
            // next thing down is the character or, when it never arrives, the
            // wordmark.
            Spacer(Modifier.height(MARK_GAP_DP.dp))

            // Not under reduce-motion. A looping wave is motion, and the whole
            // point of that preference is that a subtler animation is not the
            // accommodation - no animation is.
            if (showAthlete && !reduceMotion) {
                AthleteScene(
                    controller = athlete,
                    transparent = true,
                    onReady = { animations ->
                        if (AthleteAnimation.Wave.jsName in animations) {
                            athlete.setCamera(
                                theta = 14f,
                                phi = 80f,
                                distance = 3.5f,
                                targetHeight = 0.95f,
                            )
                            athlete.play(AthleteAnimation.Wave)
                            athleteReady = true
                        }
                    },
                    modifier = Modifier
                        // The slot costs the column no height until the
                        // character is actually in it, and opens as it fades up.
                        // The scene is still measured and drawn at its full
                        // size - only the height reported upward is scaled - so
                        // the WebView boots and runs exactly as it would
                        // otherwise, which it must, since it cannot report
                        // itself ready from a box with no height. Without this
                        // the column reserves 176 dp for something that can
                        // take longer to arrive than the whole splash lasts, or
                        // never arrive at all, and what is on screen in the
                        // meantime is the mark stranded above a hole. Read here
                        // in the layout phase, so the gap opening re-lays-out
                        // without recomposing.
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val reveal = (placeable.height * athleteAlpha.value).roundToInt()
                            layout(placeable.width, reveal) { placeable.place(0, 0) }
                        }
                        .size(ATHLETE_SIZE_DP.dp)
                        // Multiplied by markAlpha so the character joins the
                        // outro rather than being the one element left standing
                        // while the rest of the splash leaves.
                        .alpha(athleteAlpha.value * markAlpha.value),
                )
            }
            Text(
                text = stringResource(R.string.app_name).uppercase(),
                color = WordmarkColor,
                fontFamily = VbFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                letterSpacing = 3.sp,
                textAlign = TextAlign.Center,
                // The lambda overload of offset defers reading the animated value
                // to the layout phase, so each frame skips recomposition and only
                // re-lays-out. On an animation that runs on every cold start,
                // that is the difference between smooth and merely acceptable.
                modifier = Modifier
                    .offset { IntOffset(x = 0, y = wordmarkOffset.value.dp.roundToPx()) }
                    .alpha(wordmarkAlpha.value),
            )
            Text(
                text = stringResource(R.string.app_tagline),
                color = TaglineColor,
                fontFamily = VbFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .offset { IntOffset(x = 0, y = taglineOffset.value.dp.roundToPx()) }
                    .alpha(taglineAlpha.value)
                    .semantics { contentDescription = "" },
            )
        }
    }
}

/**
 * The atmosphere behind the mark: a warm wash and three pulses rising out of it.
 *
 * ## Why it is drawn rather than composed
 *
 * One `Canvas` and three floats, instead of a stack of animated `Box`es with
 * their own layers. On a cold start the splash is competing with everything else
 * the app is doing to boot, and a handful of draw calls in a single layer is the
 * version that still holds its frame rate while MediaPipe and the WebView are
 * both initialising behind it.
 *
 * The whole thing is skipped under reduce-motion, which is why the caller guards
 * it rather than this fading itself out - vestibular sensitivity is not served by
 * a subtler pulse, only by no pulse.
 */
@Composable
private fun SplashBackdrop(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "splashBackdrop")

    // Three rings on one clock, read at staggered offsets. Separate animations
    // would drift apart over a long cold start; one phase cannot.
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(RING_PERIOD_MS, easing = LinearEasing),
        ),
        label = "ringPhase",
    )

    val glow by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(GLOW_PERIOD_MS, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowBreath",
    )

    Canvas(modifier) {
        // Matches the content Column's optical lift, converted here rather than
        // hardcoded in pixels so the rings stay centred on the mark at every
        // screen density instead of only at the one they were eyeballed on.
        val centre = Offset(
            x = size.width / 2f,
            y = size.height / 2f - CONTENT_LIFT_DP.dp.toPx(),
        )
        val reach = size.minDimension * 0.62f

        // Warm wash. The primary at very low alpha, so the page still reads as
        // Alice Blue and the orange is felt rather than seen.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(GlowColor, GlowColor.copy(alpha = 0f)),
                center = centre,
                radius = reach * glow,
            ),
            radius = reach * glow,
            center = centre,
        )

        repeat(RING_COUNT) { index ->
            val ringPhase = (phase + index.toFloat() / RING_COUNT) % 1f
            // Eased outward so each ring leaves quickly and thins as it goes,
            // the way a struck surface actually rings.
            val radius = reach * (RING_START + (1f - RING_START) * ringPhase)
            val alpha = (1f - ringPhase) * (1f - ringPhase) * RING_PEAK_ALPHA
            drawCircle(
                color = RingColor.copy(alpha = alpha),
                radius = radius,
                center = centre,
                style = Stroke(width = RING_STROKE_DP.dp.toPx()),
            )
        }
    }
}

/**
 * `Intro`, 1200 ms.
 *
 * The three elements are launched concurrently with staggered delays rather than
 * animated in sequence. The overlap is the whole point: the wordmark starts
 * arriving while the mark is still settling, which is what separates a designed
 * entrance from three animations queued back to back.
 */
private suspend fun playIntro(
    markScale: Animatable<Float, *>,
    markAlpha: Animatable<Float, *>,
    wordmarkAlpha: Animatable<Float, *>,
    wordmarkOffset: Animatable<Float, *>,
    taglineAlpha: Animatable<Float, *>,
    taglineOffset: Animatable<Float, *>,
) = coroutineScope {
    // 150-750 ms: mark fades up and scales in, overshooting slightly before it settles.
    launch {
        delay(150)
        markAlpha.animateTo(1f, tween(600, easing = EaseOutCubic))
    }
    launch {
        delay(150)
        markScale.animateTo(
            targetValue = 1f,
            animationSpec = keyframes {
                durationMillis = 600
                INITIAL_MARK_SCALE at 0
                MARK_OVERSHOOT at 450
                1f at 600
            },
        )
    }

    // 500-900 ms: wordmark, deliberately overlapping the mark.
    launch {
        delay(500)
        launch { wordmarkAlpha.animateTo(1f, tween(400, easing = EaseOutCubic)) }
        launch { wordmarkOffset.animateTo(0f, tween(400, easing = EaseOutCubic)) }
    }

    // 750-1100 ms: tagline.
    launch {
        delay(750)
        launch { taglineAlpha.animateTo(1f, tween(350, easing = EaseOutCubic)) }
        launch { taglineOffset.animateTo(0f, tween(350, easing = EaseOutCubic)) }
    }

    // Holds the full 1200 ms so the last 100 ms is a settle with no motion,
    // giving the eye a beat before the idle loop begins.
    delay(1200)
}

/**
 * One `Idle` cycle, 2000 ms, looping.
 *
 * Deliberately almost imperceptible. On a cold start with a slow network this can
 * repeat for many seconds, and anything energetic becomes irritating fast. The
 * cycle begins and ends at exactly 1.0 so the loop seam is invisible.
 */
private suspend fun playIdleCycle(markScale: Animatable<Float, *>) {
    markScale.animateTo(IDLE_BREATH_SCALE, tween(1000, easing = EaseInOutSine))
    markScale.animateTo(1f, tween(1000, easing = EaseInOutSine))
}

/**
 * `Outro`, 400 ms.
 *
 * Fast and decisive. The user has already waited; this is not the moment for
 * another leisurely animation.
 */
private suspend fun playOutro(
    markScale: Animatable<Float, *>,
    markAlpha: Animatable<Float, *>,
    wordmarkAlpha: Animatable<Float, *>,
    wordmarkOffset: Animatable<Float, *>,
    taglineAlpha: Animatable<Float, *>,
    taglineOffset: Animatable<Float, *>,
) = coroutineScope {
    // 0-250 ms: text drops away first.
    launch { taglineAlpha.animateTo(0f, tween(250, easing = EaseInCubic)) }
    launch { taglineOffset.animateTo(OUTRO_DROP_DP, tween(250, easing = EaseInCubic)) }
    launch { wordmarkAlpha.animateTo(0f, tween(250, easing = EaseInCubic)) }
    launch { wordmarkOffset.animateTo(OUTRO_DROP_DP, tween(250, easing = EaseInCubic)) }

    // 100-400 ms: the mark expands through the viewer and fades.
    launch {
        delay(100)
        launch { markScale.animateTo(OUTRO_MARK_SCALE, tween(300, easing = EaseInCubic)) }
        launch { markAlpha.animateTo(0f, tween(300, easing = EaseInCubic)) }
    }

    delay(400)
}

private const val INITIAL_MARK_SCALE = 0.86f
private const val MARK_OVERSHOOT = 1.03f
private const val IDLE_BREATH_SCALE = 1.02f
private const val OUTRO_MARK_SCALE = 1.12f
private const val WORDMARK_RISE_DP = 24f
private const val TAGLINE_RISE_DP = 16f
private const val OUTRO_DROP_DP = 12f
private const val MARK_SIZE_DP = 100
private const val MARK_GAP_DP = 12
private const val IDLE_SETTLE_MS = 180
private const val REDUCED_MOTION_HOLD_MS = 450L

/**
 * Sized to sit under the mark without the pair overflowing a short screen.
 *
 * The mark and the athlete stack, so their heights compete: together with the
 * gap, the wordmark and the tagline this comes to roughly 340 dp, which clears
 * the shortest phone this app targets. A figure still needs room to read as a
 * figure - the wave carries a hand well above the head - so the mark gave up the
 * larger share. This is also the worst case: until the character is ready the
 * slot is collapsed and the splash is 165 dp of mark and text.
 */
private const val ATHLETE_SIZE_DP = 176

/** Long enough to read as a hand-off, short enough not to eat the idle beat. */
private const val ATHLETE_FADE_MS = 420

private const val RING_COUNT = 3
private const val RING_PERIOD_MS = 2600
private const val RING_START = 0.30f
private const val RING_PEAK_ALPHA = 0.30f
private const val RING_STROKE_DP = 1.5f
private const val GLOW_PERIOD_MS = 2200

/** The content Column's upward nudge from dead centre, shared with the backdrop. */
private const val CONTENT_LIFT_DP = 24f

private val WordmarkColor = androidx.compose.ui.graphics.Color(0xFF141C33) // InkNavy
private val TaglineColor = androidx.compose.ui.graphics.Color(0xFF545D7D) // Court40

/** Burnt orange, the brand primary, at wash strength. */
private val GlowColor = androidx.compose.ui.graphics.Color(0x1FCC5500)
private val RingColor = androidx.compose.ui.graphics.Color(0xFFCC5500)
