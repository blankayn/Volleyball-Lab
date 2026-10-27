package com.volleyballlab.player.feature.coachmode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.R
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.domain.model.PlayerSnapshot
import com.volleyballlab.player.domain.model.RosterMember
import com.volleyballlab.player.domain.repository.CoachRepository
import com.volleyballlab.player.domain.repository.SessionDirectory
import com.volleyballlab.player.ui.component.EntranceAnimation
import com.volleyballlab.player.ui.component.GhostLayout
import com.volleyballlab.player.ui.component.GhostLoadingScreen
import com.volleyballlab.player.ui.component.scoreColor
import com.volleyballlab.player.ui.theme.VbTabularNumerals
import com.volleyballlab.player.ui.theme.vbTween
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CoachRosterViewModel(
    private val coachRepository: CoachRepository,
    sessionDirectory: SessionDirectory,
) : ViewModel() {

    val state: StateFlow<CoachTeamState> =
        coachTeamState(coachRepository, sessionDirectory).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CoachTeamState(),
        )

    fun approve(playerUid: String) {
        viewModelScope.launch { coachRepository.approve(playerUid) }
    }

    fun decline(playerUid: String) {
        viewModelScope.launch { coachRepository.decline(playerUid) }
    }

    fun remove(playerUid: String) {
        viewModelScope.launch { coachRepository.removeFromRoster(playerUid) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CoachRosterViewModel(
                    coachRepository = appContainer.coachRepository,
                    sessionDirectory = appContainer.sessionDirectory,
                )
            }
        }
    }
}

/**
 * The roster, and the requests waiting to get onto it.
 *
 * Figures 26 and 27 of the capstone document, which the web portal draws as two
 * separate pages. They are one screen here because on a phone they are the same
 * question - who is on my team - asked about two groups, and a coach who has to
 * find a second page to answer a request will leave requests unanswered.
 *
 * ## Nothing here adds a player
 *
 * The document is explicit that the player asks and the coach answers, so there is
 * no add button on this screen and there should not be one. What fills this roster
 * is an athlete typing the coach code on their own phone; the code lives on the
 * Team tab, where a coach can read it out.
 */
@Composable
fun CoachRosterScreen(
    onOpenPlayer: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val viewModel: CoachRosterViewModel = viewModel(factory = CoachRosterViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now = remember { System.currentTimeMillis() }

    if (!state.loaded) {
        GhostLoadingScreen(GhostLayout.List, contentPadding, modifier)
        return
    }

    var search by rememberSaveable { mutableStateOf("") }
    val query = search.trim()
    val active = state.snapshots.filter { it.member.isActive && it.member.playerName.contains(query, ignoreCase = true) }
    val requests = state.pendingRequests.filter { it.playerName.contains(query, ignoreCase = true) }
    val past = state.pastMembers.filter { it.playerName.contains(query, ignoreCase = true) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            EntranceAnimation(index = 0) {
                CoachPageHeader(
                    title = stringResource(R.string.coach_roster_title),
                    subtitle = state.profile.teamName.takeIf { it.isNotBlank() },
                )
            }
        }

        item(key = "player-search") {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text(stringResource(R.string.coach_search_players)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (search.isNotEmpty()) IconButton(onClick = { search = "" }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.coach_search_clear))
                    }
                },
                supportingText = {
                    if (query.isNotEmpty()) Text(stringResource(R.string.coach_search_count, active.size + requests.size + past.size))
                },
                singleLine = true,
                shape = CoachInnerShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = CoachGutter),
            )
        }
        if (query.isNotEmpty() && active.isEmpty() && requests.isEmpty() && past.isEmpty()) {
            item(key = "search-empty") {
                CoachCard(Modifier.padding(horizontal = CoachGutter)) {
                    Text(stringResource(R.string.coach_search_empty_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.coach_search_empty_body), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { search = "" }) { Text(stringResource(R.string.coach_search_clear)) }
                }
            }
        }

        // Requests sit above the squad and are the only rows here with buttons on
        // them. That is the ranking this screen argues for: answering a request is
        // something only the coach can do and the player is blocked until they do
        // it, whereas reading a roster row can wait.
        if (requests.isNotEmpty()) {
            item {
                EntranceAnimation(index = 1) {
                    CoachSectionTitle(text = stringResource(R.string.coach_roster_requests))
                }
            }
            itemsIndexed(
                items = requests,
                key = { _, member -> "request-" + member.playerUid },
            ) { index, member ->
                EntranceAnimation(index = index + 2, modifier = Modifier.animateItem(fadeInSpec = vbTween(), placementSpec = vbTween(), fadeOutSpec = vbTween())) {
                    RequestRow(
                        member = member,
                        now = now,
                        onApprove = { viewModel.approve(member.playerUid) },
                        onDecline = { viewModel.decline(member.playerUid) },
                        modifier = Modifier.padding(horizontal = CoachGutter),
                    )
                }
            }
        }

        if (active.isEmpty() && query.isEmpty()) {
            item {
                EntranceAnimation(index = 2) {
                    CoachFeatureCard(
                        // The tab's own name as the eyebrow: the card is standing in
                        // for the roster that is not there yet, so it says which list
                        // is empty before it says what to do about it.
                        eyebrow = stringResource(R.string.nav_coach_roster),
                        title = stringResource(R.string.coach_roster_empty_title),
                        body = stringResource(R.string.coach_roster_empty_body),
                        modifier = Modifier.padding(horizontal = CoachGutter),
                        // No action pill. The thing that fills this roster is a
                        // player typing the coach's code on their own phone, which
                        // is not something a button here can do - and a dark card
                        // whose button does nothing useful is worse than one that
                        // simply explains. The code itself lives on the Team tab.
                        art = {
                            CoachIconChip(
                                icon = Icons.Rounded.PersonAdd,
                                accent = CoachAccent.Rust,
                                size = 56.dp,
                            )
                        },
                    )
                }
            }
        } else if (active.isNotEmpty()) {
            item {
                EntranceAnimation(index = 2) {
                    CoachSectionTitle(text = stringResource(R.string.coach_roster_active))
                }
            }

            itemsIndexed(
                items = active,
                key = { _, snapshot -> snapshot.member.playerUid },
            ) { index, snapshot ->
                EntranceAnimation(index = index + 3, modifier = Modifier.animateItem(fadeInSpec = vbTween(), placementSpec = vbTween(), fadeOutSpec = vbTween())) {
                    PlayerRow(
                        snapshot = snapshot,
                        onClick = { onOpenPlayer(snapshot.member.playerUid) },
                        modifier = Modifier.padding(horizontal = CoachGutter),
                    )
                }
            }
        }

        if (past.isNotEmpty()) {
            item {
                CoachSectionTitle(
                    text = stringResource(R.string.coach_roster_past),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            items(
                items = past,
                key = { member -> "past-" + member.playerUid },
            ) { member ->
                PastRow(member)
            }
        }
    }
}

