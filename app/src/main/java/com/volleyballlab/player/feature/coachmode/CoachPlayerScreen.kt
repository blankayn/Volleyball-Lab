package com.volleyballlab.player.feature.coachmode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AssignmentTurnedIn
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.volleyballlab.player.domain.model.CoachAssignment
import com.volleyballlab.player.domain.model.PlayerSnapshot
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.SkillStanding
import com.volleyballlab.player.domain.model.formatTrend
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.CoachRepository
import com.volleyballlab.player.domain.repository.SessionDirectory
import com.volleyballlab.player.ui.component.EntranceAnimation
import com.volleyballlab.player.ui.component.GhostLayout
import com.volleyballlab.player.ui.component.GhostLoadingScreen
import com.volleyballlab.player.ui.component.SkillAccentRule
import com.volleyballlab.player.ui.component.scoreColor
import com.volleyballlab.player.ui.theme.VbTabularNumerals
import com.volleyballlab.player.ui.theme.vbColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CoachPlayerViewModel(
    private val coachRepository: CoachRepository,
    sessionDirectory: SessionDirectory,
    authRepository: AuthRepository,
) : ViewModel() {

    val state: StateFlow<CoachTeamState> =
        coachTeamState(coachRepository, sessionDirectory).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CoachTeamState(),
        )

    fun remove(playerUid: String) {
        viewModelScope.launch { coachRepository.removeFromRoster(playerUid) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CoachPlayerViewModel(
                    coachRepository = appContainer.coachRepository,
                    sessionDirectory = appContainer.sessionDirectory,
                    authRepository = appContainer.authRepository,
                )
            }
        }
    }
}

