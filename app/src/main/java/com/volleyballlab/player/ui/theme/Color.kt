package com.volleyballlab.player.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colour tokens for Volleyball Lab.
 *
 * ## Where these came from
 *
 * Three colours were sampled directly from the approved theme reference and are
 * treated as fixed brand anchors. Everything else in this file is a tonal ramp
 * built around them, so the palette is derivable rather than a pile of hex codes
 * someone eyeballed:
 *
 * | Role                  | Hex       | Token below                     |
 * |-----------------------|-----------|---------------------------------|
 * | Primary action        | `#CC5500` | [RustPrimary40] - burnt orange  |
 * | Page background       | `#F0F8FF` | [AliceBlue]                     |
 * | Dark shape / ink      | `#141C33` | [InkNavy]                       |
 *
 * ## Palette structure
 *
 * Four tonal families, each named by tone (0 = black, 100 = white) the same way
 * Material 3 numbers them, so mapping a token to its M3 role is mechanical:
 *
 * - **Rust** - burnt orange, `#CC5500`. Primary. Reserved for actions the
 *   athlete takes: the record button, the active nav item, the primary CTA. It
 *   is never used for data.
 * - **Court** - the desaturated navy that the ink and the card fills both sit
 *   on. Secondary, plus every neutral surface, which is why the greys in this
 *   app are faintly blue rather than pure grey and why they sit comfortably on
 *   an Alice Blue page.
 * - **Deep Teal** - tertiary. Carries analytics and insight accents so that
 *   charts never borrow the primary action colour.
 * - **Crimson** - error. Deliberately pushed toward magenta (hue ~5) instead of
 *   Material's default `#B3261E`, because that default sits only a few degrees
 *   of hue from our rust primary and the two are near-indistinguishable at
 *   button size.
 *
 * ## What is NOT here
 *
 * Live-video overlay colours (the skeleton, the per-joint cue tints) are in
 * [VbExtendedColors], not in the M3 scheme. They are drawn on top of a camera
 * feed rather than on a surface, so they are chosen for contrast against
 * arbitrary video, not for contrast against [ColorLightSurface]. Putting them in
 * the scheme would imply they participate in M3's contrast guarantees. They do not.
 */

// ---------------------------------------------------------------------------
// Burnt Orange - primary. Standard burnt orange #CC5500.
// ---------------------------------------------------------------------------
internal val RustPrimary10 = Color(0xFF331500)
internal val RustPrimary20 = Color(0xFF662A00)
internal val RustPrimary30 = Color(0xFF993F00)
internal val RustPrimary40 = Color(0xFFCC5500) // Burnt Orange
internal val RustPrimary80 = Color(0xFFFFB280)
internal val RustPrimary90 = Color(0xFFFFD9BF)

// ---------------------------------------------------------------------------
// Court - secondary and the blue-leaning neutral ramp.
// ---------------------------------------------------------------------------
internal val InkNavy = Color(0xFF141C33) // sampled from the reference
internal val Court20 = Color(0xFF26304C)
internal val Court30 = Color(0xFF3C4664)
internal val Court40 = Color(0xFF545D7D)
internal val Court80 = Color(0xFFBDC6EA)
internal val Court90 = Color(0xFFDCE2FF)

// ---------------------------------------------------------------------------
// Deep Teal - tertiary. Analytics, insight, "measured" information.
// ---------------------------------------------------------------------------
internal val Teal10 = Color(0xFF002022)
internal val Teal30 = Color(0xFF004F53)
internal val Teal40 = Color(0xFF00696E)
internal val Teal80 = Color(0xFF80D4DA)
internal val Teal90 = Color(0xFF9CF0F6)

// ---------------------------------------------------------------------------
// Crimson - error.
// ---------------------------------------------------------------------------
internal val Crimson10 = Color(0xFF410008)
internal val Crimson20 = Color(0xFF680012)
internal val Crimson30 = Color(0xFF91021F)
internal val Crimson40 = Color(0xFFB3132B)
internal val Crimson80 = Color(0xFFFFB3B0)
internal val Crimson90 = Color(0xFFFFDAD9)

// ---------------------------------------------------------------------------
// Alice Blue neutrals.
//
// [AliceBlue] is the page itself - background and surface - and every container
// step below it is progressively more tinted, the same direction Material 3's
// own light ramp runs. That ordering is what makes a card visible: a panel at
// surfaceContainer sits a measurable step darker than the page it rests on,
// with only surfaceContainerLowest going the other way to pure white for the
// cards that must read as lifted clear of it.
//
// Getting this backwards is the classic tinted-background mistake - cards that
// are lighter than the page look like holes punched in it rather than objects
// on top of it.
// ---------------------------------------------------------------------------
internal val AliceBlue = Color(0xFFF0F8FF) // Alice Blue #F0F8FF - background
internal val AliceBlueBright = Color(0xFFF8FCFF)
internal val AliceBlueContainerLowest = Color(0xFFFFFFFF)
internal val AliceBlueContainerLow = Color(0xFFEAF3FC)
internal val AliceBlueContainer = Color(0xFFE3EEF9)
internal val AliceBlueContainerHigh = Color(0xFFDAE8F6)
internal val AliceBlueContainerHighest = Color(0xFFCFDFF1)
internal val AliceBlueDim = Color(0xFFD5E2F0)
internal val AliceBlueOutline = Color(0xFF6F7A91)
internal val AliceBlueOutlineVariant = Color(0xFFC4D5E7)
internal val AliceBlueOnSurfaceVariant = Color(0xFF44506A)

internal val NightSurface = Color(0xFF101219)
internal val NightContainerLowest = Color(0xFF0B0D14)
internal val NightContainerLow = Color(0xFF191B23)
internal val NightContainer = Color(0xFF1D1F28)
internal val NightContainerHigh = Color(0xFF272932)
internal val NightContainerHighest = Color(0xFF32343D)
internal val NightBright = Color(0xFF363842)
internal val NightOnSurface = Color(0xFFE3E6F2)
internal val NightOutline = Color(0xFF8F94AB)

/**
 * The pre-Compose splash window colour.
 *
 * Must stay byte-identical to `@color/splash_background` in `res/values/colors.xml`.
 * The system splash window is drawn by the platform before any Compose code
 * runs; if these two drift apart the user sees a one-frame flash at the handoff.
 * This closes the open decision left blank in section 7 of
 * `docs/rive/splash-contract.md`.
 */
val VbSplashBackground = AliceBlue // Alice Blue #F0F8FF
