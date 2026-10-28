package com.volleyballlab.player.feature.coach

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal val CoachInk = Color(0xFF242925)

@Composable internal fun Modifier.coachPageBackground(): Modifier = background(
    if (MaterialTheme.colorScheme.background.luminance() < .4f) MaterialTheme.colorScheme.background else Color(0xFFF6F5F1)
)

@Composable internal fun rememberCoachSummary(uid: String): State<CoachPublicSummary> {
    val actions: CoachingActionsModel = viewModel(factory = CoachingActionsModel.Factory)
    val stream = remember(uid) { actions.hub.summary(uid) }
    return stream.collectAsStateWithLifecycle(CoachPublicSummary())
}

@Composable internal fun CoachEyebrow(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = color)
}

@Composable internal fun CoachIdentityHero(
    coach: CoachPreview,
    summary: CoachPublicSummary,
    connected: Boolean,
    onViewProfile: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
        .background(Brush.linearGradient(listOf(CoachInk, Color(0xFF373B32))))) {
        Canvas(Modifier.matchParentSize()) {
            val left = size.width * .60f
            val ink = Color.White.copy(alpha = .055f)
            repeat(7) { index ->
                val x = left + index * 24.dp.toPx()
                drawLine(ink, Offset(x, 0f), Offset(x - size.height * .22f, size.height), 1.dp.toPx())
            }
            repeat(9) { index ->
                val y = index * 25.dp.toPx()
                drawLine(ink, Offset(left - y * .22f, y), Offset(size.width, y), 1.dp.toPx())
            }
            drawLine(Color(0xFFED9C72).copy(alpha = .38f), Offset(size.width * .64f, size.height), Offset(size.width, size.height * .73f), 2.dp.toPx())
        }
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CoachEyebrow(if (connected) "Your coach" else "Coach profile", Color(0xFFF3BA97))
                AvailabilityTag(summary.availability, onDark = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CoachAvatar(coach.coachUid, coach.initials(), Modifier.size(72.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(coach.displayName(stringResource(R.string.coach_coach_default)), color = Color.White,
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp)
                    coach.profile.teamName.takeIf(String::isNotBlank)?.let {
                        Text(it, color = Color(0xFFDEDCD2), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                coach.profile.specialisation?.let {
                    Text(stringResource(it.displayName), color = Color(0xFFF5BE9C), style = MaterialTheme.typography.labelLarge)
                }
                coach.profile.yearsExperience?.let {
                    Text("$it ${if (it == 1) "year" else "years"} coaching", color = Color(0xFFDEDCD2), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (onViewProfile != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HorizontalDivider(color = Color.White.copy(alpha = .13f))
                    TextButton(onClick = onViewProfile, contentPadding = PaddingValues(0.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                        Text("View coach profile", color = Color.White, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Color(0xFFF3BA97), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable internal fun AvailabilityTag(status: CoachAvailability?, onDark: Boolean = false) {
    val ink = when (status) {
        CoachAvailability.Online -> if (onDark) Color(0xFFB5D2B4) else Color(0xFF285837)
        CoachAvailability.Busy -> if (onDark) Color(0xFFF4C57D) else Color(0xFF805217)
        else -> if (onDark) Color(0xFFD8D8D0) else MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(20.dp), color = ink.copy(alpha = if (onDark) .10f else .08f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(5.dp).clip(RoundedCornerShape(3.dp)).background(ink))
            Text(status?.label ?: "Status unavailable", style = MaterialTheme.typography.labelSmall, color = ink)
        }
    }
}

@Composable internal fun CoachMetrics(summary: CoachPublicSummary) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))) {
        Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Column(Modifier.weight(1f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(summary.averageRating?.let { "★ ${String.format(Locale.getDefault(), "%.1f", it)}" } ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Coach rating", style = MaterialTheme.typography.labelMedium)
                Text(if (summary.reviews.isEmpty()) "No reviews yet" else "${summary.reviews.size} session reviews", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.width(1.dp).height(75.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)))
            Column(Modifier.weight(1f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(summary.typicalResponseMs?.let(::coachingResponseLabel) ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Typical response", style = MaterialTheme.typography.labelMedium)
                Text(if (summary.typicalResponseMs == null) "History building" else "From recent replies", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

internal enum class CoachWorkspaceTab(val label: String) { Overview("Overview"), Training("Training"), Feedback("Feedback") }

@Composable internal fun CoachSectionTitle(title: String, subtitle: String? = null, count: Int? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            count?.let { Text(it.toString().padStart(2, '0'), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable internal fun CoachSectionCard(modifier: Modifier = Modifier, tint: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = if (tint) MaterialTheme.colorScheme.primary.copy(alpha = .055f).compositeOver(MaterialTheme.colorScheme.surfaceContainerLowest) else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable internal fun CoachEmptyState(title: String, body: String) {
    CoachSectionCard {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .08f)) {
            Icon(Icons.Rounded.StarBorder, null, Modifier.padding(12.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun CoachReviews(coachUid: String, summary: CoachPublicSummary) {
    var expanded by rememberSaveable(coachUid) { mutableStateOf(false) }
    CoachSectionTitle("What players say", "Reviews from completed coaching sessions", summary.reviews.size)
    if (summary.reviews.isEmpty()) CoachEmptyState("No reviews yet", "Verified session reviews will appear here as players share their experience.")
    else {
        val visible = if (expanded) summary.reviews else summary.reviews.take(3)
        visible.forEach { review ->
            CoachSectionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("★".repeat(review.rating.coerceIn(1, 5)), color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp, style = MaterialTheme.typography.titleMedium)
                    Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(review.createdAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (review.text.isNotBlank()) Text(review.text, style = MaterialTheme.typography.bodyLarge, lineHeight = 24.sp)
                Text("Verified player · session review", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (summary.reviews.size > 3) TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) { Text(if (expanded) "Show fewer reviews" else "View all ${summary.reviews.size} reviews") }
    }
}
