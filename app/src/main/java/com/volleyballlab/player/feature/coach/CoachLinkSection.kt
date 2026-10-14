package com.volleyballlab.player.feature.coach

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import com.volleyballlab.player.ui.component.StudentCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Schedule
import com.volleyballlab.player.BuildConfig
import com.volleyballlab.player.R
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.domain.model.AssignmentStatus
import com.volleyballlab.player.domain.model.CoachAssignment
import com.volleyballlab.player.domain.model.CoachLink
import com.volleyballlab.player.domain.model.CoachNote
import com.volleyballlab.player.domain.model.CoachPreview
import com.volleyballlab.player.domain.model.ProFeature
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.SessionSubmission
import com.volleyballlab.player.domain.model.UserPlan
import com.volleyballlab.player.domain.repository.CoachRepository
import com.volleyballlab.player.domain.repository.JoinFailure
import com.volleyballlab.player.domain.repository.JoinOutcome
import com.volleyballlab.player.domain.repository.PlanRepository
import com.volleyballlab.player.domain.repository.SessionRepository
import com.volleyballlab.player.ui.component.SkillAccentRule
import com.volleyballlab.player.ui.component.GhostLoadingCard
import com.volleyballlab.player.ui.theme.VbButtonShape
import com.volleyballlab.player.ui.theme.VbTabularNumerals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The athlete's side of the coach relationship.
 *
 * @param latestSession what the submit button would send. Null when the athlete
 *   has never recorded anything, which is why the button explains itself instead
 *   of simply being disabled.
 */
data class CoachLinkState(
    /** Free locks this whole section behind the upsell. Conflict 6, made real. */
    val plan: UserPlan = UserPlan.Free,
    val link: CoachLink? = null,
    /** Every coach who could be asked. Only read once the account is Pro. */
    val directory: List<CoachPreview> = emptyList(),
    val notes: List<CoachNote> = emptyList(),
    val assignments: List<CoachAssignment> = emptyList(),
    val submissions: List<SessionSubmission> = emptyList(),
    val latestSession: SessionResult? = null,
    val loaded: Boolean = false,
) {
    val isLinked: Boolean get() = link?.isActive == true

    /** True when the newest session is already sitting in the coach's queue. */
    val latestIsSubmitted: Boolean
        get() = latestSession != null && submissions.any {
            it.sessionId == latestSession.id
        }
}

/**
 * Where the player is in the flow of Figures 17, 20, 21 and 22.
 *
 * A sealed type rather than a pile of nullable fields on [CoachLinkState], because
 * these are genuinely exclusive: a player is browsing, or reading one coach's
 * profile, or waiting, or connected. Booleans would let two of those be true at
 * once and the screen would have to pick.
 */
sealed interface CoachJoinStep {
    /** Figure 20: the directory, nobody chosen yet. */
    data object Browse : CoachJoinStep

    /** Figure 21: one coach chosen, showing who they are about to ask. */
    data class Review(val preview: CoachPreview) : CoachJoinStep
}

