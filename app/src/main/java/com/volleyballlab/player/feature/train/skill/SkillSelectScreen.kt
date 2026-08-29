package com.volleyballlab.player.feature.train.skill

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.ui.component.EntranceAnimation
import com.volleyballlab.player.ui.component.pressScale
import java.util.Locale

/**
 * Entry point of the training flow: choose one of the five MVP skills.
 *
 * No ViewModel. The skill list is a compile-time constant with no state to hold
 * and nothing to load, so introducing one would add a lifecycle-aware object
 * whose only job is to return [Skill.All].
 *
 * ## Why this screen carries its own colours
 *
 * Every other screen draws entirely from the Material scheme. This one gives each
 * skill an accent of its own, because the five illustrations are each painted in
 * a single colour and a card whose tint fights its artwork looks like a mistake.
 * The colours live in [SkillPalette] beside the drawable they belong to rather
 * than in `Color.kt`, which is the app's design system: these are properties of
 * five pictures, not new roles in the palette, and promoting them would imply the
 * rest of the app may use them.
 */
@Composable
fun SkillSelectScreen(
    onSkillSelected: (Skill) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            EntranceAnimation(index = 0) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp)) {
                    Text(
                        text = stringResource(R.string.skill_select_title),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.skill_select_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        itemsIndexed(Skill.All, key = { _, skill -> skill.name }) { index, skill ->
            EntranceAnimation(
                index = index + 1,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                SkillCard(
                    skill = skill,
                    position = index + 1,
                    onClick = { onSkillSelected(skill) },
                )
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

/**
 * One skill, as a wide card with its illustration bled to the right edge.
 *
 * The number, the title rule and the arrow all take the skill's accent, and the
 * card is that accent at wash strength. The illustration is drawn first so the
 * text sits over it: the figures are painted light toward their trailing edge, so
 * the two occupy the same rectangle without either needing a scrim.
 */
@Composable
private fun SkillCard(skill: Skill, position: Int, onClick: () -> Unit) {
    val palette = paletteFor(skill)
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT_DP.dp)
            .pressScale(interactionSource),
        shape = RoundedCornerShape(CARD_CORNER_DP.dp),
        color = palette.wash,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(palette.illustration),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(end = ILLUSTRATION_INSET_DP.dp, top = 6.dp, bottom = 6.dp),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                NumberBadge(position = position, accent = palette.accent)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(skill.displayName),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    // The rule under the title, in the skill's own colour. It is
                    // what ties the number, the figure and the card together
                    // without needing a border around anything.
                    Box(
                        Modifier
                            .width(TITLE_RULE_WIDTH_DP.dp)
                            .height(TITLE_RULE_HEIGHT_DP.dp)
                            .clip(CircleShape)
                            .background(palette.accent)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(skill.description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(DESCRIPTION_WIDTH_DP.dp),
                    )
                }
            }

            ForwardButton(
                accent = palette.accent,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(14.dp),
            )
        }
    }
}

@Composable
private fun NumberBadge(position: Int, accent: Color) {
    Box(
        modifier = Modifier
            .size(BADGE_SIZE_DP.dp)
            .clip(RoundedCornerShape(BADGE_CORNER_DP.dp))
            .background(accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // Zero-padded, because "01" through "05" reads as a numbered series
            // and "1" through "5" reads as a count of things.
            text = String.format(Locale.US, "%02d", position),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = Color.White,
        )
    }
}

/**
 * The affordance in the corner.
 *
 * A white disc rather than a tinted one: on five different card washes a filled
 * accent button changes weight card to card, and the eye reads that as five
 * different controls rather than the same one repeated.
 */
@Composable
private fun ForwardButton(accent: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(BUTTON_SIZE_DP.dp)
            .clip(CircleShape)
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The presentation of one skill: its accent, its card wash and its figure.
 *
 * @param accent sampled from the illustration itself, so the number badge and the
 *   figure are the same colour rather than two colours that nearly match.
 * @param wash the same hue at roughly 8% over white - light enough that the ink
 *   and muted text keep their contrast against it without restating either.
 */
private data class SkillPalette(
    val accent: Color,
    val wash: Color,
    @DrawableRes val illustration: Int,
)

private fun paletteFor(skill: Skill): SkillPalette = when (skill) {
    Skill.Serving -> SkillPalette(
        accent = Color(0xFFF26A15),
        wash = Color(0xFFFDF2E8),
        illustration = R.drawable.skill_serving,
    )
    Skill.Receiving -> SkillPalette(
        accent = Color(0xFF2563EB),
        wash = Color(0xFFEBF1FE),
        illustration = R.drawable.skill_receiving,
    )
    Skill.Setting -> SkillPalette(
        accent = Color(0xFF0FA06B),
        wash = Color(0xFFE8F7F0),
        illustration = R.drawable.skill_setting,
    )
    Skill.Spiking -> SkillPalette(
        accent = Color(0xFF7C3AED),
        wash = Color(0xFFF1EBFE),
        illustration = R.drawable.skill_spiking,
    )
    Skill.Blocking -> SkillPalette(
        accent = Color(0xFFF5A524),
        wash = Color(0xFFFDF5E5),
        illustration = R.drawable.skill_blocking,
    )
}

private const val CARD_HEIGHT_DP = 148
private const val CARD_CORNER_DP = 20
private const val BADGE_SIZE_DP = 38
private const val BADGE_CORNER_DP = 11
private const val BUTTON_SIZE_DP = 42
private const val TITLE_RULE_WIDTH_DP = 30
private const val TITLE_RULE_HEIGHT_DP = 3

/** Keeps the description clear of the figure behind it at every text scale. */
private const val DESCRIPTION_WIDTH_DP = 150

/** Leaves the forward button its corner without the figure crowding it. */
private const val ILLUSTRATION_INSET_DP = 34
