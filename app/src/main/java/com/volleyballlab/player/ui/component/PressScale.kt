package com.volleyballlab.player.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.volleyballlab.player.ui.theme.vbSpring

/**
 * Shrinks a component slightly while it is held.
 *
 * Large tap targets - the skill cards, the drill rows - have a dead quality
 * without this. A ripple alone confirms the tap landed but says nothing about
 * whether the finger is still down, so dragging off to cancel gives no feedback
 * at all. Scale does, and it releases the moment the pointer leaves.
 *
 * Spring rather than tween on purpose: presses are interrupted constantly, and a
 * spring retargets from its current velocity instead of restarting, so a quick
 * double tap does not stutter. The spring collapses to a stiff, non-bouncy one
 * when the user has reduced motion, via [vbSpring].
 *
 * Applied through `graphicsLayer`, so it only ever triggers redraw - never
 * measure or layout - and cannot shift neighbouring content.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = vbSpring(),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
