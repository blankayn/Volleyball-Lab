package com.volleyballlab.player.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner scale, nudged one step rounder than the Material 3 default
 * (4/8/12/16/28) to match the softness of the panels in the theme reference.
 *
 * Assignment is by role, not by taste. Pick the token from what the surface is,
 * and the app stays coherent as screens get added:
 *
 * - extraSmall  chips, badges, inline tags
 * - small       dense list rows, text fields
 * - medium      the default for content cards
 * - large       hero cards, the capture framing panel
 * - extraLarge  bottom sheets, full-bleed dialogs
 *
 * Buttons are deliberately not in this scale. Material 3 gives them a fully
 * rounded stadium shape by default, and the Primary button in the reference is
 * squarer than that, so [VbButtonShape] overrides it at the component level.
 */
val VbShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Matches the modest corner radius of the Primary button in the reference. */
val VbButtonShape = RoundedCornerShape(10.dp)
