package com.volleyballlab.player.feature.coachmode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AssignmentTurnedIn
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.R
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.CoachRepository
import com.volleyballlab.player.domain.repository.SessionDirectory
import com.volleyballlab.player.ui.component.EntranceAnimation
import com.volleyballlab.player.ui.component.GhostLayout
import com.volleyballlab.player.ui.component.GhostLoadingScreen
import com.volleyballlab.player.ui.component.scoreColor
import com.volleyballlab.player.ui.theme.VbTabularNumerals
import com.volleyballlab.player.ui.theme.vbTween
import java.time.LocalTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CoachDashboardViewModel(
    private val coachRepository: CoachRepository,
    private val authRepository: AuthRepository,
    sessionDirectory: SessionDirectory,
) : ViewModel() {

    val state: StateFlow<CoachTeamState> =
        coachTeamState(coachRepository, sessionDirectory).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CoachTeamState(),
        )

    /**
     * Lists this coach in the directory players browse.
     *
     * Called when the dashboard opens rather than at sign-up, because an account
     * that became a coach through the switch in Profile never went through sign-up
     * as one. Idempotent, so running it on every open costs one no-op write.
     */
    fun publishListing() {
        viewModelScope.launch { coachRepository.publishListing() }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CoachDashboardViewModel(
                    coachRepository = appContainer.coachRepository,
                    authRepository = appContainer.authRepository,
                    sessionDirectory = appContainer.sessionDirectory,
                )
            }
        }
    }
}

/**
 * The coach's home screen. Figure 25 of the capstone document.
 *
 * ## What the figure has that this does not, and why
 *
 * The web mockup is a two-pane layout: a review queue on the left, and the
 * selected submission's scorecard and comment box filling the right. On a phone
 * that becomes a list and a detail screen, which is what the queue rows navigate
 * to. Nothing is lost - the same scorecard, analysis and comment box are all on
 * `CoachReviewScreen` - but a coach taps once more to reach them than a coach at a
 * desk would.
 *
 * ## Order of the page
 *
 * Greeting, the four team numbers, the one dark prompt card, the queue, the
 * shortcuts, the caveat.
 *
 * The numbers now sit *above* the outstanding work, which reverses the order this
 * screen used to argue for, and [CoachFeatureCard] is what makes the reversal
 * safe. The old stat block was two stacked rows of tiles and genuinely did push
 * the queue off a short phone; [CoachStatStrip] is one band of four columns, and
 * the dark card immediately under it states the single thing to do about them. A
 * coach still meets the call to action in the first screenful - they just read the
 * scoreboard on the way past it.
 *
 * There is deliberately no route from here to a player's profile. A queue row
 * opens the *submission*, because a coach who has come to this queue is here to
 * answer one clip, and the player page is a detour on the way to it. The roster
 * tab is where you go to read a person.
 */
