package com.volleyballlab.player.feature.coachmode

import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volleyballlab.player.R
import com.volleyballlab.player.ui.component.pressScale
import com.volleyballlab.player.ui.theme.VbTabularNumerals
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * The coach shell's design language, in one file.
 *
 * ## What this is
 *
 * The coach half of the app is a stack of dashboards, and dashboards go wrong in
 * exactly one way: six screens that each invent their own card, their own stat
 * tile and their own empty state, all almost matching. Every surface a coach
 * screen draws comes from here, so the drift is visible in one place.
 *
 * ## The visual system
 *
 * White cards floating on the pale Alice Blue page, generous corner radii, and
 * colour used sparingly as *categorisation* rather than decoration:
 *
 * - **Cards are `surfaceContainerLowest`**, which is pure white in the light
 *   scheme, with a soft shadow rather than a border. That is the one substantive
 *   departure from the rest of the app, where panels sit at `surfaceContainerHigh`
 *   and read as recessed. A coach page is a long scroll of unrelated blocks, and
 *   lifting them off the page is what keeps that legible; a training screen is one
 *   thing at a time and does not need it.
 * - **Icon chips carry a [CoachAccent]** - a pale tint behind a saturated glyph.
 *   Four tones, assigned per *kind of thing* and then kept fixed, so a coach learns
 *   that violet means paperwork and green means sessions without being told.
 * - **The rust primary stays an affordance.** It marks the thing to tap and the
 *   thing that needs attention, exactly as `Color.kt` insists, and it is never
 *   spent on a decorative fill. That is why the accent tones exist at all.
 *
 * ## Nothing here is promoted into `ui/component`
 *
 * These are specific in their *use*, not their rendering, and a player screen
 * reaching for a "coach stat strip" would be a smell rather than a reuse win.
 */

// ---------------------------------------------------------------------------
// Tokens
// ---------------------------------------------------------------------------

/** Corner radius for the large cards that make up a coach page. */
val CoachCardShape = RoundedCornerShape(24.dp)

/** Corner radius for things that sit *inside* a card - tiles, chips, wells. */
val CoachInnerShape = RoundedCornerShape(16.dp)

/** Corner radius for the small tinted square behind an icon. */
val CoachChipShape = RoundedCornerShape(14.dp)

/** The pill used for primary actions and for the small "view all" chips. */
val CoachPillShape = RoundedCornerShape(50)

/**
 * The lift that separates a card from the page.
 *
 * Deliberately small. At 2dp the shadow reads as a soft edge rather than as a
 * drop shadow, which is what keeps a page of eight cards from looking cluttered.
 */
val CoachCardElevation = 2.dp

/** Standard page gutter. Every coach screen indents its content by this. */
val CoachGutter = 20.dp

/**
 * The four categorical tones.
 *
 * Assigned by *what a thing is*, never by how important it is - importance is the
 * primary colour's job. Once a tone is attached to a concept it does not move:
 * players are always rust, review always indigo, sessions always green, written
 * material always violet.
 */
enum class CoachAccent {
    /** People and rosters. */
    Rust,

    /** Review, queues, anything waiting on the coach. */
    Indigo,

    /** Training that happened - sessions, sets, activity. */
    Green,

    /** Written material - assignments, notes, forms. */
    Violet,
}

/** A tone's pale fill and its saturated glyph colour. */
@Immutable
data class CoachAccentColors(val tint: Color, val ink: Color)

/**
 * Whether this scheme is a dark one, judged by the page it paints.
 *
 * Luminance of `surface` rather than a flag, because a `ColorScheme` does not
 * carry one - and because the answer that matters is about the colours actually
 * in force, not about whatever decided them.
 */
private fun ColorScheme.isDark(): Boolean = surface.luminance() < 0.5f

