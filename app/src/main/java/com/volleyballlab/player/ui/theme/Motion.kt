package com.volleyballlab.player.ui.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween as composeTween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * The app's motion system.
 *
 * Every animation in Volleyball Lab pulls its duration and easing from here.
 * Nothing calls tween(300) with a literal, for the same reason nothing writes a
 * raw Color(0xFF...) inline: the moment durations are scattered across screens,
 * the app stops feeling like one product and there is no single place to tune it.
 *
 * ## Choosing a duration
 *
 * The rule is distance travelled, not importance. A control that changes state
 * in place gets [Short]. Something entering or leaving the screen gets [Medium].
 * A full-screen transition gets [Long]. [ExtraLong] is reserved for the splash
 * and for the result reveal, because anything else at that length reads as lag.
 *
 * ## Choosing an easing
 *
 * - [Emphasized] the default, for elements that both enter and settle.
 * - [EmphasizedDecelerate] things arriving on screen. Fast in, gentle stop.
 * - [EmphasizedAccelerate] things leaving. Gentle start, quick exit.
 * - [Standard] small functional changes that should not draw the eye.
 *
 * ## Accessibility
 *
 * All the helpers below collapse to zero duration when the user has turned
 * animations off system-wide. See [LocalReduceMotion]. Prefer them over raw
 * tween and spring so that the setting is actually honoured. Vestibular
 * disorders make large motion genuinely unpleasant, and Remove Animations in
 * Accessibility settings is the user telling us so.
 */
object VbMotion {

    /** In-place state changes: ripples, check marks, colour swaps. */
    const val Short = 150

    /** Elements entering or leaving within a screen. */
    const val Medium = 300

    /** Screen-level transitions and container transforms. */
    const val Long = 450

    /** A page-to-page fade. Short enough to stay out of the way of the content. */
    const val Navigation = 200

    /** Splash beats and the result reveal only. */
    const val ExtraLong = 700

    /** Stagger step between siblings in a list entrance. */
    const val StaggerStep = 30

    /** Ceiling on cumulative stagger delay, so long lists do not crawl in. */
    const val StaggerMaxDelay = 180

    // Material 3 easing curves.
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /**
     * Stagger delay for the item at [index], clamped by [StaggerMaxDelay].
     *
     * Without the clamp, a 20-item list would give its last row a 900 ms delay,
     * which does not read as staggered, it reads as broken.
     */
    fun staggerDelay(index: Int): Int =
        (index * StaggerStep).coerceAtMost(StaggerMaxDelay)

    /**
     * A reduce-motion-aware tween that is **not** composable.
     *
     * Needed because several of the places transitions are actually declared are
     * plain lambdas rather than composable ones - `AnimatedContent`'s
     * `transitionSpec`, and `NavHost`'s `enterTransition` and friends. The
     * composable [vbTween] cannot be called from inside them at all.
     *
     * The caller reads [LocalReduceMotion] in composable scope and passes it in.
     * Prefer [vbTween] wherever composition context is available; reach for this
     * only when it is not.
     */
    fun <T> tween(
        durationMillis: Int = Medium,
        delayMillis: Int = 0,
        easing: Easing = Emphasized,
        reduceMotion: Boolean = false,
    ): FiniteAnimationSpec<T> = composeTween(
        durationMillis = if (reduceMotion) 0 else durationMillis,
        delayMillis = if (reduceMotion) 0 else delayMillis,
        easing = easing,
    )
}

/**
 * True when the user has disabled system animations.
 *
 * Defaults to false rather than reading the setting, so a preview or a test that
 * forgets to provide it still animates normally. The real value is supplied by
 * VolleyballLabTheme for the running app.
 */
val LocalReduceMotion = compositionLocalOf { false }

/**
 * Reads Settings.Global.ANIMATOR_DURATION_SCALE and keeps watching it.
 *
 * The observer matters. The user can toggle Remove Animations in Accessibility
 * settings and come straight back to the app without it being recreated. Reading
 * the value once inside remember would leave the app animating against an
 * explicit preference until the next process death.
 */
@Composable
fun rememberSystemReduceMotion(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var reduceMotion by remember(resolver) {
        mutableStateOf(readAnimatorScale(resolver) == 0f)
    }

    DisposableEffect(resolver) {
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduceMotion = readAnimatorScale(resolver) == 0f
            }
        }
        resolver.registerContentObserver(uri, false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    return reduceMotion
}

private fun readAnimatorScale(resolver: ContentResolver): Float =
    runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)

/**
 * A tween that respects the user's reduce-motion preference.
 *
 * When motion is reduced, the duration and delay both collapse to zero, so the
 * value snaps instead of travelling. The animation still runs and state still
 * lands where it should. It just does not move through space.
 */
@Composable
@ReadOnlyComposable
fun <T> vbTween(
    durationMillis: Int = VbMotion.Medium,
    delayMillis: Int = 0,
    easing: Easing = VbMotion.Emphasized,
): FiniteAnimationSpec<T> = VbMotion.tween(
    durationMillis = durationMillis,
    delayMillis = delayMillis,
    easing = easing,
    reduceMotion = LocalReduceMotion.current,
)

/**
 * A spring that respects reduce-motion.
 *
 * Springs are the right choice when an animation is interrupted often, such as a
 * selection that can change mid-flight or a value being dragged, because they
 * retarget from their current velocity instead of restarting.
 */
@Composable
@ReadOnlyComposable
fun <T> vbSpring(
    dampingRatio: Float = Spring.DampingRatioLowBouncy,
    stiffness: Float = Spring.StiffnessMediumLow,
): SpringSpec<T> = spring(
    dampingRatio = if (LocalReduceMotion.current) Spring.DampingRatioNoBouncy else dampingRatio,
    stiffness = if (LocalReduceMotion.current) Spring.StiffnessHigh else stiffness,
)