@Composable
fun CoachDashboardScreen(
    onOpenReview: (playerUid: String, sessionId: String) -> Unit,
    onOpenRoster: () -> Unit,
    onOpenAssign: () -> Unit,
    onOpenAccount: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onOpenQuestions: () -> Unit = {},
) {
    val viewModel: CoachDashboardViewModel = viewModel(factory = CoachDashboardViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Both clock reads are sampled once per composition rather than at the point
    // of use. A clock read inside a list item makes the item's output depend on
    // when it happened to be measured, which is exactly the kind of thing that
    // makes a lazy list flicker - and a greeting resolved during layout can flip
    // from "afternoon" to "evening" while the coach is mid-scroll.
    val now = remember { System.currentTimeMillis() }
    val greeting = remember { greetingRes() }
    var showAllReviews by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.publishListing() }

    if (!state.loaded) {
        GhostLoadingScreen(GhostLayout.Dashboard, contentPadding, modifier)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(key = "hero") {
            EntranceAnimation(index = 0) {
                // CoachHero indents itself, so this one item skips the gutter that
                // every other item below applies.
                CoachHero(
                    lead = stringResource(greeting),
                    // The team's own name once the coach has set one, and the
                    // standing welcome line only until they have. The name is the
                    // more useful of the two the moment it exists, and it is also
                    // the subtitle this header carried before the restyle - the
                    // hero copy is the fallback, not a replacement for it.
                    body = state.profile.teamName.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.coach_hero_body),
                )
            }
        }

        item(key = "focus") {
            EntranceAnimation(index = 2) {
                DashboardFocusCard(
                    state = state,
                    onOpenRoster = onOpenRoster,
                    onOpenReview = onOpenReview,
                    modifier = Modifier.padding(horizontal = CoachGutter),
                )
            }
        }

        item(key = "stats") {
            EntranceAnimation(index = 1) {
                TeamStats(
                    state = state,
                    onOpenRoster = onOpenRoster,
                    modifier = Modifier.padding(horizontal = CoachGutter),
                )
            }
        }

        item(key = "questions") {
            CoachNavigationRow("Player questions", "Answer training questions and set your availability", Icons.Rounded.QuestionAnswer,
                onOpenQuestions, Modifier.padding(horizontal = CoachGutter))
        }

        // The queue is a heading over free-standing rows rather than a
        // CoachSectionCard wrapping them, which is the one place this screen
        // departs from the section-card pattern. A section card has to render its
        // whole body inside a single LazyColumn item, and this list is unbounded -
        // every pending submission the squad has ever sent. That would compose and
        // measure the entire queue on the first frame and give up the per-row keys
        // the recycler and the entrance stagger both rely on. Rows as items keep
        // the list lazy; the cost is that the heading has no icon chip.
        item(key = "queue-title") {
            EntranceAnimation(index = 3) {
                // Preview the first few reviews; the control below expands the
                // same keyed list without leaving the dashboard.
                CoachSectionTitle(text = stringResource(R.string.coach_dash_queue))
            }
        }

        if (state.pendingQueue.isEmpty()) {
            item(key = "queue-empty") {
                EntranceAnimation(index = 4) {
                    val hasTeam = state.hasTeam
                    CoachEmptyCard(
                        title = stringResource(
                            if (hasTeam) {
                                R.string.coach_dash_queue_empty_title
                            } else {
                                R.string.coach_dash_empty_title
                            },
                        ),
                        body = stringResource(
                            if (hasTeam) {
                                R.string.coach_dash_queue_empty_body
                            } else {
                                R.string.coach_dash_empty_body
                            },
                        ),
                        modifier = Modifier.padding(horizontal = CoachGutter),
                        // The two cases are empty for different reasons and take
                        // the tone of the thing that is missing: an unanswered
                        // queue is a queue (indigo), an unbuilt squad is people
                        // (rust).
                        icon = if (hasTeam) Icons.Rounded.Inbox else Icons.Rounded.Group,
                        accent = if (hasTeam) CoachAccent.Indigo else CoachAccent.Rust,
                    )
                }
            }
        } else {
            itemsIndexed(
                items = if (showAllReviews) state.pendingQueue else state.pendingQueue.take(3),
                key = { _, entry -> entry.submission.playerUid + entry.submission.sessionId },
            ) { index, entry ->
                EntranceAnimation(index = index + 4, modifier = Modifier.animateItem(fadeInSpec = vbTween(), placementSpec = vbTween(), fadeOutSpec = vbTween())) {
                    QueueRow(
                        now = now,
                        entry = entry,
                        onClick = {
                            // Unopenable when the athlete has deleted the recording
                            // since submitting it. The row stays so the coach knows
                            // they were asked; there is simply nothing to show.
                            entry.session?.let {
                                onOpenReview(entry.submission.playerUid, it.id)
                            }
                        },
                        modifier = Modifier.padding(horizontal = CoachGutter),
                    )
                }
            }
            if (state.pendingQueue.size > 3) {
                item(key = "more-reviews") {
                    TextButton(
                        onClick = { showAllReviews = !showAllReviews },
                        modifier = Modifier.padding(horizontal = CoachGutter),
                    ) {
                        Text(if (showAllReviews) stringResource(R.string.coach_reviews_show_less)
                            else stringResource(R.string.coach_reviews_show_all, state.pendingQueue.size))
                    }
                }
            }
        }

        item(key = "quick-actions") {
            EntranceAnimation(index = 5) {
                CoachActionRow(
                    actions = listOf(
                        CoachAction(
                            title = stringResource(R.string.coach_dash_requests_action),
                            subtitle = stringResource(R.string.coach_quick_add_sub),
                            icon = Icons.Rounded.PersonAdd,
                            accent = CoachAccent.Rust,
                            onClick = onOpenRoster,
                        ),
                        CoachAction(
                            title = stringResource(R.string.coach_assign_new),
                            subtitle = stringResource(R.string.coach_quick_assign_sub),
                            icon = Icons.Rounded.AssignmentTurnedIn,
                            accent = CoachAccent.Violet,
                            onClick = onOpenAssign,
                        ),
                        CoachAction(
                            title = stringResource(R.string.coach_account_title),
                            subtitle = stringResource(R.string.coach_quick_account_sub),
                            icon = Icons.Rounded.Settings,
                            accent = CoachAccent.Indigo,
                            onClick = onOpenAccount,
                        ),
                    ),
                    modifier = Modifier.padding(horizontal = CoachGutter),
                    title = stringResource(R.string.coach_quick_title),
                )
            }
        }

        // The disclosure that lets the sample team exist at all. An info row rather
        // than a footnote, because unlike the caveat above it is not a limitation to
        // be found when looked for - it is a correction to what the coach has just
        // spent the whole screen reading, and it has to carry the same weight as the
        // numbers it is retracting.
        if (state.isSample) {
            item(key = "sample-note") {
                CoachInfoRow(
                    icon = Icons.Rounded.Info,
                    title = stringResource(R.string.coach_sample_title),
                    body = stringResource(R.string.coach_sample_note),
                    accent = CoachAccent.Violet,
                    modifier = Modifier.padding(horizontal = CoachGutter),
                )
            }
        }
    }
}