/**
 * Resolves a tone for the current scheme.
 *
 * Two hand-tuned pairs per tone rather than an algorithmic derivation. A tint
 * computed by dropping alpha over the page colour goes muddy in dark mode, and
 * these are small enough to be worth choosing by eye.
 *
 * ## Why this reads the surface and not `isSystemInDarkTheme()`
 *
 * Because they are not the same question, and getting it wrong is visible.
 * `MainActivity` pins `VolleyballLabTheme(darkTheme = false)`, so this app renders
 * its light scheme on *every* device. Asking the system whether the phone is in
 * dark mode therefore answers a question nobody asked: on a dark-mode handset it
 * returned true, and every icon chip, avatar and action tile painted its dark fill
 * onto white cards sitting on a pale blue page.
 *
 * Deriving it from the applied `surface` colour asks the only question that
 * matters - is the scheme I am drawing into light or dark - and it keeps working
 * on its own if that pin is ever removed.
 */
@Composable
@ReadOnlyComposable
fun CoachAccent.colors(): CoachAccentColors = if (MaterialTheme.colorScheme.isDark()) {
    when (this) {
        CoachAccent.Rust -> CoachAccentColors(Color(0xFF3A2517), Color(0xFFFFB280))
        CoachAccent.Indigo -> CoachAccentColors(Color(0xFF1D2438), Color(0xFF9DB4FF))
        CoachAccent.Green -> CoachAccentColors(Color(0xFF16301F), Color(0xFF6FD79B))
        CoachAccent.Violet -> CoachAccentColors(Color(0xFF251E38), Color(0xFFBFA3F5))
    }
} else {
    when (this) {
        CoachAccent.Rust -> CoachAccentColors(Color(0xFFFDEBDD), Color(0xFFCC5500))
        CoachAccent.Indigo -> CoachAccentColors(Color(0xFFE8EEFD), Color(0xFF3B5BDB))
        CoachAccent.Green -> CoachAccentColors(Color(0xFFE3F5EA), Color(0xFF12855A))
        CoachAccent.Violet -> CoachAccentColors(Color(0xFFF0EAFB), Color(0xFF7048C4))
    }
}

// ---------------------------------------------------------------------------
// Surfaces
// ---------------------------------------------------------------------------

/**
 * Makes a surface tappable, with the app's press-scale feedback.
 *
 * One helper rather than the same four lines on every card, because
 * `Modifier.pressScale` needs the *same* [MutableInteractionSource] the click is
 * registered against - wiring two separate ones is a silent bug that leaves the
 * ripple working and the scale dead.
 *
 * A null [onClick] returns the modifier untouched, so a read-only card is inert
 * rather than a control that highlights under a finger and does nothing.
 */
@Composable
private fun Modifier.tappable(onClick: (() -> Unit)?, enabled: Boolean = true): Modifier {
    if (onClick == null) return this
    val interaction = remember { MutableInteractionSource() }
    return this
        .pressScale(interaction)
        .clickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            enabled = enabled,
            onClick = onClick,
        )
}

/**
 * The white card everything on a coach page sits in.
 *
 * @param onClick makes the whole card a target. Null leaves it inert, which is
 *   the common case - most coach cards are readouts, and a card that highlights
 *   under a finger but does nothing is a worse lie than one that never reacts.
 */
@Composable
fun CoachCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .tappable(onClick),
        shape = CoachCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = CoachCardElevation,
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

/**
 * A card whose content is one horizontal row - a list entry, essentially.
 *
 * Separate from [CoachCard] only so the dozen list rows in this shell do not each
 * have to remember the same vertical padding and the same alignment.
 */
@Composable
fun CoachRowCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .tappable(onClick),
        shape = CoachCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = CoachCardElevation,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------
// Hero
// ---------------------------------------------------------------------------

/**
 * The two-tone greeting at the top of a coach tab.
 *
 * [lead] in ink and [accent] in the primary colour, on consecutive lines at
 * display size. The split is what gives the page its one moment of scale: below
 * this everything is card-sized, so the header has to do the work of announcing
 * which screen you are on without a title bar.
 *
 * ## No illustration
 *
 * The reference design this shell is styled after puts a character mascot beside
 * the greeting. There is none here, by decision rather than omission: this project
 * has no mascot art, and a stand-in - the app logo in a tinted disc was the
 * obvious one - reads as a placeholder someone forgot to replace. Type alone at
 * this size carries the header perfectly well, and the full width makes the two
 * lines breathe rather than wrap.
 */
