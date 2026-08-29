package com.volleyballlab.player.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import com.volleyballlab.player.ui.theme.LocalReduceMotion
import com.volleyballlab.player.ui.theme.VbMotion
import com.volleyballlab.player.ui.theme.VbTabularNumerals

/**
 * A number that rolls when it changes.
 *
 * Used for the live rep count and the elapsed timer, both of which update while
 * the athlete is mid-drill and glancing at the screen. Motion direction carries
 * meaning: counting up slides up, counting down slides down, so the direction of
 * change is readable at a glance without reading the digits.
 *
 * The style is merged with [VbTabularNumerals] so digits keep a fixed width.
 * Without that, a proportional 1 is narrower than a 7 and the number visibly
 * shifts sideways on every tick, which is far more distracting than the roll.
 */
@Composable
fun AnimatedCounter(
    value: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    // Captured here, in composable scope: transitionSpec below is a plain lambda
    // and cannot read a CompositionLocal.
    val reduceMotion = LocalReduceMotion.current

    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            val goingUp = targetState > initialState
            // IntOffset, not Int: slideIn/slideOutVertically animate an offset.
            val enterSpec = VbMotion.tween<IntOffset>(
                durationMillis = VbMotion.Short,
                easing = VbMotion.EmphasizedDecelerate,
                reduceMotion = reduceMotion,
            )
            val exitSpec = VbMotion.tween<IntOffset>(
                durationMillis = VbMotion.Short,
                easing = VbMotion.EmphasizedAccelerate,
                reduceMotion = reduceMotion,
            )
            val fadeSpec = VbMotion.tween<Float>(
                durationMillis = VbMotion.Short,
                reduceMotion = reduceMotion,
            )

            val enter = slideInVertically(enterSpec) { height ->
                if (goingUp) height else -height
            } + fadeIn(fadeSpec)
            val exit = slideOutVertically(exitSpec) { height ->
                if (goingUp) -height else height
            } + fadeOut(fadeSpec)

            // Clipping is off so the outgoing digit is visible as it leaves.
            // With it on, the roll looks like an abrupt swap.
            (enter togetherWith exit).using(SizeTransform(clip = false))
        },
        label = "AnimatedCounter",
    ) { target ->
        Text(text = target.toString(), style = style.merge(VbTabularNumerals))
    }
}