/**
 * The four headline numbers, as one strip.
 *
 * Two of the four carry an action link and two deliberately do not. "Players" and
 * "Sets this week" each have a screen behind them worth opening; "To review" is
 * answered by the queue further down this same page, and "Team form" is a reading
 * rather than a place. Putting a link under every figure would cost the two real
 * ones their meaning, and one of them would have had to be a no-op.
 */
@Composable
private fun TeamStats(
    state: CoachTeamState,
    onOpenRoster: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CoachStatStrip(
        stats = listOf(
            CoachStat(
                value = state.summary.activePlayers.toString(),
                label = stringResource(R.string.coach_dash_stat_players),
                icon = Icons.Rounded.Group,
                accent = CoachAccent.Rust,
                actionLabel = stringResource(R.string.coach_dash_view_roster),
                onAction = onOpenRoster,
            ),
            CoachStat(
                value = state.summary.pendingReviews.toString(),
                label = stringResource(R.string.coach_dash_stat_reviews),
                icon = Icons.Rounded.Inbox,
                accent = CoachAccent.Indigo,
            ),
            CoachStat(
                value = state.summary.sessionsThisWeek.toString(),
                label = stringResource(R.string.coach_dash_stat_week),
                icon = Icons.Rounded.CalendarMonth,
                accent = CoachAccent.Green,
                // The roster is where a coach sees what was recorded and by whom.
                // There is no separate sets screen for this to point at, and the
                // per-player histories are one tap past the roster rows.
                actionLabel = stringResource(R.string.coach_dash_view_sets),
                onAction = onOpenRoster,
            ),
            CoachStat(
                // A dash, not a zero: a squad nobody has scored yet has no team
                // form, and printing 0 would report an absence as a failure.
                value = state.summary.averageFormScore?.toString() ?: NO_VALUE,
                label = stringResource(R.string.coach_dash_stat_form),
                icon = Icons.Rounded.Insights,
                accent = CoachAccent.Violet,
            ),
        ),
        modifier = modifier,
    )
}

/**
 * The one dark card on the page: whichever thing is most worth doing right now.
 *
 * ## Why this order
 *
 * Only one of these can show, because a second navy card would cost the first all
 * of its emphasis - see [CoachFeatureCard]. So the four cases are ranked by how
 * badly the coach is blocked rather than by how loud each one is:
 *
 * 1. **An empty roster** outranks everything because nothing else on this screen
 *    can be true yet. There are no sessions to review and no bodies to flag, so a
 *    prompt about either would be pointing at a number that is zero by definition.
 * 2. **A safety watch** outranks the queue because it is about a person rather
 *    than a task. A submission waits patiently; a joint angle outside its band is
 *    the one thing here that gets worse for being left a week. This replaces the
 *    separate warning banner the screen used to draw above the queue - same count,
 *    same wording, same refusal to name a risk, but it now competes for the one
 *    slot instead of stacking a second alarm on top of the first.
 * 3. **A non-empty queue** opens the first available session directly.
 * 4. **All clear** still draws the card rather than dropping it. A page whose
 *    shape changes depending on whether there is bad news teaches the coach to
 *    read the layout instead of the words, and "nothing waiting" is a genuinely
 *    useful thing to have confirmed.
 */
