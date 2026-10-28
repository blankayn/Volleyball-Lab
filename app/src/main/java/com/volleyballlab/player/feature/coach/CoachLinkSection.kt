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
    val sessions: List<SessionResult> = emptyList(),
    val loaded: Boolean = false,
) {
    val isLinked: Boolean get() = link?.isActive == true

    /** True when the newest session is already sitting in the coach's queue. */
    val latestIsSubmitted: Boolean
        get() = latestSession != null && submissions.any {
            it.sessionId == latestSession.id && it.coachUid == link?.coachUid
        }
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
            sessions = sessions.sortedByDescending { it.startedAtEpochMs },
            loaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CoachLinkState(),
    )

    private val _error = MutableStateFlow<Int?>(null)
    val error: StateFlow<Int?> = _error.asStateFlow()
    val sending = MutableStateFlow(false)
    val submitError = MutableStateFlow<String?>(null)
    val leaving = MutableStateFlow(false)
    val leaveError = MutableStateFlow<String?>(null)

    fun clearError() {
        _error.value = null
    }

    /**
     * Step two: actually ask. Only the coach can move it on from here.
     *
     * The Pro check sits here rather than only in the UI because this is the one
     * choke point every path to a request goes through. Firestore rules independently enforce
     * server entitlement; this check also ensures a screen that forgets to
     * gate cannot silently create a membership.
     */
    private val _requesting = MutableStateFlow(false)
    val requesting = _requesting.asStateFlow()
    private val _requestedCoach = MutableStateFlow<String?>(null)
    val requestedCoach = _requestedCoach.asStateFlow()

    fun sendRequest(coachUid: String) {
        if (_requesting.value) return
        _requesting.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                if (!planRepository.plan.first().isPro) {
                    _error.value = JoinFailure.NotPro.message
                    return@launch
                }
                when (val outcome = coachRepository.requestJoin(coachUid)) {
                    is JoinOutcome.Success -> _requestedCoach.value = coachUid
                    is JoinOutcome.Failure -> _error.value = outcome.reason.message
                }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                _error.value = R.string.coach_link_error_unavailable
            } finally { _requesting.value = false }
        }
    }

    fun leave() {
        if (leaving.value) return
        leaving.value = true
        leaveError.value = null
        viewModelScope.launch {
            try { coachRepository.leaveCoach() }
            catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                leaveError.value = "Could not end the connection. Check your connection and try again."
            } finally { leaving.value = false }
        }
    }

    fun submit(sessionId: String) {
        if (sending.value) return
        sending.value = true
        submitError.value = null
        viewModelScope.launch {
            try { coachRepository.submitSession(sessionId) }
            catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                submitError.value = "Your session was not confirmed as sent. Check your connection and try again."
            }
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
 * Four states, in the order the branch below tests them:
 *
 * - **Waiting** - the request has gone and the coach has not answered. Figure 22.
 * - **On a team** - who the coach is, a way to send the last session for review,
 *   the drills they were set, the notes that came back, and the way out.
 * - **Free** - the upsell, because coach connection is what Pro buys. Figure 17.
 * - **Ready to browse** - opens the dedicated coach directory and profile pages.
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
fun CoachLinkSection(
    modifier: Modifier = Modifier,
    onOpenAssignment: (String) -> Unit = {},
    onBrowseCoaches: () -> Unit = {},
    onMyCoach: () -> Unit = {},
) {
    val viewModel: CoachLinkViewModel = viewModel(factory = CoachLinkViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
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

            link != null -> LinkSurface {
                Text(link.coachName.ifBlank { "Your coach" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (link.teamName.isNotBlank()) Text(link.teamName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text("Your assignments, feedback history, and coaching questions are in your coach workspace.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onMyCoach, modifier = Modifier.fillMaxWidth()) { Text("Open My coach") }
            }

            // Figure 17: the upsell. Everything below this branch is Pro-only.
            !state.plan.isPro -> ProUpsellCard(onOpenUpgrade = { showUpgrade = true })

            else -> LinkSurface {
                Text("Your next step: find a coach", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Explore coaches, read their profiles, and choose who to train with.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBrowseCoaches, modifier = Modifier.fillMaxWidth(), shape = VbButtonShape) {
                    Text("Find a coach")
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                }
            }
        }
    }

    if (link?.isActive != true && (state.submissions.isNotEmpty() || state.notes.isNotEmpty())) {
        TextButton(onClick = onMyCoach, modifier = modifier.fillMaxWidth()) { Text("View coaching history") }
    }

    if (showUpgrade) {
        androidx.compose.material3.AlertDialog(onDismissRequest = { showUpgrade = false },
            title = { Text("Pro access") },
            text = { com.volleyballlab.player.feature.profile.ProPlanCard(onBrowseCoaches = {
                showUpgrade = false
                onBrowseCoaches()
            }) },
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
private fun LinkSurface(content: @Composable () -> Unit) {
    StudentCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(20.dp)) { content() }
    }
}
