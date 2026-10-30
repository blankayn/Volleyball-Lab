package com.volleyballlab.player.feature.train.skill

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.ui.component.EntranceAnimation
import com.volleyballlab.player.ui.component.StudentCard
import com.volleyballlab.player.ui.component.StudentPageHeading
import com.volleyballlab.player.ui.component.CourtCardArtwork
import com.volleyballlab.player.ui.component.studentBackground
import com.volleyballlab.player.ui.component.pressScale
import java.util.Locale

/**
 * Entry point of the training flow: choose one of the five MVP skills.
 *
 * The skills themselves are a compile-time constant. The ViewModel adds only the
 * stored form progress for each one, so the card can show whether the athlete has
 * trained it without the screen doing aggregation during composition.
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
    onOpenDrillLibrary: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val viewModel: SkillSelectViewModel = viewModel(factory = SkillSelectViewModel.Factory)
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = modifier.fillMaxSize().studentBackground(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            EntranceAnimation(index = 0) {
                StudentPageHeading("SKILL DEVELOPMENT", stringResource(R.string.skill_select_title),
                    stringResource(R.string.skill_select_subtitle),
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp))
            }
        }

        item {
            StudentCard(onClick = onOpenDrillLibrary, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.MenuBook, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Drill library", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Step-by-step guides and practice", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
        }
        itemsIndexed(Skill.All, key = { _, skill -> skill.name }) { index, skill ->
            EntranceAnimation(
                index = index + 1,
                modifier = Modifier.padding(horizontal = 20.dp),
            ) {
                SkillCard(
                    skill = skill,
                    position = index + 1,
                    progress = progress.bySkill[skill],
                    progressLoaded = progress.loaded,
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
internal fun SkillCard(
    skill: Skill, position: Int, progress: SkillFormProgress?, progressLoaded: Boolean, onClick: () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 270.dp || fontScale > 1.15f) {
            CompactSkillCard(skill, position, progress, progressLoaded, onClick)
        } else {
            IllustratedSkillCard(skill, position, progress, progressLoaded, onClick, artworkAlpha = if (maxWidth < 380.dp) .18f else 1f)
        }
    }
}

/** Text and artwork get separate space when a fixed-height illustration would crowd the labels. */
@Composable
private fun CompactSkillCard(skill: Skill, position: Int, progress: SkillFormProgress?, progressLoaded: Boolean, onClick: () -> Unit) {
    val palette = paletteFor(skill)
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(CARD_CORNER_DP.dp),
        color = palette.wash, contentColor = SkillCardInk,
        border = BorderStroke(1.dp, palette.accent.copy(alpha = .18f)), shadowElevation = 2.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberBadge(position, palette.accent)
                Text(stringResource(skill.displayName), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, color = SkillCardInk, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(skill.description), style = MaterialTheme.typography.bodySmall,
                    color = SkillCardMutedInk, modifier = Modifier.weight(1f))
                Image(painterResource(palette.illustration), null, Modifier.size(72.dp), contentScale = ContentScale.Fit)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (progressLoaded) SkillProgressLine(progress, true, palette.accent, Modifier.weight(1f), maxLines = Int.MAX_VALUE)
                else Spacer(Modifier.weight(1f))
                ForwardButton(palette.accent)
            }
        }
    }
}

@Composable
private fun IllustratedSkillCard(
    skill: Skill,
    position: Int,
    progress: SkillFormProgress?,
    progressLoaded: Boolean,
    onClick: () -> Unit,
    artworkAlpha: Float,
) {
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
        contentColor = SkillCardInk,
        border = BorderStroke(1.dp, palette.accent.copy(alpha = .18f)),
        shadowElevation = 2.dp,
    ) {
        Box(Modifier.fillMaxWidth()) {
            CourtCardArtwork(Modifier.matchParentSize().testTag("skill-card-background"), ink = palette.accent)
            Image(
                painter = painterResource(palette.illustration),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alpha = artworkAlpha,
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
                        color = SkillCardInk,
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
                        color = SkillCardMutedInk,
                        modifier = Modifier.width(DESCRIPTION_WIDTH_DP.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    SkillProgressLine(progress, progressLoaded, palette.accent)
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
private fun SkillProgressLine(
    progress: SkillFormProgress?,
    loaded: Boolean,
    accent: Color,
    modifier: Modifier = Modifier.width(DESCRIPTION_WIDTH_DP.dp),
    maxLines: Int = 2,
) {
    if (!loaded) return
    val text = when {
        progress == null -> stringResource(R.string.skill_progress_none)
        progress.meanScore == null -> if (progress.sessionCount == 1) {
            stringResource(R.string.skill_progress_one_no_score)
        } else {
            stringResource(R.string.skill_progress_many_no_score, progress.sessionCount)
        }
        progress.sessionCount == 1 -> stringResource(R.string.skill_progress_one, progress.meanScore)
        else -> stringResource(R.string.skill_progress_many, progress.sessionCount, progress.meanScore)
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(
            if (progress != null) accent else MaterialTheme.colorScheme.outline
        ))
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = SkillCardMutedInk,
            modifier = Modifier.weight(1f),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
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

private const val CARD_HEIGHT_DP = 164
private val SkillCardInk = Color(0xFF22302A)
private val SkillCardMutedInk = Color(0xFF47534B)
private const val CARD_CORNER_DP = 24
private const val BADGE_SIZE_DP = 38
private const val BADGE_CORNER_DP = 11
private const val BUTTON_SIZE_DP = 42
private const val TITLE_RULE_WIDTH_DP = 30
private const val TITLE_RULE_HEIGHT_DP = 3

/** Keeps the description clear of the figure behind it at every text scale. */
private const val DESCRIPTION_WIDTH_DP = 150

/** Leaves the forward button its corner without the figure crowding it. */
private const val ILLUSTRATION_INSET_DP = 34