/** Player overview, shared-session history and training plan as separate views. */
@Composable
fun CoachPlayerScreen(
    playerUid: String,
    onBack: () -> Unit,
    onOpenReview: (playerUid: String, sessionId: String) -> Unit,
    onAssignDrill: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val viewModel: CoachPlayerViewModel = viewModel(factory = CoachPlayerViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now = remember { System.currentTimeMillis() }

    if (!state.loaded) {
        GhostLoadingScreen(GhostLayout.Detail, contentPadding, modifier)
        return
    }
    val snapshot = state.snapshotOf(playerUid)
    var tab by rememberSaveable(playerUid) { mutableStateOf(0) }
    var confirmRemoval by rememberSaveable(playerUid) { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "header") {
            CoachDetailHeader(
                title = snapshot?.member?.playerName?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.coach_unnamed),
                subtitle = snapshot?.member?.position?.let { stringResource(it.displayName) },
                onBack = onBack,
                leading = {
                    InitialsAvatar(
                        initials = snapshot?.member?.initials(),
                        diameter = 40,
                        accent = CoachAccent.Rust,
                    )
                },
                // This screen is the one most likely to be screenshotted for a
                // report, and it is a whole page of numbers about a named person.
                // The badge has to be in the header, where it cannot be cropped out
                // of the frame that shows the trend and the safety panel.
                trailing = {
                    if (SampleTeam.isSample(playerUid)) {
                        AccentChip(
                            label = stringResource(R.string.coach_sample_chip),
                            accent = CoachAccent.Violet,
                        )
                    }
                },
            )
        }

        if (snapshot == null) {
            // Reachable by backing into a player who was removed on another screen
            // while this one was in the back stack. An empty card beats a crash and
            // beats a blank page that looks like a failed load.
            item(key = "missing") {
                CoachEmptyCard(
                    title = stringResource(R.string.coach_roster_empty_title),
                    body = stringResource(R.string.coach_roster_empty_body),
                    modifier = Modifier.padding(horizontal = CoachGutter),
                    icon = Icons.Rounded.PersonAdd,
                    accent = CoachAccent.Rust,
                )
            }
            return@LazyColumn
        }

        item(key = "player-tabs") {
            CoachFilterTabs(listOf("Overview", "Sessions", "Plan"), tab, { tab = it }, Modifier.padding(horizontal = CoachGutter))
        }
        if (tab == 0) item(key = "stats") {
            EntranceAnimation(index = 1) {
                PlayerStatStrip(snapshot, Modifier.padding(horizontal = CoachGutter))
            }
        }

        if (tab == 0 && !snapshot.hasTraining) {
            item(key = "no-training") {
                EntranceAnimation(index = 2) {
                    CoachEmptyCard(
                        title = stringResource(R.string.coach_player_no_training_title),
                        body = stringResource(R.string.coach_player_no_training_body),
                        modifier = Modifier.padding(horizontal = CoachGutter),
                        icon = Icons.Rounded.CalendarMonth,
                        accent = CoachAccent.Green,
                        actionLabel = stringResource(R.string.coach_player_new_assignment),
                        onAction = { onAssignDrill(playerUid) },
                    )
                }
            }
        } else if (tab == 0) {
            snapshot.weakestCue?.let { cue ->
                item(key = "focus") {
                    val cueLabel = stringResource(cue.label)
                    // Still qualified by the skill it was measured in: the same cue
                    // name means different things across skills, so dropping the
                    // qualifier would lose information rather than chrome.
                    val skillLabel = snapshot.weakestCueSkill
                        ?.let { stringResource(it.displayName) }
                    EntranceAnimation(index = 2) {
                        // The screen's single dark card. The cue is the one thing the
                        // coach is being asked to act on, and inverting one card against
                        // a page of white ones is what makes that legible without a
                        // badge or a louder colour - which only works while it stays the
                        // only dark card here.
                        CoachFeatureCard(
                            eyebrow = stringResource(R.string.coach_player_focus),
                            title = if (skillLabel == null) {
                                cueLabel
                            } else {
                                "$skillLabel · $cueLabel"
                            },
                            body = stringResource(cue.coachingTip),
                            modifier = Modifier.padding(horizontal = CoachGutter),
                        )
                    }
                }
            }

            item(key = "breakdown") {
                EntranceAnimation(index = 3) {
                    CoachSectionCard(
                        title = stringResource(R.string.coach_player_breakdown),
                        icon = Icons.Rounded.BarChart,
                        accent = CoachAccent.Violet,
                        modifier = Modifier.padding(horizontal = CoachGutter),
                    ) {
                        snapshot.standings.forEach { standing ->
                            StandingRow(standing)
                        }
                    }
                }
            }
        }

        if (tab != 1) item(key = "actions") {
            CoachPillButton(stringResource(R.string.coach_player_new_assignment), { onAssignDrill(playerUid) },
                Modifier.fillMaxWidth().padding(horizontal = CoachGutter))
        }

        val assignments = state.assignmentsFor(playerUid)
        if (tab == 2) item(key = "assignments") {
            CoachSectionCard(
                title = stringResource(R.string.coach_player_assignments),
                icon = Icons.Rounded.AssignmentTurnedIn,
                accent = CoachAccent.Violet,
                modifier = Modifier.padding(horizontal = CoachGutter),
            ) {
                if (assignments.isEmpty()) {
                    CoachDashedEmpty(
                        icon = Icons.Rounded.AssignmentTurnedIn,
                        title = stringResource(R.string.coach_player_no_assignments),
                        body = stringResource(R.string.coach_assign_empty_body),
                        accent = CoachAccent.Violet,
                    )
                } else {
                    assignments.forEachIndexed { index, assignment ->
                        if (index > 0) Spacer(Modifier.height(6.dp))
                        AssignmentRow(assignment = assignment, now = now)
                    }
                }
            }
        }

        if (tab == 1 && snapshot.recentSessions.isNotEmpty()) {
            item(key = "history") {
                CoachSectionCard(
                    title = stringResource(R.string.coach_player_history),
                    icon = Icons.Rounded.CalendarMonth,
                    accent = CoachAccent.Green,
                    modifier = Modifier.padding(horizontal = CoachGutter),
                ) {
                    snapshot.recentSessions.forEach { session ->
                        SessionRow(
                            session = session,
                            pending = state.queue.any {
                                it.submission.sessionId == session.id && it.submission.isPending
                            },
                            onClick = { onOpenReview(playerUid, session.id) },
                        )
                    }
                }
            }
        }

        if (tab == 1 && snapshot.recentSessions.isEmpty()) item(key = "empty-sessions") {
            CoachEmptyCard("No shared sessions yet", "The player’s shared sessions will appear here when they send one for review.",
                icon = Icons.Rounded.CalendarMonth, modifier = Modifier.padding(horizontal = CoachGutter))
        }
        if (tab == 2 && snapshot.member.isActive) item(key = "manage-player") {
            TextButton(onClick = { confirmRemoval = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = CoachGutter)) {
                Text("Remove from team", color = MaterialTheme.colorScheme.error)
            }
        }
        if (tab == 0) item(key = "footnote") {
            CoachFootnote(
                text = stringResource(R.string.coach_player_focus_source),
                modifier = Modifier.padding(horizontal = CoachGutter + 4.dp, vertical = 6.dp),
            )
        }
    }
    if (confirmRemoval) AlertDialog(
        onDismissRequest = { confirmRemoval = false }, title = { Text("Remove this player?") },
        text = { Text("They will need a new approved request to send you sessions again. Previous feedback stays available.") },
        confirmButton = { TextButton(onClick = { confirmRemoval = false; viewModel.remove(playerUid); onBack() }) { Text("Remove player", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("Keep player") } },
    )
}

