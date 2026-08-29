package com.volleyballlab.player.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * The single point at which this app's typeface is decided.
 *
 * ## Current state
 *
 * The approved reference specifies **Hanken Grotesk**. It is not bundled yet, so
 * this resolves to the platform sans (Roboto on most devices). Everything else
 * in this file, and every `MaterialTheme.typography` call site in the app, reads
 * from here, so switching is a one-line change rather than a find-and-replace.
 *
 * ## Swapping Hanken Grotesk in
 *
 * Google Fonts ships it as a variable font only, with no static instances:
 *
 * 1. Download `HankenGrotesk[wght].ttf` (about 130 KB) from
 *    `github.com/google/fonts/ofl/hankengrotesk`, plus `OFL.txt`.
 * 2. Save the font as `app/src/main/res/font/hanken_grotesk.ttf`. The rename is
 *    not optional: `res/font/` rejects uppercase letters and square brackets, so
 *    the upstream filename will fail the build. Keep `OFL.txt` in the project
 *    for licence compliance, since the app redistributes the font.
 * 3. Replace the [VbFontFamily] body with a [FontFamily] whose entries all point
 *    at that one file and differ by `FontVariation.Settings(FontVariation.weight(n))`,
 *    with a matching `FontWeight` on each entry. One entry per weight actually
 *    used below: 400, 500, 600 and 700.
 *
 * Variable fonts need API 26, which is this app's `minSdk`, so no fallback path
 * is required.
 */
val VbFontFamily: FontFamily = FontFamily.SansSerif

/**
 * Distributes a line's leading evenly above and below the glyphs.
 *
 * By default the platform hangs text off the ascent, so a line box with generous
 * line height sits visually high inside its bounds. Centring it means a label
 * vertically centred in a button or a chip actually looks centred.
 *
 * Trim stays at [LineHeightStyle.Trim.None] on purpose. Trimming the outer
 * leading would let consecutive paragraphs collide; vertical rhythm between
 * blocks is the layout's job, not the type scale's.
 */
private val TightLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

/**
 * The type scale.
 *
 * Two deliberate departures from the stock Material 3 scale:
 *
 * - **Tighter tracking on display and headline sizes.** Material's defaults are
 *   tuned for Roboto. Geometric grotesques like Hanken Grotesk already set
 *   fairly tight, and at 32 sp and up the stock positive tracking makes headings
 *   look loose and unresolved.
 * - **Heavier weights on headlines** (600 rather than 400). The app is read at
 *   arm's length in a gym, often in motion, sometimes in bad light. Thin large
 *   text is the first thing to fail in those conditions.
 */
val VbTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = VbFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-1.0).sp,
        lineHeightStyle = TightLineHeight,
    ),
    displayMedium = TextStyle(
        fontFamily = VbFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.5).sp,
        lineHeightStyle = TightLineHeight,
    ),
    displaySmall = TextStyle(
        fontFamily = VbFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.25).sp,
        lineHeightStyle = TightLineHeight,
    ),
    headlineLarge = TextStyle(
        fontFamily = VbFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.25).sp,
        lineHeightStyle = TightLineHeight,
    ),
    headlineMedium = TextStyle(
        fontFamily = VbFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = TightLineHeight,
    ),
    headlineSmall = TextStyle(
        fontFamily = VbFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = TightLineHeight,
    ),
    titleLarge = TextStyle(
        fontFamily = VbFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = TightLineHeight,
    ),
    titleMedium = TextStyle(
        fontFamily = VbFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
        lineHeightStyle = TightLineHeight,
    ),
    titleSmall = TextStyle(
        fontFamily = VbFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        lineHeightStyle = TightLineHeight,
    ),
    bodyLarge = TextStyle(
        fontFamily = VbFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
        lineHeightStyle = TightLineHeight,
    ),
    bodyMedium = TextStyle(
        fontFamily = VbFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
        lineHeightStyle = TightLineHeight,
    ),
    bodySmall = TextStyle(
        fontFamily = VbFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
        lineHeightStyle = TightLineHeight,
    ),
    labelLarge = TextStyle(
        fontFamily = VbFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        lineHeightStyle = TightLineHeight,
    ),
    labelMedium = TextStyle(
        fontFamily = VbFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
        lineHeightStyle = TightLineHeight,
    ),
    labelSmall = TextStyle(
        fontFamily = VbFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
        lineHeightStyle = TightLineHeight,
    ),
)

/**
 * Numerals that will not jitter while they animate.
 *
 * The rep counter and the elapsed timer change several times a second. With
 * proportional figures a 1 is narrower than a 7, so the whole number shifts
 * sideways on every tick. Tabular figures give every digit the same advance
 * width, which holds the layout still.
 *
 * This uses a font feature setting rather than a monospace family so the digits
 * still match the app's typeface. If a future typeface lacks the `tnum` feature
 * this silently does nothing, which is the correct failure mode.
 */
val VbTabularNumerals = TextStyle(fontFeatureSettings = "tnum")
