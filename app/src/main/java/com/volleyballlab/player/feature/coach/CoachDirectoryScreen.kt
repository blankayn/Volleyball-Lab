package com.volleyballlab.player.feature.coach

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.CoachPreview
import com.volleyballlab.player.domain.model.CoachSpecialisation
import com.volleyballlab.player.feature.profile.ProPlanCard
import com.volleyballlab.player.ui.component.StudentCard
import com.volleyballlab.player.ui.component.studentBackground

/** Coach discovery has its own back stack and scroll position, separate from training advice. */
@Composable
fun CoachDirectoryScreen(
    onBack: () -> Unit,
    onOpenCoach: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val model: CoachLinkViewModel = viewModel(factory = CoachLinkViewModel.Factory)
    val state by model.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var specialisation by rememberSaveable { mutableStateOf<CoachSpecialisation?>(null) }
    val filtered = state.directory.filter { coach ->
        (specialisation == null || coach.profile.specialisation == specialisation) &&
            listOf(coach.profile.name, coach.profile.teamName, coach.profile.organisation)
                .any { it.contains(query.trim(), ignoreCase = true) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().studentBackground(),
        contentPadding = pagePadding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { DiscoveryHeader("Find a coach", "A team for your next chapter", onBack) }
        item { CoachSyncBanner(coach = false) }
        when {
            !state.loaded -> item { LoadingCoaches() }
            !state.plan.isPro -> item { ProPlanCard() }
            else -> {
                item {
                    Text("Explore coaches and get to know their approach before asking to join.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        label = { Text("Search name, team or school") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    )
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(selected = specialisation == null, onClick = { specialisation = null }, label = { Text("All coaches") }) }
                        items(CoachSpecialisation.All) { spec ->
                            FilterChip(selected = specialisation == spec, onClick = { specialisation = spec },
                                label = { Text(stringResource(spec.displayName)) })
                        }
                    }
                }
                item {
                    Text("${filtered.size} ${if (filtered.size == 1) "coach" else "coaches"}",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (filtered.isEmpty()) item {
                    StudentCard {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (state.directory.isEmpty()) "Coaches are on their way" else "No matching coaches",
                                style = MaterialTheme.typography.titleMedium)
                            Text(if (state.directory.isEmpty()) "Published coach profiles will appear here. Check back soon."
                                else "Try another name or choose a different specialization.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (query.isNotBlank() || specialisation != null) TextButton(onClick = { query = ""; specialisation = null }) { Text("Clear filters") }
                        }
                    }
                }
                items(filtered, key = { it.coachUid }) { coach ->
                    CoachDiscoveryCard(coach, onClick = { onOpenCoach(coach.coachUid) })
                }
            }
        }
    }
}

@Composable
fun CoachProfileScreen(
    coachUid: String,
    onBack: () -> Unit,
    onRequestSent: () -> Unit,
    contentPadding: PaddingValues,
    onOpenWorkspace: () -> Unit = {},
) {
    val model: CoachLinkViewModel = viewModel(factory = CoachLinkViewModel.Factory)
    val state by model.state.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val requesting by model.requesting.collectAsStateWithLifecycle()
    val requestedCoach by model.requestedCoach.collectAsStateWithLifecycle()
    val coach = state.directory.firstOrNull { it.coachUid == coachUid }
    val current = state.link?.coachUid == coachUid
    val connected = current && state.link?.isActive == true
    val canViewProfile = state.plan.isPro || connected
    val summary = if (state.loaded && canViewProfile && coach != null) rememberCoachSummary(coachUid).value else com.volleyballlab.player.domain.model.CoachPublicSummary()
    LaunchedEffect(requestedCoach) { if (requestedCoach == coachUid) onRequestSent() }

    Column(Modifier.fillMaxSize().coachPageBackground()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = contentPadding.calculateTopPadding() + 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item { Column { DiscoveryHeader("Coach profile", "The person behind the plan", onBack); CoachSyncBanner(coach = false) } }
            when {
                !state.loaded -> item { LoadingCoaches() }
                !canViewProfile -> item { ProPlanCard() }
                coach == null -> item { CoachEmptyState("Profile unavailable", "This coach may have removed their directory listing. Your existing feedback stays in My coach.") }
                else -> {
                    item { com.volleyballlab.player.ui.component.EntranceAnimation(index = 0) { CoachIdentityHero(coach, summary, connected) } }
                    item { CoachMetrics(summary) }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            CoachSectionTitle("Coaching background", "A closer look at their experience and team")
                            CoachSectionCard {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(coach.profile.yearsExperience?.toString() ?: "—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
                                        Text("Years coaching", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(coach.rosterSize.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
                                        Text("Players on the team", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                                CoachFact("School or club", coach.profile.organisation.ifBlank { "Not provided" })
                                coach.profile.teamName.takeIf(String::isNotBlank)?.let { CoachFact("Team", it) }
                                coach.profile.specialisation?.let { CoachFact("Coaching focus", stringResource(it.displayName)) }
                            }
                        }
                    }
                    item { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { CoachReviews(coachUid, summary) } }
                    if (!connected) item {
                        CoachSectionCard {
                            CoachEyebrow("Working together")
                            Text("A clear path from practice to progress", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            CoachConnectionStep("01", "Request a place", "Choose a coach whose focus fits your training goals.")
                            CoachConnectionStep("02", "Get approved", "Your coach accepts your request before training is shared.")
                            CoachConnectionStep("03", "Practise with guidance", "Follow assignments, share sessions, and get focused feedback.")
                        }
                    }
                    item {
                        Text("Status follows recent coaching activity. Response estimates come from recent answered training questions.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (state.loaded && canViewProfile && coach != null) Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = contentPadding.calculateBottomPadding() + 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                error?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (current && state.link?.isPending == true) Text("Request sent. Your coach will review it before you join.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (state.link != null && !current) Text("You already have a team connection. Manage it in My coach before choosing another.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { if (connected) onOpenWorkspace() else model.sendRequest(coachUid) },
                    enabled = connected || (!requesting && state.link == null),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(16.dp)) {
                    if (requesting) { CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
                    Text(if (connected) "Open My coach" else if (requesting) "Sending request…" else if (current && state.link?.isPending == true) "Awaiting approval" else if (state.link != null) "Already connected to a team" else "Request to join team", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    if (!requesting && (connected || state.link == null)) Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun CoachConnectionStep(number: String, title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .08f)) {
            Text(number, Modifier.padding(horizontal = 11.dp, vertical = 9.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


private fun pagePadding(padding: PaddingValues) = PaddingValues(
    start = 20.dp, end = 20.dp, top = padding.calculateTopPadding() + 12.dp,
    bottom = padding.calculateBottomPadding() + 24.dp,
)

@Composable
private fun DiscoveryHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", modifier = Modifier.size(21.dp)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            CoachEyebrow(subtitle)
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LoadingCoaches() {
    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text("Loading coaches…")
    }
}

@Composable
private fun CoachDiscoveryCard(coach: CoachPreview, onClick: () -> Unit) {
    StudentCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CoachInitials(coach)
                Column(Modifier.weight(1f)) {
                    Text(coach.displayName(stringResource(R.string.coach_coach_default)),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    coach.profile.teamName.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            coach.profile.organisation.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val summary = listOfNotNull(
                coach.profile.specialisation?.let { stringResource(it.displayName) },
                coach.profile.yearsExperience?.let { "$it ${if (it == 1) "year" else "years"} coaching" },
            ).joinToString(" · ")
            if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("View coach profile", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CoachInitials(coach: CoachPreview, large: Boolean = false) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(if (large) 80.dp else 52.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(coach.initials() ?: "C", style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun CoachFact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}
