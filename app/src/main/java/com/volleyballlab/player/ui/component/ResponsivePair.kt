package com.volleyballlab.player.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Two related controls share a row only when both have enough room for the text size. */
@Composable
fun ResponsivePair(
    modifier: Modifier = Modifier,
    minItemWidth: Dp = 144.dp,
    spacing: Dp = 12.dp,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth < minItemWidth * (2 * fontScale) + spacing) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1f))
            }
        }
    }
}
