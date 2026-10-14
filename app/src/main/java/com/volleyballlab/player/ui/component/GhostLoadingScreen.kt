package com.volleyballlab.player.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.R
import com.volleyballlab.player.ui.theme.LocalReduceMotion

/** Screen-shaped placeholders while the first repository snapshot is pending. */
enum class GhostLayout { Dashboard, Progress, Profile, List, Detail }

@Composable
fun GhostLoadingScreen(
    layout: GhostLayout,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val brush = rememberGhostBrush()
    val loadingLabel = stringResource(R.string.loading_content)

    LazyColumn(
        modifier = modifier.fillMaxSize().clearAndSetSemantics {
            contentDescription = loadingLabel
        },
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        userScrollEnabled = false,
    ) {
        item {
            GhostHeader(brush, layout == GhostLayout.Profile || layout == GhostLayout.Detail)
        }
        when (layout) {
            GhostLayout.Dashboard -> {
                item { GhostCard(brush, 150.dp, Modifier.padding(horizontal = 20.dp)) }
                item { GhostSection(brush) }
                items(2) { GhostCard(brush, 78.dp, Modifier.padding(horizontal = 20.dp)) }
                item { GhostTiles(brush) }
            }
            GhostLayout.Progress -> {
                item { GhostTiles(brush) }
                item { GhostSection(brush) }
                item { GhostChart(brush) }
                item { GhostSection(brush) }
                items(2) { GhostCard(brush, 74.dp, Modifier.padding(horizontal = 20.dp)) }
            }
            GhostLayout.Profile -> {
                item { GhostCard(brush, 106.dp, Modifier.padding(horizontal = 20.dp)) }
                item { GhostSection(brush) }
                item { GhostCard(brush, 126.dp, Modifier.padding(horizontal = 20.dp)) }
                item { GhostTiles(brush) }
            }
            GhostLayout.List -> {
                item { GhostSection(brush) }
                items(4) { GhostCard(brush, 76.dp, Modifier.padding(horizontal = 20.dp)) }
            }
            GhostLayout.Detail -> {
                item { GhostCard(brush, 174.dp, Modifier.padding(horizontal = 20.dp)) }
                item { GhostSection(brush) }
                items(2) { GhostCard(brush, 82.dp, Modifier.padding(horizontal = 20.dp)) }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
fun GhostLoadingCard(modifier: Modifier = Modifier, height: Dp = 112.dp) {
    val loadingLabel = stringResource(R.string.loading_content)
    GhostCard(
        rememberGhostBrush(),
        height,
        modifier.clearAndSetSemantics { contentDescription = loadingLabel },
    )
}

@Composable
private fun rememberGhostBrush(): Brush {
    val reduceMotion = LocalReduceMotion.current
    val phase = if (reduceMotion) 0f else {
        val transition = rememberInfiniteTransition(label = "ghostLoading")
        val animated by transition.animateFloat(
            initialValue = -500f,
            targetValue = 1600f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
            label = "ghostSweep",
        )
        animated
    }
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val highlight = MaterialTheme.colorScheme.surfaceBright
    return if (reduceMotion) {
        Brush.linearGradient(listOf(base, base))
    } else {
        Brush.linearGradient(
            colors = listOf(base, highlight, base),
            start = Offset(phase, 0f),
            end = Offset(phase + 440f, 240f),
        )
    }
}

@Composable
private fun GhostHeader(brush: Brush, avatar: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (avatar) {
            GhostBlock(brush, Modifier.size(58.dp), CircleShape)
            Spacer(Modifier.width(14.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostBlock(brush, Modifier.width(126.dp).height(13.dp))
            GhostBlock(brush, Modifier.width(if (avatar) 166.dp else 226.dp).height(25.dp))
        }
    }
}

@Composable
private fun GhostSection(brush: Brush) {
    GhostBlock(
        brush = brush,
        modifier = Modifier.padding(horizontal = 20.dp).width(142.dp).height(16.dp),
    )
}

@Composable
private fun GhostCard(brush: Brush, height: Dp, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().height(height),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GhostBlock(brush, Modifier.fillMaxWidth(0.48f).height(15.dp))
            GhostBlock(brush, Modifier.fillMaxWidth(0.82f).height(11.dp))
            GhostBlock(brush, Modifier.fillMaxWidth(0.62f).height(11.dp))
        }
    }
}

@Composable
private fun GhostTiles(brush: Brush) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(3) {
            Surface(
                modifier = Modifier.weight(1f).height(76.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    GhostBlock(brush, Modifier.fillMaxWidth(0.65f).height(22.dp))
                    GhostBlock(brush, Modifier.fillMaxWidth().height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun GhostChart(brush: Brush) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(210.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(18.dp)) {
            GhostBlock(brush, Modifier.width(126.dp).height(12.dp))
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                listOf(0.44f, 0.7f, 0.55f, 0.82f, 0.64f, 0.91f).forEach { fraction ->
                    GhostBlock(
                        brush,
                        Modifier.weight(1f).fillMaxHeight(fraction),
                        RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GhostBlock(brush: Brush, modifier: Modifier, shape: Shape = RoundedCornerShape(8.dp)) {
    Box(modifier.background(brush, shape))
}
