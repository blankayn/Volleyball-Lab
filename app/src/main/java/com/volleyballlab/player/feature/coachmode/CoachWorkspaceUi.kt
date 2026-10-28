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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
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
    portrait: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF282D29))) {
        Canvas(Modifier.matchParentSize()) {
            val line = Color.White.copy(alpha = .055f)
            repeat(9) { i ->
                val x = size.width * .7f + i * 22.dp.toPx()
                drawLine(line, Offset(x, 0f), Offset(x - size.height * .25f, size.height), 1.dp.toPx())
                val y = i * 24.dp.toPx()
                drawLine(line, Offset(size.width * .7f - y * .25f, y), Offset(size.width, y), 1.dp.toPx())
            }
        }
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            WorkspaceEyebrow(eyebrow, Color(0xFFF1B58F))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(shape = RoundedCornerShape(18.dp), color = Color.White.copy(alpha = .10f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = .18f)), modifier = Modifier.size(64.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(initials ?: "C", fontSize = 24.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        portrait?.invoke()
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = Color.White)
                    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFFD9DCD4))
                }
            }
            action?.invoke()
        }
    }
}
