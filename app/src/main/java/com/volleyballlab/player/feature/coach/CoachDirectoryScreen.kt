package com.volleyballlab.player.feature.coach

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
) {
    val model: CoachLinkViewModel = viewModel(factory = CoachLinkViewModel.Factory)
    val state by model.state.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val requesting by model.requesting.collectAsStateWithLifecycle()
    val requestedCoach by model.requestedCoach.collectAsStateWithLifecycle()
    val coach = state.directory.firstOrNull { it.coachUid == coachUid }
    LaunchedEffect(requestedCoach) { if (requestedCoach == coachUid) onRequestSent() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().studentBackground(),
        contentPadding = pagePadding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { DiscoveryHeader("Coach profile", "Get to know your coach", onBack) }
        item { CoachSyncBanner(coach = false) }
        when {
            !state.loaded -> item { LoadingCoaches() }
            !state.plan.isPro -> item { ProPlanCard() }
            coach == null -> item {
                Text("This coach profile is no longer available.", style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onBack) { Text("Back to coaches") }
            }
            else -> {
                item {
                    StudentCard {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CoachInitials(coach, large = true)
                            Text(coach.displayName(stringResource(R.string.coach_coach_default)),
                                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            coach.profile.teamName.takeIf(String::isNotBlank)?.let {
                                Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            coach.profile.specialisation?.let {
                                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text(stringResource(it.displayName), Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                            }
                        }
                    }
                }
                item {
                    CoachPublicDetails(coachUid)
                    Spacer(Modifier.height(20.dp))
                    Text("Coaching details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    StudentCard {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            coach.profile.organisation.takeIf(String::isNotBlank)?.let { CoachFact("School or club", it) }
                            coach.profile.specialisation?.let { CoachFact("Specialization", stringResource(it.displayName)) }
                            coach.profile.yearsExperience?.let { CoachFact("Coaching experience", "$it ${if (it == 1) "year" else "years"}") }
                            CoachFact("Team size", "${coach.rosterSize} ${if (coach.rosterSize == 1) "player" else "players"}")
                        }
                    }
                }
                item {
                    Text("How it works", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Text("1. Send a request to join this coach’s team.\n2. Your coach reviews and accepts your request.\n3. Share the sessions you choose and receive feedback.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    error?.let {
                        Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                    }
                    state.link?.let {
                        Text(if (it.coachUid == coachUid) {
                            if (it.isPending) "Your request is waiting for this coach’s approval." else "You are connected to this coach."
                        } else "You already have a team connection. Manage it from the Coach tab before choosing another coach.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                    }
                    Button(onClick = { model.sendRequest(coachUid) }, enabled = !requesting && state.link == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        if (requesting) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(if (requesting) "Sending request…" else if (state.link?.coachUid == coachUid) {
                            if (state.link?.isPending == true) "Request pending" else "Connected"
                        } else "Send connection request")
                    }
                }
            }
        }
    }
}

private fun pagePadding(padding: PaddingValues) = PaddingValues(
    start = 20.dp, end = 20.dp, top = padding.calculateTopPadding() + 12.dp,
    bottom = padding.calculateBottomPadding() + 24.dp,
)

@Composable
private fun DiscoveryHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        Column(Modifier.padding(start = 8.dp)) {
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
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
