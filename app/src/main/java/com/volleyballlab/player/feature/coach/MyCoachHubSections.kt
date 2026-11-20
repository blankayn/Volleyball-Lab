package com.volleyballlab.player.feature.coach

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.ui.component.DrillArt
import com.volleyballlab.player.ui.component.skillVisual
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val CardShape = RoundedCornerShape(20.dp)

@Composable internal fun HubSurface(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val border = BorderStroke(1.dp, coachCardOutline())
    val color = MaterialTheme.colorScheme.surfaceContainerLowest
    if (onClick != null) Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = CardShape, color = color, border = border, content = content)
    else Surface(modifier.fillMaxWidth(), shape = CardShape, color = color, border = border, content = content)
}

@Composable internal fun HubLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
}

/**
 * The connected coach at the top of My coach: who they are and whether they are
 * around. Tapping it opens their profile; asking happens from the list below, so
 * there is exactly one way in.
 */
@Composable
internal fun MyCoachCard(coach: CoachPreview, summary: CoachPublicSummary, onViewProfile: () -> Unit) {
    val media = com.volleyballlab.player.feature.profile.rememberProfileMedia(coach.coachUid, coach = true)
    val primary = MaterialTheme.colorScheme.primary
    HubSurface(onClick = onViewProfile) {
      Column {
        // Cover photo, with the coach's portrait overlapping its bottom edge.
        Box(Modifier.fillMaxWidth().height(150.dp)) {
            Box(Modifier.fillMaxWidth().height(116.dp)
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(primary.copy(alpha = .30f), primary.copy(alpha = .10f))))) {
                com.volleyballlab.player.feature.profile.ProfileMediaImage(media.banner, "Coach cover photo", Modifier.matchParentSize())
            }
            Surface(Modifier.align(Alignment.BottomStart).padding(start = 18.dp), shape = RoundedCornerShape(23.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                CoachAvatar(coach.coachUid, coach.initials(), Modifier.padding(3.dp).size(64.dp))
            }
        }
        Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(coach.displayName(stringResource(R.string.coach_coach_default)),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                coach.profile.teamName.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = coachCardMuted(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CoachMetaLine(summary)
            }
            Icon(Icons.Rounded.ChevronRight, "View coach profile", Modifier.size(20.dp), tint = coachCardMuted())
        }
      }
    }
}

/** "● Online · ★ 4.8 · Replies in about 2 hr", leaving out whatever is not known yet. */
@Composable private fun CoachMetaLine(summary: CoachPublicSummary) {
    val muted = coachCardMuted()
    val dot = when (summary.availability) {
        CoachAvailability.Online -> Color(0xFF2E9D5B)
        CoachAvailability.Busy -> Color(0xFFD08A1E)
        else -> muted.copy(alpha = .6f)
    }
    val parts = buildList {
        summary.averageRating?.let { add("★ " + String.format(Locale.getDefault(), "%.1f", it)) }
        summary.typicalResponseMs?.let { add("Replies in about ${coachingResponseLabel(it)}") }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text((listOf(summary.availability?.label ?: "Status unknown") + parts).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Everything under the coach card on My coach: the next drill, the three places to
 * go, and the coach's latest note. Forms and submissions stay in their own sections.
 */
@Composable
internal fun MyCoachHubSections(
    state: CoachLinkState,
    onManageConnection: (() -> Unit)? = null,
    unread: Int = 0,
    onOpenAssignment: (String) -> Unit = {},
    onBrowse: () -> Unit = {},
    onOpen: (CoachWorkspaceTab) -> Unit,
) {
    val link = state.link
    val active = link?.isActive == true
    val open = state.assignments.filter { it.coachUid == link?.coachUid && it.isOpen }
        .sortedBy { it.dueAtEpochMs ?: Long.MAX_VALUE }
    val reviewed = state.submissions.count { !it.isPending }
    val pending = state.submissions.count { it.isPending }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!active) {
            if (link?.isPending == true) StatusCard(Icons.Rounded.HourglassEmpty, "Request sent",
                "Waiting for ${link.coachName.ifBlank { "your coach" }} to accept. Your training plan and feedback will show up here.")
            else StatusCard(Icons.Rounded.PersonSearch, "You don’t have a coach yet",
                "A coach can send you drills to practise and review your recorded sessions.") {
                Button(onClick = onBrowse, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                    Text("Find a coach")
                }
            }
            if (state.submissions.isNotEmpty()) {
                HubSurface { HubRow(Icons.Rounded.RateReview, "Past feedback", "$reviewed reviewed") { onOpen(CoachWorkspaceTab.Feedback) } }
            }
        } else {
        HubLabel("Up next")
        val next = open.firstOrNull()
        if (next != null) NextDrillCard(next, more = open.size - 1) { onOpenAssignment(next.id) }
        else HubSurface {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Nothing assigned yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Drills from your coach will show up here.", style = MaterialTheme.typography.bodyMedium, color = coachCardMuted())
            }
        }

        HubLabel("Your coaching")
        HubSurface {
            Column {
                HubRow(Icons.Rounded.FitnessCenter, CoachWorkspaceTab.Training.label,
                    if (open.isEmpty()) "None yet" else "${open.size} to practise") { onOpen(CoachWorkspaceTab.Training) }
                HorizontalDivider(Modifier.padding(start = 58.dp), color = coachCardOutline())
                HubRow(Icons.Rounded.RateReview, CoachWorkspaceTab.Feedback.label, when {
                    pending > 0 -> "$pending waiting"
                    reviewed > 0 -> "$reviewed reviewed"
                    else -> "None yet"
                }) { onOpen(CoachWorkspaceTab.Feedback) }
                HorizontalDivider(Modifier.padding(start = 58.dp), color = coachCardOutline())
                HubRow(Icons.Rounded.Forum, CoachWorkspaceTab.Overview.label, null, unread) { onOpen(CoachWorkspaceTab.Overview) }
            }
        }

        if (onManageConnection != null) {
            TextButton(onClick = onManageConnection, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Manage coach connection", color = coachCardMuted())
            }
        }
        }
    }
}

/** The single most useful thing to do next: the open drill due soonest. */
@Composable private fun NextDrillCard(assignment: CoachAssignment, more: Int, onClick: () -> Unit) {
    val visual = skillVisual(assignment.skill)
    val art = assignment.libraryDrillId?.let(DrillArt::forDrill) ?: visual.illustration
    HubSurface(onClick = onClick) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(60.dp).clip(RoundedCornerShape(16.dp)).background(visual.wash), contentAlignment = Alignment.Center) {
                    Image(painterResource(art), null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(assignment.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(buildString {
                        append("${assignment.targetSets} sets × ${assignment.targetReps} reps")
                        assignment.dueAtEpochMs?.let { append(" · Due ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))}") }
                    }, style = MaterialTheme.typography.bodySmall, color = coachCardMuted())
                }
            }
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                Text("Start drill")
            }
            if (more > 0) Text("$more more in your training plan", style = MaterialTheme.typography.bodySmall,
                color = coachCardMuted(), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}

/** One tappable line in a grouped list: icon, label, what is waiting there, chevron. */
@Composable internal fun HubRow(icon: ImageVector, label: String, detail: String?, unread: Int = 0, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 60.dp)
        .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = coachCardMuted()) }
        CoachingUnreadBadge(unread)
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = coachCardMuted())
    }
}

@Composable private fun StatusCard(icon: ImageVector, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    HubSurface {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = .09f)),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = coachCardMuted(), textAlign = TextAlign.Center)
            action?.let { Spacer(Modifier.height(4.dp)); it() }
        }
    }
}