class CoachLinkViewModel(
    private val coachRepository: CoachRepository,
    private val planRepository: PlanRepository,
    sessionRepository: SessionRepository,
) : ViewModel() {

    val state: StateFlow<CoachLinkState> = combine(
        combine(coachRepository.myCoach, coachRepository.coachDirectory, ::Pair),
        combine(planRepository.plan, coachRepository.notesForMe, ::Pair),
        coachRepository.assignmentsForMe,
        coachRepository.mySubmissions,
        sessionRepository.sessions,
    ) { (link, directory), (plan, notes), assignments, submissions, sessions ->
        CoachLinkState(
            plan = plan,
            link = link,
            directory = directory,
            notes = notes,
            assignments = assignments,
            submissions = submissions,
            latestSession = sessions.maxByOrNull { it.startedAtEpochMs },
            loaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CoachLinkState(),
    )

    private val _step = MutableStateFlow<CoachJoinStep>(CoachJoinStep.Browse)
    val step: StateFlow<CoachJoinStep> = _step.asStateFlow()

    private val _error = MutableStateFlow<Int?>(null)
    val error: StateFlow<Int?> = _error.asStateFlow()
    val sending = MutableStateFlow(false)

    fun clearError() {
        _error.value = null
    }

    /** Step one: open one coach's profile. Writes nothing, so backing out is free. */
    fun choose(preview: CoachPreview) {
        _error.value = null
        _step.value = CoachJoinStep.Review(preview)
    }

    /**
     * Step two: actually ask. Only the coach can move it on from here.
     *
     * The Pro check sits here rather than only in the UI because this is the one
     * choke point every path to a request goes through. Firestore rules independently enforce
     * server entitlement; this check also ensures a screen that forgets to
     * gate cannot silently create a membership.
     */
    fun sendRequest(coachUid: String) {
        viewModelScope.launch {
            if (!planRepository.plan.first().isPro) {
                _error.value = JoinFailure.NotPro.message
                return@launch
            }
            when (val outcome = coachRepository.requestJoin(coachUid)) {
                is JoinOutcome.Success -> {
                    _error.value = null
                    // Back to the directory, which is not what the player will see:
                    // `myCoach` now emits a pending link and the card switches to the
                    // waiting state. Resetting means that if they later cancel, they
                    // land on the list rather than on a stale profile.
                    _step.value = CoachJoinStep.Browse
                }
                is JoinOutcome.Failure -> {
                    _error.value = outcome.reason.message
                    _step.value = CoachJoinStep.Browse
                }
            }
        }
    }

    /** Back out of one coach's profile to the list. */
    fun backToBrowse() {
        _step.value = CoachJoinStep.Browse
        _error.value = null
    }

    fun leave() {
        viewModelScope.launch { coachRepository.leaveCoach() }
    }

    fun submit(sessionId: String) {
        if (sending.value) return
        sending.value = true
        viewModelScope.launch {
            try { coachRepository.submitSession(sessionId) }
            finally { sending.value = false }
        }
    }

    fun markDone(assignmentId: String) {
        viewModelScope.launch {
            coachRepository.updateAssignmentStatus(assignmentId, AssignmentStatus.Completed)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CoachLinkViewModel(
                    coachRepository = appContainer.coachRepository,
                    planRepository = appContainer.planRepository,
                    sessionRepository = appContainer.sessionRepository,
                )
            }
        }

        /** Why a lookup or a request failed, in words the athlete can act on. */
        private val JoinFailure.message: Int
            get() = when (this) {
                JoinFailure.OwnCode -> R.string.coach_link_error_own
                JoinFailure.UnknownCoach -> R.string.coach_link_error_unknown
                JoinFailure.AlreadyLinked -> R.string.coach_link_error_linked
                JoinFailure.NoSession -> R.string.coach_link_error_session
                JoinFailure.NotPro -> R.string.coach_link_error_notpro
                JoinFailure.Unavailable -> R.string.coach_link_error_unavailable
            }
    }
}

/**
 * The card at the top of the athlete's Coach tab. Figures 17 and 20 to 22.
 *
 * Five states, in the order the branch below tests them:
 *
 * - **Waiting** - the request has gone and the coach has not answered. Figure 22.
 * - **On a team** - who the coach is, a way to send the last session for review,
 *   the drills they were set, the notes that came back, and the way out.
 * - **Free** - the upsell, because coach connection is what Pro buys. Figure 17.
 * - **Reading one coach's profile** - who they are about to ask, and the rules of
 *   the arrangement, before anything is written. Figure 21.
 * - **Browsing** - every coach who could be asked. Figure 20.
 *
 * Review is asynchronous rather than a chat, and the copy says so in more than one
 * of these states deliberately: an athlete who thinks a coach is watching live will
 * wait for a reply that is not coming.
 *
 * ## The leave button is not decoration
 *
 * The athlete chose this coach, but choosing is not a commitment they should have
 * to keep - a coach can see every session they submit, and Leave team is the one
 * control that closes that off again. Do not bury it.
 *
 * The rest of the Coach tab - the on-device advice this app derives from the
 * athlete's own sessions - is unchanged and sits below this. The two are different
 * kinds of coaching and the screen does not blur them: one is arithmetic over this
 * phone's recordings, the other is a person who wrote something.
 */