/**
 * A pending join request. Figure 27.
 *
 * Deliberately shows the applicant's name, position and how long they have waited,
 * and *nothing* about their training. The coach has not approved them yet, so the
 * app has no business showing their form scores to decide with - that is precisely
 * the boundary the request-and-approve step exists to draw, and a preview of it
 * here would make the approval a formality after the fact.
 */
@Composable
private fun RequestRow(
    member: RosterMember,
    now: Long,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CoachCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InitialsAvatar(member.initials(), accent = CoachAccent.Rust)
            HorizontalGap(12)
            Column(Modifier.weight(1f)) {
                Text(
                    text = member.playerName.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.coach_unnamed),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = listOfNotNull(
                        member.position?.let { stringResource(it.displayName) },
                        stringResource(
                            R.string.coach_roster_asked,
                            relativeDayLabel(member.requestedAtEpochMs, now),
                        ),
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CoachPillButton(
                label = stringResource(R.string.coach_roster_approve),
                onClick = onApprove,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = onDecline,
                shape = CoachPillShape,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.coach_roster_decline))
            }
        }
    }
}

/**
 * One athlete on the roster.
 *
 * Carries the two numbers a coach scans a squad list for - how much they have
 * trained and how they are scoring - plus a count of anything waiting on the
 * coach. Everything else is on the detail screen this row opens.
 *
 * The form score keeps its own colour where the rest of the row is monochrome,
 * because it is the one value here that means something on sight; the name and the
 * subtitle have to be read.
 */
@Composable
private fun PlayerRow(
    snapshot: PlayerSnapshot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CoachRowCard(modifier = modifier, onClick = onClick) {
        InitialsAvatar(snapshot.member.initials(), accent = CoachAccent.Rust)
        HorizontalGap(12)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = snapshot.member.playerName.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.coach_unnamed),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // Beside the name rather than at the end of the row, because the end
                // of the row is where the form score sits. A "Sample" badge floating
                // next to a number reads as a note about the number; next to the
                // name it reads as what it is - a note about the person.
                if (SampleTeam.isSample(snapshot.member.playerUid)) {
                    HorizontalGap(8)
                    AccentChip(
                        label = stringResource(R.string.coach_sample_chip),
                        accent = CoachAccent.Violet,
                    )
                }
            }
            Text(
                text = rosterSubtitle(snapshot),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        snapshot.totals.averageScore?.let { score ->
            Text(
                text = score.toString(),
                style = MaterialTheme.typography.titleMedium.merge(VbTabularNumerals),
                fontWeight = FontWeight.ExtraBold,
                color = scoreColor(score),
            )
        }
        HorizontalGap(6)
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The line under a roster name.
 *
 * Three cases, in priority order: something is waiting on the coach, the athlete
 * has trained, or they have not. Pending reviews win because that is the only one
 * of the three that is a call to action.
 */
@Composable
private fun rosterSubtitle(snapshot: PlayerSnapshot): String = when {
    snapshot.pendingSubmissions > 0 -> stringResource(
        R.string.coach_roster_pending_reviews,
        snapshot.pendingSubmissions,
    )

    !snapshot.hasTraining -> stringResource(R.string.coach_roster_no_training)

    else -> {
        val sets = snapshot.totals.sessionCount
        val score = snapshot.totals.averageScore
        if (score == null) {
            stringResource(R.string.coach_roster_no_training)
        } else if (sets == 1) {
            stringResource(R.string.coach_roster_summary, sets, score)
        } else {
            stringResource(R.string.coach_roster_summary_plural, sets, score)
        }
    }
}

/**
 * A player who left or was declined. Shown quietly, and not tappable.
 *
 * The only row on the page with no card under it. That is the whole of how it
 * reads as past: everything current on a coach page floats on white, so a name
 * sitting directly on the page is visibly not part of the squad, without needing
 * to be greyed into illegibility or hidden behind a disclosure.
 */
@Composable
private fun PastRow(member: RosterMember, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = CoachGutter, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = member.playerName.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.coach_unnamed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        AccentChip(
            label = stringResource(member.status.displayName),
            accent = CoachAccent.Rust,
        )
    }
}
