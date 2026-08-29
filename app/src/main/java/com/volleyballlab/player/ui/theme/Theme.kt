package com.volleyballlab.player.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours that live outside the Material 3 scheme.
 *
 * Two groups, both of which would be wrong to force into [ColorScheme]:
 *
 * **Semantic status colours.** Material 3 ships exactly one status role, `error`.
 * A form-detection app needs at least three, because "this rep was good", "this
 * rep drifted" and "this rep was wrong" are the app's core output. Overloading
 * `error` for a merely mediocre rep would be both semantically wrong and
 * visually alarming.
 *
 * **Overlay colours.** The skeleton and the per-joint cue tints are drawn on top
 * of a live camera feed, not on a surface. They are picked for legibility against
 * arbitrary video - a gym floor, a bright window, a dark hall - which is why they
 * are more saturated than anything else in the palette. Material's contrast
 * guarantees are defined against scheme surfaces and simply do not apply to them,
 * so putting them in the scheme would be a false promise.
 */
@Immutable
data class VbExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    /** Bone segments of the live skeleton overlay. */
    val poseBone: Color,
    /** Joint dots of the live skeleton overlay. */
    val poseJoint: Color,
    /** A joint inside its target range for the current cue. */
    val cueGood: Color,
    /** A joint drifting toward the edge of its target range. */
    val cueWarn: Color,
    /** A joint outside its target range. */
    val cueBad: Color,
    /** Scrim behind camera controls, so they stay readable over any footage. */
    val cameraScrim: Color,
)

private val LightExtendedColors = VbExtendedColors(
    success = Color(0xFF1B6B3F),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFA4F4C2),
    onSuccessContainer = Color(0xFF00210F),
    warning = Color(0xFF7A5900),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDF9A),
    onWarningContainer = Color(0xFF261900),
    poseBone = Color(0xFFF2F5FF),
    poseJoint = Color(0xFFFFB59B),
    cueGood = Color(0xFF3DDC84),
    cueWarn = Color(0xFFFFC24B),
    cueBad = Color(0xFFFF6B6B),
    cameraScrim = Color(0x99000000),
)

/**
 * The overlay colours are identical in both themes on purpose. They sit on the
 * camera feed, whose brightness has nothing to do with whether the user picked
 * dark mode, so dimming them at night would only make tracking harder to read.
 */
private val DarkExtendedColors = VbExtendedColors(
    success = Color(0xFF88D8A6),
    onSuccess = Color(0xFF003920),
    successContainer = Color(0xFF00522F),
    onSuccessContainer = Color(0xFFA4F4C2),
    warning = Color(0xFFF0C24B),
    onWarning = Color(0xFF402D00),
    warningContainer = Color(0xFF5C4200),
    onWarningContainer = Color(0xFFFFDF9A),
    poseBone = Color(0xFFF2F5FF),
    poseJoint = Color(0xFFFFB59B),
    cueGood = Color(0xFF3DDC84),
    cueWarn = Color(0xFFFFC24B),
    cueBad = Color(0xFFFF6B6B),
    cameraScrim = Color(0x99000000),
)

private val VbLightColorScheme = lightColorScheme(
    primary = RustPrimary40,
    onPrimary = Color.White,
    primaryContainer = RustPrimary90,
    onPrimaryContainer = RustPrimary10,
    inversePrimary = RustPrimary80,

    secondary = Court40,
    onSecondary = Color.White,
    secondaryContainer = Court90,
    onSecondaryContainer = InkNavy,

    tertiary = Teal40,
    onTertiary = Color.White,
    tertiaryContainer = Teal90,
    onTertiaryContainer = Teal10,

    error = Crimson40,
    onError = Color.White,
    errorContainer = Crimson90,
    onErrorContainer = Crimson10,

    background = AliceBlue,
    onBackground = InkNavy,
    surface = AliceBlue,
    onSurface = InkNavy,
    surfaceVariant = AliceBlueContainerHighest,
    onSurfaceVariant = AliceBlueOnSurfaceVariant,
    surfaceTint = RustPrimary40,
    inverseSurface = Color(0xFF2A2E3B),
    inverseOnSurface = Color(0xFFF1F0FA),

    surfaceBright = AliceBlueBright,
    surfaceDim = AliceBlueDim,
    surfaceContainerLowest = AliceBlueContainerLowest,
    surfaceContainerLow = AliceBlueContainerLow,
    surfaceContainer = AliceBlueContainer,
    surfaceContainerHigh = AliceBlueContainerHigh,
    surfaceContainerHighest = AliceBlueContainerHighest,

    outline = AliceBlueOutline,
    outlineVariant = AliceBlueOutlineVariant,
    scrim = Color.Black,
)