@Composable
fun CoachLinkSection(modifier: Modifier = Modifier, onOpenAssignment: (String) -> Unit = {}) {
    val viewModel: CoachLinkViewModel = viewModel(factory = CoachLinkViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val step by viewModel.step.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()

    if (!state.loaded) {
        GhostLoadingCard(modifier)
        return
    }
    val link = state.link

    var showUpgrade by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        when {
            // A live link outranks everything, including the plan. An athlete whose
            // Pro lapsed still has a coach and still needs to see them - locking a
            // relationship that already exists behind a paywall would be taking
            // something away rather than gating something new.
            link != null && link.isPending -> PendingCard(
                link = link,
                onCancel = viewModel::leave,
            )

            link != null -> LinkedCard(
                state = state,
                onSubmit = viewModel::submit,
                onLeave = viewModel::leave,
                onOpenAssignment = onOpenAssignment,
                sending = sending,
            )

            // Figure 17: the upsell. Everything below this branch is Pro-only.
            !state.plan.isPro -> ProUpsellCard(onOpenUpgrade = { showUpgrade = true })

            step is CoachJoinStep.Review -> ReviewCard(
                preview = (step as CoachJoinStep.Review).preview,
                error = error,
                onSend = viewModel::sendRequest,
                onBack = viewModel::backToBrowse,
            )

            else -> DirectoryCard(
                coaches = state.directory,
                error = error,
                onChoose = viewModel::choose,
            )
        }
    }

    if (showUpgrade) {
        androidx.compose.material3.AlertDialog(onDismissRequest = { showUpgrade = false },
            title = { Text("Pro access") },
            text = { com.volleyballlab.player.feature.profile.ProPlanCard() },
            confirmButton = { TextButton(onClick = { showUpgrade = false }) { Text("Close") } })
    }
}

/**
 * Figure 17: the locked state.
 *
 * Names the two features that are genuinely gated rather than listing the whole Pro
 * tier, because this card's job is to explain *why this tab is empty* - and three
 * of the five Pro claims in the document have nothing to do with coaching and are
 * not implemented anyway. The full list, with that distinction marked, is in the
 * sheet behind the button.
 */
@Composable
private fun ProUpsellCard(onOpenUpgrade: () -> Unit) {
    LinkSurface {
        Text(
            text = stringResource(R.string.pro_upsell_eyebrow).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.pro_upsell_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.pro_upsell_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(14.dp))
        ProFeature.Live.forEach { feature ->
            LockedFeatureRow(feature)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onOpenUpgrade,
            shape = VbButtonShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.pro_upsell_cta))
        }
    }
}

