package com.volleyballlab.player.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.Skill

/**
 * How each skill is coloured and illustrated.
 *
 * ## Why these live outside `Color.kt`
 *
 * `Color.kt` is the app's design system: a small set of roles the whole app draws
 * from. These five are not roles - they are properties of five pictures, sampled
 * from the artwork so a card's tint and its figure are the same colour rather
 * than two colours that nearly match. Promoting them into the scheme would say
 * that any screen may reach for a green, which is exactly what a design system
 * exists to prevent.
 *
 * @param accent the figure's own colour, at full strength.
 * @param wash the same hue at roughly 8% over white, for card backgrounds.
 */
data class SkillVisual(
    val accent: Color,
    val wash: Color,
    @DrawableRes val illustration: Int,
)

fun skillVisual(skill: Skill): SkillVisual = when (skill) {
    Skill.Serving -> SkillVisual(
        accent = Color(0xFFF26A15),
        wash = Color(0xFFFDF2E8),
        illustration = R.drawable.skill_serving,
    )
    Skill.Receiving -> SkillVisual(
        accent = Color(0xFF2563EB),
        wash = Color(0xFFEBF1FE),
        illustration = R.drawable.skill_receiving,
    )
    Skill.Setting -> SkillVisual(
        accent = Color(0xFF0FA06B),
        wash = Color(0xFFE8F7F0),
        illustration = R.drawable.skill_setting,
    )
    Skill.Spiking -> SkillVisual(
        accent = Color(0xFF7C3AED),
        wash = Color(0xFFF1EBFE),
        illustration = R.drawable.skill_spiking,
    )
    Skill.Blocking -> SkillVisual(
        accent = Color(0xFFF5A524),
        wash = Color(0xFFFDF5E5),
        illustration = R.drawable.skill_blocking,
    )
}

/**
 * A short vertical rule in a skill's colour, for list rows.
 *
 * This is what replaced the drawn skill pictograms. At the 24 dp those icons were
 * shown at, five figures differing only in limb position were not tellable apart
 * anyway - the colour does the identifying job the drawing was failing at, in a
 * fraction of the space and with nothing to misread.
 *
 * It deliberately matches [VbSectionHeader]'s rule, so a coloured bar means the
 * same thing everywhere in the app: the thing to its right is what this is about.
 */
@Composable
fun SkillAccentRule(
    skill: Skill,
    modifier: Modifier = Modifier,
    height: Int = 40,
) {
    Box(
        modifier = modifier
            .width(4.dp)
            .height(height.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(skillVisual(skill).accent)
    )
}