/**
 * The four headline figures, as one strip.
 *
 * One card rather than four, because these are four readings of the same athlete;
 * the tones only separate the *kinds* of reading, per the shell's fixed assignment
 * - green for training that happened, violet for the written score, indigo for
 * what is still moving, rust for the athlete themselves.
 */
@Composable
private fun PlayerStatStrip(snapshot: PlayerSnapshot, modifier: Modifier = Modifier) {
    CoachStatStrip(
        modifier = modifier,
        stats = listOf(
            CoachStat(
                value = snapshot.totals.sessionCount.toString(),
                label = stringResource(R.string.coach_player_sets),
                icon = Icons.Rounded.CalendarMonth,
                accent = CoachAccent.Green,
            ),
            CoachStat(
                value = snapshot.totals.averageScore?.toString() ?: NO_VALUE,
                label = stringResource(R.string.coach_player_form),
                icon = Icons.Rounded.Insights,
                accent = CoachAccent.Violet,
            ),
            CoachStat(
                // Null until there are enough scored sessions on both sides of the
                // thirty-day midpoint. A trend from one session is not a trend.
                value = snapshot.trendDelta?.let(::formatTrend) ?: NO_VALUE,
                label = stringResource(R.string.coach_player_trend),
                icon = Icons.AutoMirrored.Rounded.TrendingUp,
                accent = CoachAccent.Indigo,
            ),
            CoachStat(
                value = snapshot.consistencyOutOfHundred?.toString() ?: NO_VALUE,
                label = stringResource(R.string.coach_player_consistency),
                icon = Icons.Rounded.Bolt,
                accent = CoachAccent.Rust,
            ),
        ),
    )
}

/**
 * One skill's standing, with a bar so a squad's shape is readable at a glance.
 *
 * Keeps the skill's own accent rule rather than taking an icon chip: inside the
 * breakdown card every row is the same *kind* of thing, so the useful distinction
 * is which skill it is, and that colour is already fixed app-wide by
 * [SkillAccentRule].
 */
@Composable
private fun StandingRow(standing: SkillStanding, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkillAccentRule(skill = standing.skill, height = 26)
        HorizontalGap(12)
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(standing.skill.displayName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(5.dp))
            LinearProgressIndicator(
                progress = { (standing.meanScore ?: 0) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = scoreColor(standing.meanScore ?: 0),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
        HorizontalGap(12)
        Text(
            text = standing.meanScore?.toString() ?: NO_VALUE,
            style = MaterialTheme.typography.labelLarge.merge(VbTabularNumerals),
            color = standing.meanScore
                ?.let { scoreColor(it) }
                ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One assigned drill.
 *
 * An overdue drill keeps the error tone rather than one of the four accents. The
 * accents categorise; the error colour is the app's one way of saying something
 * has gone wrong, and a violet "overdue" would read as just another status.
 */
@Composable
private fun AssignmentRow(
    assignment: CoachAssignment,
    now: Long,
    modifier: Modifier = Modifier,
) {
    val overdue = assignment.isOverdue(now)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkillAccentRule(skill = assignment.skill, height = 34)
        HorizontalGap(12)
        Column(Modifier.weight(1f)) {
            Text(
                text = assignment.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    R.string.coach_assign_target,
                    assignment.targetSets,
                    assignment.targetReps,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalGap(8)
        if (overdue) {
            StatusChip(
                label = stringResource(R.string.coach_assign_overdue),
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            AccentChip(
                label = stringResource(assignment.status.displayName),
                accent = CoachAccent.Violet,
            )
        }
    }
}

/**
 * One recorded session, tappable through to its review.
 *
 * The dot is a submission still waiting on the coach. Kept as a dot rather than a
 * chip because it is a property of the row's *destination* - open it and you land
 * in the review that is outstanding - and a labelled chip here would compete with
 * the score, which is what the row is actually about.
 */
@Composable
private fun SessionRow(
    session: SessionResult,
    pending: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val score = session.formScore()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CoachInnerShape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkillAccentRule(skill = session.skill, height = 34)
        HorizontalGap(12)
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(session.skill.displayName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = sessionTimestampLabel(session.startedAtEpochMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (pending) {
            Box(
                Modifier
                    .height(8.dp)
                    .width(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.vbColors.warning),
            )
            HorizontalGap(10)
        }
        if (score != null) {
            Text(
                text = score.toString(),
                style = MaterialTheme.typography.labelLarge.merge(VbTabularNumerals),
                color = scoreColor(score),
            )
        }
        HorizontalGap(4)
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
