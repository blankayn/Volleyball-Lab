package com.volleyballlab.player.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volleyballlab.player.R
import com.volleyballlab.player.ui.theme.LocalReduceMotion
import com.volleyballlab.player.ui.theme.VbMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Reference proportions scale with the card width; larger accessibility text gets extra height. */
@Composable
fun TrainingHeroCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    var opening by remember { mutableStateOf(false) }
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val currentClick by rememberUpdatedState(onClick)
    val motion = rememberCourtHeroMotion(opening)
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val unit = maxWidth.value / 515f
        val largeText = fontScale > 1.2f
        StudentCard(
            onClick = {
                if (!opening) {
                    opening = true
                    scope.launch {
                        try {
                            if (!reduceMotion) delay(VbMotion.Short.toLong())
                            currentClick()
                        } finally {
                            opening = false
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape((30f * unit).dp),
            color = Color.Transparent,
            border = BorderStroke(0.dp, Color.Transparent),
        ) {
            val cardSize = if (largeText) Modifier.fillMaxWidth() else
                Modifier.fillMaxWidth().height((402f * unit).dp)
            Box(cardSize) {
                CourtHeroArtwork(motion, Modifier.matchParentSize())
                if (largeText) {
                    Column(Modifier.fillMaxWidth().heightIn(min = (402f * unit).dp)
                        .padding(start = (28f * unit).dp, end = (28f * unit).dp,
                            top = (38f * unit).dp, bottom = (24f * unit).dp)) {
                        HeroBadge(unit, fixedSize = false)
                        Spacer(Modifier.height((24f * unit).dp))
                        HeroHeadline(unit, Modifier.fillMaxWidth())
                        Spacer(Modifier.height((80f * unit).dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HeroTrainingPrompt(unit, Modifier.weight(1f))
                            Spacer(Modifier.width((12f * unit).dp))
                            HeroArrow(unit, motion)
                        }
                    }
                } else {
                    HeroBadge(unit, Modifier.offset((28f * unit).dp, (38f * unit).dp))
                    HeroHeadline(unit, Modifier.offset((28f * unit).dp, (100f * unit).dp)
                        .width((459f * unit).dp))
                    HeroTrainingPrompt(unit, Modifier.align(Alignment.BottomStart)
                        .padding(start = (28f * unit).dp, bottom = (24f * unit).dp))
                    HeroArrow(unit, motion, Modifier.align(Alignment.BottomEnd)
                        .padding(end = (12f * unit).dp, bottom = (12f * unit).dp))
                }
            }
        }
    }
}

private val HeroLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

// Bundled type keeps the reference's glyph shapes and widths consistent across phone themes.
@OptIn(ExperimentalTextApi::class)
private val HeroFontFamily = FontFamily(
    Font(R.font.training_inter, FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.training_inter, FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

@Composable
private fun HeroBadge(unit: Float, modifier: Modifier = Modifier, fixedSize: Boolean = true) {
    val sized = if (fixedSize) modifier.width((212f * unit).dp).height((39f * unit).dp) else modifier
    Surface(modifier = sized, color = Color.White.copy(alpha = .15f), shape = CircleShape) {
        val inset = if (fixedSize) Modifier else Modifier.padding(horizontal = (16f * unit).dp,
            vertical = (10f * unit).dp)
        Box(inset, contentAlignment = Alignment.Center) {
            Text("LIVE FORM COACHING", color = Color.White,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = HeroFontFamily,
                    fontSize = (14.5f * unit).sp, lineHeight = (18f * unit).sp,
                    fontWeight = FontWeight.Bold, letterSpacing = (1.2f * unit).sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ))
        }
    }
}

@Composable
private fun HeroHeadline(unit: Float, modifier: Modifier) {
    Text("Build a stronger\ngame, one rep\nat a time.", modifier, color = Color.White,
        style = MaterialTheme.typography.headlineLarge.copy(
            fontFamily = HeroFontFamily,
            fontSize = (40f * unit).sp, lineHeight = (45f * unit).sp,
            fontWeight = FontWeight.Bold, letterSpacing = (-.6f * unit).sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = HeroLineHeight,
        ))
}

@Composable
private fun HeroTrainingPrompt(unit: Float, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy((3f * unit).dp)) {
        Text(stringResource(R.string.home_start_training), color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = HeroFontFamily,
                fontSize = (24f * unit).sp, lineHeight = (29f * unit).sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-.2f * unit).sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = HeroLineHeight,
            ))
        Text("Pick a skill. Get moving.", color = Color(0xFFFFDAC3),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = HeroFontFamily,
                fontSize = (20f * unit).sp, lineHeight = (26f * unit).sp, letterSpacing = 0.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = HeroLineHeight,
            ))
    }
}

@Composable
private fun HeroArrow(unit: Float, motion: CourtHeroMotion, modifier: Modifier = Modifier) {
    Box(modifier.size((76f * unit).dp).courtHeroArrowGlow(motion), contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = Color.White, modifier = Modifier.size((58f * unit).dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null,
                    modifier = Modifier.size((28f * unit).dp), tint = Color(0xFFF66B0C))
            }
        }
    }
}