@Composable
private fun DashboardFocusCard(
    state: CoachTeamState,
    onOpenRoster: () -> Unit,
    onOpenReview: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val safety = state.summary.safetyWatchCount
    val pending = state.summary.pendingReviews

    when {
        // `isSample` counts as empty here, and has to. The fixture fills the roster
        // so the screen has something to render, which would otherwise silence the
        // one prompt a coach with no squad actually needs - leaving them looking at
        // two athletes they cannot act on and no way to get a real one.
        state.isSample || state.roster.isEmpty() -> CoachFeatureCard(
            eyebrow = stringResource(R.string.coach_focus_setup_eyebrow),
            title = stringResource(R.string.coach_focus_setup_title),
            body = stringResource(R.string.coach_focus_setup_body),
            modifier = modifier,
            actionLabel = stringResource(R.string.coach_dash_requests_action),
            onAction = onOpenRoster,
        )

        state.pendingRequests.isNotEmpty() -> CoachFeatureCard(
            eyebrow = stringResource(R.string.coach_roster_requests),
            title = stringResource(R.string.coach_dash_requests, state.pendingRequests.size),
            body = stringResource(R.string.coach_requests_focus_body),
            modifier = modifier,
            actionLabel = stringResource(R.string.coach_dash_requests_action),
            onAction = onOpenRoster,
        )

        safety > 0 -> CoachFeatureCard(
            eyebrow = stringResource(R.string.coach_focus_safety_eyebrow),
            // Counts *players*, not flags, because a coach acts on people. The
            // singular and plural are separate strings rather than a plural
            // resource, which is how the rest of the coach shell counts things.
            title = if (safety == 1) {
                stringResource(R.string.coach_dash_safety, safety)
            } else {
                stringResource(R.string.coach_dash_safety_plural, safety)
            },
            body = stringResource(R.string.coach_focus_safety_body),
            modifier = modifier,
            // Through the roster rather than straight at a player: the count is a
            // team-wide total and the card has no single athlete to open.
            actionLabel = stringResource(R.string.nav_coach_roster),
            onAction = onOpenRoster,
        )

        pending > 0 -> CoachFeatureCard(
            eyebrow = stringResource(R.string.coach_focus_review_eyebrow),
            title = if (pending == 1) {
                stringResource(R.string.coach_focus_review_title, pending)
            } else {
                stringResource(R.string.coach_focus_review_title_plural, pending)
            },
            body = stringResource(R.string.coach_focus_review_body),
            modifier = modifier,
            actionLabel = state.pendingQueue.firstOrNull { it.session != null }?.let { stringResource(R.string.coach_review_first) },
            onAction = state.pendingQueue.firstOrNull { it.session != null }?.let { entry ->
                { onOpenReview(entry.submission.playerUid, entry.session!!.id) }
            },
        )

        else -> CoachFeatureCard(
            eyebrow = stringResource(R.string.coach_focus_clear_eyebrow),
            title = stringResource(R.string.coach_focus_clear_title),
            body = stringResource(R.string.coach_focus_clear_body),
            modifier = modifier,
        )
    }
}

/**
 * One waiting submission.
 *
 * Shows the player, the skill, when it arrived and the session's own score. The
 * score is here rather than only on the detail screen so a coach can triage the
 * queue - answer the 48 before the 91 - without opening each one.
 *
 * Leads with the athlete's initials rather than the skill's accent rule, because
 * every row in the restyled coach shell opens with a disc or a chip and because
 * the queue is scanned by *who* sent something. The skill has not been dropped -
 * it is the first half of the line underneath.
 */
@Composable
private fun QueueRow(
    now: Long,
    entry: QueueEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = entry.session
    val score = session?.formScore()

    CoachRowCard(
        modifier = modifier,
        // Null rather than a disabled click, so a row whose recording is gone is
        // inert instead of lighting up under a finger and doing nothing.
        onClick = onClick.takeIf { session != null },
    ) {
        InitialsAvatar(entry.member?.initials())
        HorizontalGap(12)
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.member?.playerName?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.coach_unnamed),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (session == null) {
                    stringResource(R.string.coach_review_missing)
                } else {
                    stringResource(
                        R.string.coach_queue_item,
                        stringResource(session.skill.displayName),
                        relativeDayLabel(
                            entry.submission.submittedAtEpochMs,
                            now,
                        ),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (score != null) {
            Text(
                text = score.toString(),
                style = MaterialTheme.typography.titleMedium.merge(VbTabularNumerals),
                color = scoreColor(score),
            )
        }
        if (session != null) {
            HorizontalGap(6)
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The greeting the hero opens with, chosen off the wall clock.
 *
 * Mirrors the split `HomeScreen` uses rather than importing it. That helper is
 * private to the player feature, and promoting three lines of `when` into a shared
 * utility would create a dependency between two features for the sake of a
 * constant nobody is going to change in one place and not the other.
 *
 * Not a composable, so the caller can `remember` it and pin the greeting for the
 * lifetime of the screen instead of re-reading the clock on every recomposition.
 */
private fun greetingRes(): Int {
    val hour = LocalTime.now().hour
    return when {
        hour < 12 -> R.string.home_greeting_morning
        hour < 18 -> R.string.home_greeting_afternoon
        else -> R.string.home_greeting_evening
    }
}