private val VbDarkColorScheme = darkColorScheme(
    primary = RustPrimary80,
    onPrimary = RustPrimary20,
    primaryContainer = RustPrimary30,
    onPrimaryContainer = RustPrimary90,
    inversePrimary = RustPrimary40,

    secondary = Court80,
    onSecondary = Court20,
    secondaryContainer = Court30,
    onSecondaryContainer = Court90,

    tertiary = Teal80,
    onTertiary = Color(0xFF003739),
    tertiaryContainer = Teal30,
    onTertiaryContainer = Teal90,

    error = Crimson80,
    onError = Crimson20,
    errorContainer = Crimson30,
    onErrorContainer = Crimson90,

    background = NightSurface,
    onBackground = NightOnSurface,
    surface = NightSurface,
    onSurface = NightOnSurface,
    surfaceVariant = AliceBlueOnSurfaceVariant,
    onSurfaceVariant = AliceBlueOutlineVariant,
    surfaceTint = RustPrimary80,
    inverseSurface = NightOnSurface,
    inverseOnSurface = Color(0xFF2D3038),

    surfaceBright = NightBright,
    surfaceDim = NightSurface,
    surfaceContainerLowest = NightContainerLowest,
    surfaceContainerLow = NightContainerLow,
    surfaceContainer = NightContainer,
    surfaceContainerHigh = NightContainerHigh,
    surfaceContainerHighest = NightContainerHighest,

    outline = NightOutline,
    outlineVariant = AliceBlueOnSurfaceVariant,
    scrim = Color.Black,
)

internal val LocalVbExtendedColors = staticCompositionLocalOf {
    // Never actually read: VolleyballLabTheme always provides a real value, and
    // a static local has no default that would make sense here. Light is used as
    // the placeholder so a stray read outside the theme renders rather than crashes.
    LightExtendedColors
}

/**
 * Root theme for Volleyball Lab.
 *
 * ## Dynamic colour is deliberately absent
 *
 * The stock Android Studio Compose template turns Material You dynamic colour on
 * by default, which reskins the whole app from the user's wallpaper. That is the
 * wrong default here: the palette in this app is a brand specification approved
 * for the capstone, and the rust primary in particular carries meaning - it marks
 * the record control and the active training state. Letting the wallpaper repaint
 * it would break both the brand and the affordance. There is no `dynamicColor`
 * parameter because there is no situation in which we would pass `true`.
 *
 * @param darkTheme off by default, and `MainActivity` pins it off, so the Alice
 *   Blue light scheme is what ships. The palette is authored
 *   for both, and the pose overlay colours are intentionally identical across them.
 */
@Composable
fun VolleyballLabTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) VbDarkColorScheme else VbLightColorScheme
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(
        LocalVbExtendedColors provides extendedColors,
        LocalReduceMotion provides rememberSystemReduceMotion(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = VbTypography,
            shapes = VbShapes,
            content = content,
        )
    }
}

/**
 * Access point for the colours that sit outside the Material scheme.
 *
 * Reads as `MaterialTheme.vbColors.cueGood` at the call site, which keeps it
 * visually parallel to `MaterialTheme.colorScheme.primary` and makes it obvious
 * that both come from the theme rather than from a hardcoded constant.
 */
val MaterialTheme.vbColors: VbExtendedColors
    @Composable
    @ReadOnlyComposable
    get() = LocalVbExtendedColors.current