@Composable
private fun LockedFeatureRow(feature: ProFeature) {
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Rounded.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = stringResource(feature.title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(feature.body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Figure 20: the coaches a player can ask. */
@Composable
private fun DirectoryCard(
    coaches: List<CoachPreview>,
    error: Int?,
    onChoose: (CoachPreview) -> Unit,
) {
    LinkSurface {
        Text(
            text = stringResource(R.string.coach_link_none_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.coach_link_none_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(14.dp))
        if (coaches.isEmpty()) {
            Text(
                text = stringResource(R.string.coach_link_directory_empty_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.coach_link_directory_empty_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The one trap in a fresh install: the very first tap being the athlete
            // fixture, which lands here on an empty list that looks like a broken
            // feature rather than an unmet precondition. Says what to do about it.
            if (BuildConfig.DEBUG) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.coach_link_directory_empty_debug),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@LinkSurface
        }

        // A plain Column rather than a LazyColumn: this card is already inside the
        // Coach tab's scrolling list, and nesting a second scroller in the same
        // direction is both a measurement crash and a thing nobody can use.
        coaches.forEach { coach ->
            CoachDirectoryRow(coach, onClick = { onChoose(coach) })
        }
    }
}

@Composable
private fun CoachDirectoryRow(coach: CoachPreview, onClick: () -> Unit) {
    val fallback = stringResource(R.string.coach_coach_default)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = coach.displayName(fallback),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = listOfNotNull(
                    coach.profile.specialisation?.let { stringResource(it.displayName) },
                    if (coach.rosterSize > 0) {
                        stringResource(R.string.coach_link_roster_size, coach.rosterSize)
                    } else {
                        stringResource(R.string.coach_link_roster_empty)
                    },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Figure 21: who you are about to ask, and what agreeing means.
 *
 * The rules line is not decoration. This is the moment the athlete decides to let
 * someone else read their training, and it is the only screen in the flow where
 * they can still walk away - so it says plainly that the coach approves the
 * request and that only submitted sessions are shared.
 */
@Composable
private fun ReviewCard(
    preview: CoachPreview,
    error: Int?,
    onSend: (String) -> Unit,
    onBack: () -> Unit,
) {
    LinkSurface {
        Text(
            text = stringResource(R.string.coach_link_preview_eyebrow).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = preview.displayName(stringResource(R.string.coach_coach_default)),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // Only the details the coach actually filled in. A row of "not set"
        // placeholders would make a real coach look like an empty account.
        val details = listOfNotNull(
            preview.profile.teamName.takeIf { it.isNotBlank() },
            preview.profile.specialisation?.let {
                stringResource(R.string.coach_link_preview_specialisation, stringResource(it.displayName))
            },
            preview.profile.yearsExperience?.let {
                stringResource(R.string.coach_link_preview_experience, it)
            },
        )
        details.forEach { detail ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.coach_link_preview_rules),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onSend(preview.coachUid) },
            shape = VbButtonShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.coach_link_send))
        }
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onBack, shape = VbButtonShape) {
            Text(stringResource(R.string.coach_link_back))
        }
    }
}

/** The wait. Nothing can be submitted until the coach answers. */
@Composable
private fun PendingCard(link: CoachLink, onCancel: () -> Unit) {
    LinkSurface {
        Text(
            text = stringResource(
                R.string.coach_link_pending_title,
                link.coachName.ifBlank {
                    link.teamName.ifBlank { stringResource(R.string.coach_coach_default) }
                },
            ),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.coach_link_pending_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = onCancel,
            shape = VbButtonShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.coach_link_cancel))
        }
    }
}

@Composable
private fun LinkedCard(
    state: CoachLinkState,
    onSubmit: (String) -> Unit,
    onLeave: () -> Unit,
    onOpenAssignment: (String) -> Unit,
    sending: Boolean,
) {
    val link = state.link ?: return

    LinkSurface {
        Text(
            text = stringResource(
                R.string.coach_link_active_title,
                link.coachName.ifBlank {
                    link.teamName.ifBlank { stringResource(R.string.coach_coach_default) }
                },
            ),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(14.dp))
        when {
            state.latestSession == null -> Text(
                text = stringResource(R.string.coach_link_nothing_to_send),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.latestIsSubmitted -> Text(
                text = stringResource(R.string.coach_link_awaiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> Button(
                enabled = !sending,
                onClick = { onSubmit(state.latestSession.id) },
                shape = VbButtonShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (sending) "Sending recording and feedback…" else stringResource(R.string.coach_link_submit))
            }
        }

        if (state.latestSession != null && !state.latestIsSubmitted) {
            Text(if (state.latestSession.videoPath != null)
                "Sending shares your video, skeleton, and error markers privately with this coach."
                else "This session sends a form summary without a video.", style = MaterialTheme.typography.bodySmall)
        }

        if (state.assignments.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.coach_link_assignments),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            state.assignments.forEach { assignment ->
                Spacer(Modifier.height(10.dp))
                AssignmentRow(assignment, onOpen = { onOpenAssignment(assignment.id) })
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.coach_link_notes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        if (state.notes.isEmpty()) {
            Text(
                text = stringResource(R.string.coach_link_no_notes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Newest first, capped: a coach who has been writing for a season
            // should not push the rest of the tab off the bottom of the screen.
            state.notes.take(MAX_NOTES_SHOWN).forEach { note ->
                Text(
                    text = note.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        TextButton(onClick = onLeave, shape = VbButtonShape) {
            Text(
                text = stringResource(R.string.coach_link_leave),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AssignmentRow(assignment: CoachAssignment, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SkillAccentRule(skill = assignment.skill, height = 40)
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
            if (assignment.summary.isNotBlank()) {
                Text(
                    text = assignment.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onOpen) {
            Text("View task")
        }
    }
}

@Composable
private fun LinkSurface(content: @Composable () -> Unit) {
    StudentCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(20.dp)) { content() }
    }
}

private const val MAX_NOTES_SHOWN = 5
