package com.volleyballlab.player.feature.welcome

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.R
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.domain.repository.WelcomeRepository
import com.volleyballlab.player.ui.theme.LocalReduceMotion
import com.volleyballlab.player.ui.theme.VbButtonShape
import com.volleyballlab.player.ui.theme.VbFontFamily
import com.volleyballlab.player.ui.theme.VbMotion
import com.volleyballlab.player.ui.theme.vbTween
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WelcomeViewModel(private val welcomeRepository: WelcomeRepository) : ViewModel() {
    fun finish() {
        viewModelScope.launch { welcomeRepository.markWelcomeSeen() }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { WelcomeViewModel(appContainer.welcomeRepository) }
        }
    }
}

private val BrandOrange = Color(0xFFCC5500)
private val Ink = Color(0xFF141C33)
private val BodyInk = Color(0xFF5D5650)

@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    onLogIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: WelcomeViewModel = viewModel(factory = WelcomeViewModel.Factory)
    val reduceMotion = LocalReduceMotion.current
    var revealed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!reduceMotion) delay(90)
        revealed = true
    }

    val headerReveal = welcomeRevealProgress(revealed, delayMillis = 0)
    val ruleReveal = welcomeRevealProgress(revealed, delayMillis = 60)
    val titleReveal = welcomeRevealProgress(revealed, delayMillis = 120)
    val subtitleReveal = welcomeRevealProgress(revealed, delayMillis = 180)
    val firstFeatureReveal = welcomeRevealProgress(revealed, delayMillis = 250)
    val secondFeatureReveal = welcomeRevealProgress(revealed, delayMillis = 310)
    val thirdFeatureReveal = welcomeRevealProgress(revealed, delayMillis = 370)
    val actionsReveal = welcomeRevealProgress(revealed, delayMillis = 450)

    Box(modifier.fillMaxSize().background(Color.White)) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 28.dp, vertical = 22.dp),
        ) {
            Row(
                modifier = Modifier.welcomeReveal(
                    progress = headerReveal,
                    distance = 10.dp,
                    initialScale = .96f,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.volleyball_lab_logo),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                )
                Text(
                    text = "VOLLEYBALL LAB",
                    color = Ink,
                    fontFamily = VbFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    letterSpacing = 1.8.sp,
                )
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 42.dp, bottom = 24.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(.18f)
                        .height(4.dp)
                        .graphicsLayer {
                            val progress = ruleReveal.value
                            alpha = progress
                            scaleX = progress
                            transformOrigin = TransformOrigin(0f, .5f)
                        }
                        .background(BrandOrange),
                )
                Spacer(Modifier.height(22.dp))
                Text(
                    text = stringResource(R.string.welcome_title),
                    modifier = Modifier.welcomeReveal(titleReveal, distance = 18.dp),
                    color = Ink,
                    fontFamily = VbFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 40.sp,
                    lineHeight = 44.sp,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.welcome_subtitle),
                    modifier = Modifier.welcomeReveal(subtitleReveal, distance = 16.dp),
                    color = BodyInk,
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 24.sp,
                )
                Spacer(Modifier.height(34.dp))
                WelcomeFeature(
                    icon = Icons.Rounded.Videocam,
                    title = stringResource(R.string.welcome_feature_form_title),
                    body = stringResource(R.string.welcome_feature_form_body),
                    modifier = Modifier.welcomeReveal(firstFeatureReveal, distance = 20.dp),
                )
                Spacer(Modifier.height(20.dp))
                WelcomeFeature(
                    icon = Icons.Rounded.Forum,
                    title = stringResource(R.string.welcome_feature_coach_title),
                    body = stringResource(R.string.welcome_feature_coach_body),
                    modifier = Modifier.welcomeReveal(secondFeatureReveal, distance = 20.dp),
                )
                Spacer(Modifier.height(20.dp))
                WelcomeFeature(
                    icon = Icons.Rounded.Insights,
                    title = stringResource(R.string.welcome_feature_progress_title),
                    body = stringResource(R.string.welcome_feature_progress_body),
                    modifier = Modifier.welcomeReveal(thirdFeatureReveal, distance = 20.dp),
                )
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .welcomeReveal(actionsReveal, distance = 22.dp),
            ) {
                Button(
                    onClick = {
                        viewModel.finish()
                        onGetStarted()
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = VbButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandOrange,
                        contentColor = Color.White,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.welcome_get_started),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                }
                TextButton(
                    onClick = {
                        viewModel.finish()
                        onLogIn()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text(
                        text = stringResource(R.string.welcome_log_in),
                        color = BrandOrange,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun welcomeRevealProgress(visible: Boolean, delayMillis: Int): State<Float> =
    animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = vbTween<Float>(
            durationMillis = VbMotion.Medium,
            delayMillis = delayMillis,
            easing = VbMotion.EmphasizedDecelerate,
        ),
        label = "welcomeReveal",
    )

private fun Modifier.welcomeReveal(
    progress: State<Float>,
    distance: Dp = 16.dp,
    initialScale: Float = 1f,
): Modifier = graphicsLayer {
    val value = progress.value
    alpha = value
    translationY = (1f - value) * distance.toPx()
    if (initialScale != 1f) {
        val scale = initialScale + (1f - initialScale) * value
        scaleX = scale
        scaleY = scale
        transformOrigin = TransformOrigin.Center
    }
}

@Composable
private fun WelcomeFeature(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier
                .size(42.dp)
                .background(BrandOrange.copy(alpha = .10f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = BrandOrange, modifier = Modifier.size(22.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, color = BodyInk, style = MaterialTheme.typography.bodyMedium, lineHeight = 20.sp)
        }
    }
}
