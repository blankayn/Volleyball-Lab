package com.volleyballlab.player.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.volleyballlab.player.ui.theme.VbMotion
import com.volleyballlab.player.ui.theme.vbTween

/**
 * Fades and lifts its content into place once, shortly after it first appears.
 *
 * ## Why the stagger is capped, and why it plays only once
 *
 * [index] offsets the start so a list arrives in sequence rather than as one
 * block. [VbMotion.staggerDelay] caps the cumulative delay, because on a long
 * list an uncapped stagger stops reading as choreography and starts reading as
 * the app being slow to load.
 *
 * The played flag is `rememberSaveable`, so the entrance survives a
 * recomposition, a scroll that recycles the row, and a configuration change. An
 * entrance animation that replays every time a row scrolls back into view is one
 * of the most common ways an app feels cheap, and it is the exact failure mode
 * of doing this with a plain `remember`.
 *
 * @param index position among siblings, for the stagger. Pass 0 for a lone element.
 */
@Composable
fun EntranceAnimation(
    modifier: Modifier = Modifier,
    index: Int = 0,
    content: @Composable () -> Unit,
) {
    var hasPlayed by rememberSaveable { mutableStateOf(false) }
    var visible by remember { mutableStateOf(hasPlayed) }

    LaunchedEffect(Unit) {
        visible = true
        hasPlayed = true
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(
            animationSpec = vbTween(
                durationMillis = VbMotion.Short,
                delayMillis = VbMotion.staggerDelay(index),
                easing = VbMotion.EmphasizedDecelerate,
            ),
        ) + slideInVertically(
            animationSpec = vbTween(
                durationMillis = VbMotion.Short,
                delayMillis = VbMotion.staggerDelay(index),
                easing = VbMotion.EmphasizedDecelerate,
            ),
            // A small rise, proportional to the element's own height. Travelling
            // further would draw attention to the motion instead of to the
            // content arriving.
            initialOffsetY = { height -> height / 6 },
        ),
        content = { content() },
    )
}
