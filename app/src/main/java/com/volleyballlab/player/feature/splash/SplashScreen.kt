package com.volleyballlab.player.feature.splash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.foundation.background
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volleyballlab.player.R
import com.volleyballlab.player.ui.theme.VbFontFamily
import com.volleyballlab.player.ui.theme.VbSplashBackground
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Lightweight branded screen shown as soon as Compose draws its first frame.
 *
 * Android's launch window uses the same background without a separate graphic.
 * This screen displays the Volleyball Lab logo and title, and waits only for
 * startup routing. A WebView-backed 3D scene on this path delayed the first frame
 * on cold starts, even though the scene was not needed to choose a destination.
 */
@Composable
fun SplashScreen(
    isReady: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val readyState = rememberUpdatedState(isReady)
    val finishedState = rememberUpdatedState(onFinished)

    LaunchedEffect(Unit) {
        coroutineScope {
            // Give a fast warm launch one readable branded frame. The timer runs
            // alongside startup, so a slow auth or role read adds no extra wait.
            val minimumDisplay = async { delay(MIN_DISPLAY_MS) }
            snapshotFlow { readyState.value }.first { it }
            minimumDisplay.await()
            finishedState.value()
        }
    }

    Box(
        modifier = modifier.fillMaxSize().background(VbSplashBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.offset(y = (-24).dp),
        ) {
            Image(
                painter = painterResource(R.drawable.volleyball_lab_logo),
                contentDescription = stringResource(R.string.cd_app_logo),
                modifier = Modifier.size(132.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_name).uppercase(),
                color = WordmarkColor,
                fontFamily = VbFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                letterSpacing = 3.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.app_tagline),
                color = TaglineColor,
                fontFamily = VbFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            WaveLoader()
        }
    }
}

/** Transparent waveform with indeterminate startup progress. */
@Composable
private fun WaveLoader() {
    val transition = rememberInfiniteTransition(label = "Startup wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "Wave phase",
    )
    val loadingLabel = stringResource(R.string.loading_content)
    Canvas(
        Modifier.size(width = 72.dp, height = 40.dp).semantics {
            contentDescription = loadingLabel
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        },
    ) {
        val gradient = Brush.linearGradient(
            listOf(Color(0xFFA78BFA), Color(0xFF8B5CF6), Color(0xFF6D28D9)),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        val base = floatArrayOf(.24f, .48f, .78f, 1f, .78f, .48f, .24f)
        base.forEachIndexed { index, amplitude ->
            val pulse = .80f + .20f * sin(phase - index * .55f)
            val halfHeight = size.height * .40f * amplitude * pulse
            val x = size.width * (index + 1) / 8f
            drawLine(
                brush = gradient,
                start = Offset(x, size.height / 2 - halfHeight),
                end = Offset(x, size.height / 2 + halfHeight),
                strokeWidth = size.width * .065f,
                cap = StrokeCap.Round,
            )
        }
    }
}

private const val MIN_DISPLAY_MS = 350L
private val WordmarkColor = androidx.compose.ui.graphics.Color(0xFF141C33)
private val TaglineColor = androidx.compose.ui.graphics.Color(0xFF545D7D)