@Composable
fun CoachHero(
    lead: String,
    accent: String,
    body: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = CoachGutter, end = CoachGutter, top = 16.dp, bottom = 8.dp),
    ) {
        Text(
            text = lead,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 38.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = accent,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 38.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        if (!body.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The lighter page header, for the tabs that are not the dashboard.
 *
 * Same weight and size as [CoachHero]'s first line, but one tone and no art. The
 * two-tone hero is a *welcome*, and a welcome belongs on the screen you land on;
 * repeating it on every tab would make each one feel like a separate app.
 */
@Composable
fun CoachPageHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = CoachGutter, end = CoachGutter, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * The header for a detail screen: back arrow, title, optional subtitle and slot.
 *
 * Detail screens keep a back affordance where the tabs keep a hero, because they
 * are pushed rather than switched to and the difference has to be visible before
 * the user reaches for the system gesture.
 */
@Composable
fun CoachDetailHeader(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = CoachGutter, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.cd_back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}
// ---------------------------------------------------------------------------
// Stats
// ---------------------------------------------------------------------------

/**
 * One figure in a [CoachStatStrip].
 *
 * @param value a string rather than an Int so a tile can print an em dash when
 *   there is nothing to report. Every screen in this app treats "not measured"
 *   and "zero" as different claims, and an Int-typed tile would quietly collapse
 *   them into the same one.
 * @param actionLabel the small tappable line under the label. Null for a figure
 *   that is only a readout.
 */
@Immutable
data class CoachStat(
    val value: String,
    val label: String,
    val icon: ImageVector,
    val accent: CoachAccent,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

/**
 * The headline numbers, as one card of evenly-weighted columns.
 *
 * One card rather than a grid of separate tiles, because these are four readings
 * of a single subject - the team - and separating them into four cards would
 * imply four unrelated things. Columns share the width evenly so the row stays
 * balanced whether it holds two figures or four.
 */
@Composable
fun CoachStatStrip(
    stats: List<CoachStat>,
    modifier: Modifier = Modifier,
) {
    if (stats.isEmpty()) return
    CoachCard(modifier = modifier, padding = 16.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            stats.forEach { stat ->
                StatColumn(stat, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatColumn(stat: CoachStat, modifier: Modifier = Modifier) {
    val accent = stat.accent.colors()
    Column(modifier) {
        CoachIconChip(icon = stat.icon, accent = stat.accent)
        Spacer(Modifier.height(10.dp))
        Text(
            text = stat.value,
            style = MaterialTheme.typography.headlineSmall.merge(VbTabularNumerals),
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stat.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (stat.actionLabel != null && stat.onAction != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.clickable(onClick = stat.onAction),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stat.actionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accent.ink,
                )
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = accent.ink,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/** A glyph on its tone's pale square. The recurring motif of the whole shell. */
@Composable
fun CoachIconChip(
    icon: ImageVector,
    accent: CoachAccent,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val colors = accent.colors()
    Box(
        modifier = modifier
            .size(size)
            .clip(CoachChipShape)
            .background(colors.tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.ink,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

// ---------------------------------------------------------------------------
// Feature card
// ---------------------------------------------------------------------------

/**
 * The one dark card on a page: a single prompt the coach is being asked to act on.
 *
 * Inverted against everything around it because that is the entire mechanism -
 * on a page of white cards, one navy card is unmissable without needing a colour,
 * a badge or a bigger typeface. Which means the rule is *one per screen*. A second
 * one costs the first all of its emphasis.
 */
@Composable
fun CoachFeatureCard(
    eyebrow: String,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    art: (@Composable BoxScope.() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CoachCardShape,
        color = CoachFeatureSurface,
        shadowElevation = CoachCardElevation,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (art != null) {
                Box(
                    modifier = Modifier.size(76.dp),
                    contentAlignment = Alignment.Center,
                    content = art,
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = eyebrow.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = CoachFeatureEyebrow,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = CoachFeatureBody,
                )
                if (actionLabel != null && onAction != null) {
                    Spacer(Modifier.height(14.dp))
                    CoachPillButton(label = actionLabel, onClick = onAction)
                }
            }
        }
    }
}

/**
 * The feature card's ink, fixed across both schemes.
 *
 * Not a scheme role, because its job is to be the *opposite* of the page in light
 * mode and still distinct from it in dark mode. A role would follow the scheme and
 * the card would vanish into a dark page.
 */
private val CoachFeatureSurface = Color(0xFF1B2440)
private val CoachFeatureEyebrow = Color(0xFFFFA45C)
private val CoachFeatureBody = Color(0xFFB4BDD4)

/** The filled pill. The one control that always means "do the main thing here". */
@Composable
fun CoachPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier
            .tappable(onClick, enabled = enabled),
        shape = CoachPillShape,
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (enabled) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
        )
    }
}

/** The quiet pill: a secondary action tucked into a section header. */
@Composable
fun CoachPillChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .tappable(onClick),
        shape = CoachPillShape,
        color = CoachAccent.Rust.colors().tint,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = CoachAccent.Rust.colors().ink,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------

/**
 * A titled card: icon chip, heading, an optional control on the right, then
 * whatever the section holds.
 *
 * This replaces the accent-rule section headers the player app uses. Both mark
 * where a section starts; the difference is that a coach section usually *is* a
 * container - a queue, a roster, a list of drills - and drawing the container is
 * more honest than drawing a heading above a floating list.
 */
@Composable
fun CoachSectionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: CoachAccent = CoachAccent.Indigo,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    CoachCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoachIconChip(icon = icon, accent = accent, size = 34.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

/** A plain heading for the stretches of a page that are not inside a card. */
@Composable
fun CoachSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = CoachGutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

// ---------------------------------------------------------------------------
// Empty states
// ---------------------------------------------------------------------------

/**
 * The dashed well every empty list in this shell shows.
 *
 * Dashed rather than solid on purpose: a solid box reads as content that happens
 * to be blank, and a dashed one reads as a space waiting to be filled. Each says
 * what would fill it and how, because a coach opening a brand-new account meets
 * four of these in a row and four blank panels would read as a broken app.
 */
@Composable
fun CoachDashedEmpty(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    accent: CoachAccent = CoachAccent.Indigo,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = outline,
                    // Inset by half the stroke so the dash sits inside the bounds
                    // rather than being clipped in half by them.
                    topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
                    size = Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()),
                    cornerRadius = CornerRadius(18.dp.toPx()),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f),
                    ),
                )
            }
            .padding(horizontal = 20.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoachIconChip(icon = icon, accent = accent, size = 52.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            CoachPillButton(label = actionLabel, onClick = onAction)
        }
    }
}

/**
 * An empty state that stands on its own rather than inside a section card.
 *
 * Same well, wrapped in a card, for the screens whose whole body is the empty
 * state. Kept as a distinct entry point so call sites do not each decide whether
 * to wrap.
 */
@Composable
fun CoachEmptyCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.ChevronRight,
    accent: CoachAccent = CoachAccent.Indigo,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    CoachCard(modifier = modifier, padding = 12.dp) {
        CoachDashedEmpty(
            icon = icon,
            title = title,
            body = body,
            accent = accent,
            actionLabel = actionLabel,
            onAction = onAction,
        )
    }
}

// ---------------------------------------------------------------------------
// Action tiles and info rows
// ---------------------------------------------------------------------------

/** One tinted shortcut in a [CoachActionRow]. */
@Immutable
data class CoachAction(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val accent: CoachAccent,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * A row of tinted shortcut tiles.
 *
 * Each tile is a whole tone rather than a white card with a coloured icon,
 * because these are peers competing for the same glance and a shared white
 * background would make them read as one control with three labels.
 */
@Composable
fun CoachActionRow(
    actions: List<CoachAction>,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    if (actions.isEmpty()) return
    CoachCard(modifier = modifier, padding = 14.dp) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { action ->
                ActionTile(action, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ActionTile(action: CoachAction, modifier: Modifier = Modifier) {
    val colors = action.accent.colors()
    Surface(
        modifier = modifier
            .tappable(action.onClick, enabled = action.enabled),
        shape = CoachInnerShape,
        // A disabled tile drops to the page's own container tone rather than
        // fading its tint, so "not available" never looks like "faintly available".
        color = if (action.enabled) colors.tint else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                tint = if (action.enabled) colors.ink else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = action.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (action.enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = action.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The footnote row: a circular badge, a claim, and a chevron if it goes somewhere.
 *
 * Where the coach shell puts its caveats - what a number is worth, what this build
 * does not do over a network. Given a row of its own rather than set as fine print
 * so that a coach can find the caveat without it competing with the thing it is a
 * caveat about.
 */
@Composable
fun CoachInfoRow(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    accent: CoachAccent = CoachAccent.Indigo,
    onClick: (() -> Unit)? = null,
) {
    val colors = accent.colors()
    CoachRowCard(modifier = modifier, onClick = onClick) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.tint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.ink,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onClick != null) {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Small parts
// ---------------------------------------------------------------------------

/**
 * An initials disc.
 *
 * Falls back to a dot rather than a question mark when the player never set a
 * name. A question mark reads as an error the coach is expected to fix, and a
 * missing name is not one - it is a preference the athlete is entitled to.
 */
@Composable
fun InitialsAvatar(
    initials: String?,
    modifier: Modifier = Modifier,
    diameter: Int = 44,
    accent: CoachAccent = CoachAccent.Rust,
) {
    val colors = accent.colors()
    Box(
        modifier = modifier
            .size(diameter.dp)
            .clip(CircleShape)
            .background(colors.tint),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials ?: "·",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = colors.ink,
        )
    }
}

/** A thin coloured pill for statuses. */
@Composable
fun StatusChip(
    label: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = CoachPillShape, color = container) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = content,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** A status pill in one of the four tones, which is the common case. */
@Composable
fun AccentChip(
    label: String,
    accent: CoachAccent,
    modifier: Modifier = Modifier,
) {
    val colors = accent.colors()
    StatusChip(label = label, container = colors.tint, content = colors.ink, modifier = modifier)
}

/**
 * The smallest type on the screen, for provenance and limitation lines.
 *
 * Set small on purpose: a coach should be able to find the caveat without it
 * competing with the thing it is a caveat about.
 */
@Composable
fun CoachFootnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun HorizontalGap(width: Int) = Spacer(Modifier.width(width.dp))

/**
 * `Today` / `3d ago` / `6 Aug`, from a timestamp.
 *
 * Relative for the first week and absolute after it, which is the split people
 * actually reason in: a coach cares that something arrived yesterday, and that a
 * session was some time last month. "31 days ago" is a number nobody converts.
 *
 * Deliberately not a composable and deliberately free of Android formatting APIs,
 * so it can be unit-tested against a pinned clock. The date pattern matches the
 * Progress tab's, so the two halves of the app date things the same way.
 */
fun relativeDayLabel(epochMs: Long, nowEpochMs: Long): String {
    val elapsed = nowEpochMs - epochMs
    // A timestamp from the future is a clock that moved, not a date to report.
    if (elapsed < 0L) return "Today"
    return when (val days = TimeUnit.MILLISECONDS.toDays(elapsed).toInt()) {
        0 -> "Today"
        1 -> "Yesterday"
        in 2..6 -> "${days}d ago"
        else -> Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .format(coachDateFormat)
    }
}

private val coachDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/** `12 Aug, 17:40`. Used where the exact set matters, as on the review screen. */
fun sessionTimestampLabel(epochMs: Long): String = Instant.ofEpochMilli(epochMs)
    .atZone(ZoneId.systemDefault())
    .format(coachTimestampFormat)

private val coachTimestampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")

/** The dash every screen prints where there is nothing measured to print. */
const val NO_VALUE = "—"
