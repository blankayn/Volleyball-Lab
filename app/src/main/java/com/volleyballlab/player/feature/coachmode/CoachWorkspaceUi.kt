package com.volleyballlab.player.feature.coachmode

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection

/** Shared page treatment for the coach workspace; cards carry the content. */
@Composable
internal fun Modifier.coachWorkspaceBackground(): Modifier = background(
    if (MaterialTheme.colorScheme.background.luminance() < .4f) MaterialTheme.colorScheme.background
    else Color(0xFFF6F5F1)
)

@Composable
internal fun WorkspaceEyebrow(label: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.3.sp,
        fontWeight = FontWeight.SemiBold, color = color)
}

/** Scrolls at narrow widths and large text sizes instead of compressing labels. */
@Composable
internal fun CoachFilterTabs(
    labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .horizontalScroll(rememberScrollState()).selectableGroup().padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { index, label ->
            Box(Modifier.clip(RoundedCornerShape(12.dp))
                .background(if (index == selected) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent)
                .selectable(index == selected, role = Role.Tab, onClick = { onSelect(index) })
                .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 13.dp),
                contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (index == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun CoachSearchField(
    value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, supportingText: String? = null,
) {
    OutlinedTextField(value, onValueChange, label = { Text(label) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }) {
            Icon(Icons.Rounded.Close, "Clear search")
        } },
        supportingText = if (supportingText != null) { { Text(supportingText) } } else null,
        singleLine = true, shape = CoachInnerShape, modifier = modifier.fillMaxWidth())
}

/** Cards own horizontal gutters; every coach list has the same safe bottom clearance. */
@Composable
internal fun coachListPadding(padding: PaddingValues): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(start = padding.calculateStartPadding(direction), end = padding.calculateEndPadding(direction),
        top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)
}

@Composable
internal fun CoachNavigationRow(
    title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    CoachRowCard(modifier, onClick) {
        CoachIconChip(icon, CoachAccent.Rust, size = 38.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun CoachWorkspaceIdentity(
    name: String, subtitle: String, initials: String?, eyebrow: String, modifier: Modifier = Modifier,
    banner: String? = null,
    portrait: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    onPhotoClick: (() -> Unit)? = null,
    onCoverClick: (() -> Unit)? = null,
    mediaEnabled: Boolean = true,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Box(Modifier.fillMaxWidth().height(210.dp)) {
                Box(Modifier.fillMaxWidth().height(152.dp).background(MaterialTheme.colorScheme.primaryContainer)) {
                    com.volleyballlab.player.feature.profile.ProfileMediaImage(banner, "Profile cover photo", Modifier.fillMaxSize())
                    if (onCoverClick != null) FilledTonalButton(onClick = onCoverClick, enabled = mediaEnabled,
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                        Icon(Icons.Rounded.PhotoCamera, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("Edit cover", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Box(Modifier.align(Alignment.BottomStart).padding(start = 20.dp).size(112.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(4.dp, MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(initials ?: "·", fontSize = 30.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        portrait?.invoke()
                    }
                }
                if (onPhotoClick != null) Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary,
                    border = BorderStroke(3.dp, MaterialTheme.colorScheme.surface), modifier = Modifier.align(Alignment.BottomEnd).size(44.dp)) {
                    IconButton(onClick = onPhotoClick, enabled = mediaEnabled) {
                        Icon(Icons.Rounded.PhotoCamera, "Change profile picture", Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                }
            }
            Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                WorkspaceEyebrow(eyebrow)
                Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.invoke()
            }
        }
    }
}
