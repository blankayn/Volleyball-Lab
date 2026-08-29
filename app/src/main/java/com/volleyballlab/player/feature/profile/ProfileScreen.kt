package com.volleyballlab.player.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.BuildConfig
import com.volleyballlab.player.R
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.domain.model.AthleteProfile
import com.volleyballlab.player.domain.model.CourtPosition
import com.volleyballlab.player.domain.model.DemoAccounts
import com.volleyballlab.player.domain.model.DominantHand
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.model.TrainingTotals
import com.volleyballlab.player.domain.model.UserRole
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.AuthUser
import com.volleyballlab.player.domain.repository.ProfileRepository
import com.volleyballlab.player.domain.repository.RoleRepository
import com.volleyballlab.player.domain.repository.SessionRepository
import com.volleyballlab.player.feature.auth.DemoAccountButtons
import com.volleyballlab.player.ui.component.EntranceAnimation
import com.volleyballlab.player.ui.component.VbSectionHeader
import com.volleyballlab.player.ui.component.scoreColor
import com.volleyballlab.player.ui.theme.VbButtonShape
import com.volleyballlab.player.ui.theme.VbTabularNumerals
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProfileUiState(
    val profile: AthleteProfile = AthleteProfile.Anonymous,
    val totals: TrainingTotals = TrainingTotals.Empty,
    val account: AuthUser? = null,
)

class ProfileViewModel(
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository,
    private val roleRepository: RoleRepository,
    sessionRepository: SessionRepository,
) : ViewModel() {

    val uiState: StateFlow<ProfileUiState> = combine(
        profileRepository.profile,
        sessionRepository.sessions,
        authRepository.currentUser,
    ) { profile, sessions, account ->
        ProfileUiState(profile, TrainingTotals.from(sessions), account)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProfileUiState(),
    )

    /**
     * Edits are applied against the last emitted profile rather than against a
     * copy the screen has been holding, so two quick edits cannot have the second
     * one silently revert the first.
     */
    private fun edit(transform: (AthleteProfile) -> AthleteProfile) {
        viewModelScope.launch {
            profileRepository.save(transform(uiState.value.profile))
        }
    }

    fun onNameChanged(name: String) = edit { it.copy(name = name) }

    /** Tapping the selected chip clears it: the same gesture sets and unsets. */
    fun onPositionSelected(position: CourtPosition) = edit {
        it.copy(position = if (it.position == position) null else position)
    }

    fun onHandSelected(hand: DominantHand) = edit {
        it.copy(dominantHand = if (it.dominantHand == hand) null else hand)
    }

    /**
     * Turns this account into a coach account.
     *
     * Nothing is deleted and nothing is navigated: the role is written, the role
     * flow emits, and `VolleyballLabApp` builds the coach shell in place of this
     * one. The athlete's sessions, profile and assessment answers all stay under
     * the same uid, so switching back restores the player app exactly as it was.
     *
     * This is also the recovery path for a coach whose account landed in the
     * player shell on a fresh install, which is the case [UserRole] documents:
     * the role lives on this device until there is a `User_Role` column to read.
     */
    fun onSwitchToCoach() {
        viewModelScope.launch { roleRepository.setRole(UserRole.Coach) }
    }

    /**
     * Signs out, leaving everything on the device alone.
     *
     * Sessions, profile and assessment answers all stay: they were recorded on this
     * phone, they belong to whoever is holding it, and quietly wiping an athlete's
     * training history because they signed out would be destroying data they never
     * asked to lose. Clearing local data is a separate, explicit action and does not
     * exist yet.
     */
    fun onSignOut(onSignedOut: () -> Unit) {
        viewModelScope.launch {
            authRepository.signOut()
            onSignedOut()
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ProfileViewModel(
                    profileRepository = appContainer.profileRepository,
                    authRepository = appContainer.authRepository,
                    roleRepository = appContainer.roleRepository,
                    sessionRepository = appContainer.sessionRepository,
                )
            }
        }
    }
}

/**
 * The Profile tab.
 *
 * Two halves with different sources of truth, and the screen keeps them visually
 * apart for that reason: the top is what the athlete told the app, editable in
 * place, and the bottom is what the app measured, which is read-only because
 * training totals are not an opinion.
 */
@Composable
fun ProfileScreen(
    onRetakeAssessment: () -> Unit,
    onSignedOut: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val viewModel: ProfileViewModel = viewModel(factory = ProfileViewModel.Factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editingName by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            EntranceAnimation(index = 0) {
                IdentityCard(
                    profile = state.profile,
                    onEditName = { editingName = true },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }

        item {
            EntranceAnimation(index = 1) {
                VbSectionHeader(
                    text = stringResource(R.string.profile_section_play),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }

        item {
            EntranceAnimation(index = 2) {
                PlayingDetailsCard(
                    profile = state.profile,
                    onPositionSelected = viewModel::onPositionSelected,
                    onHandSelected = viewModel::onHandSelected,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }

        item {
            EntranceAnimation(index = 3) {
                VbSectionHeader(
                    text = stringResource(R.string.profile_section_totals),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }

        item {
            EntranceAnimation(index = 4) {
                TotalsCard(state.totals, Modifier.padding(horizontal = 20.dp))
            }
        }

        item {
            EntranceAnimation(index = 5) {
                PrivacyCard(Modifier.padding(horizontal = 20.dp))
            }
        }

        item {
            EntranceAnimation(index = 6) {
                VbSectionHeader(
                    text = stringResource(R.string.profile_section_account),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }

        item {
            EntranceAnimation(index = 7) {
                AccountCard(
                    account = state.account,
                    onRetakeAssessment = onRetakeAssessment,
                    // No navigation callback: writing the role swaps the whole
                    // shell, so this graph is about to be torn down. See the note
                    // in `VolleyballLabApp`.
                    onSwitchToCoach = viewModel::onSwitchToCoach,
                    onSignOut = { viewModel.onSignOut(onSignedOut) },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }

    if (editingName) {
        NameDialog(
            initialName = state.profile.name,
            onConfirm = {
                viewModel.onNameChanged(it)
                editingName = false
            },
            onDismiss = { editingName = false },
        )
    }
}

/**
 * The account section: who is signed in, a way to redo onboarding, and a way out.
 */
@Composable
private fun AccountCard(
    account: AuthUser?,
    onRetakeAssessment: () -> Unit,
    onSwitchToCoach: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(18.dp)) {
            if (account != null) {
                Text(
                    text = stringResource(R.string.profile_signed_in_as, account.email),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
            }

            Text(
                text = stringResource(R.string.profile_retake_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onRetakeAssessment,
                shape = VbButtonShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.profile_retake_assessment))
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(
                    R.string.profile_role_title,
                    stringResource(UserRole.Player.displayName).lowercase(),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.profile_switch_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onSwitchToCoach,
                shape = VbButtonShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.profile_switch_to_coach))
            }

            // Switching *accounts*, which is a different thing from the role switch
            // directly above and is kept visually apart for that reason: the button
            // above changes what this account is, and the one below changes which
            // account you are. Both would otherwise read as "become a coach".
            //
            // This is what makes the coach relationship demonstrable at all. Showing
            // it means being the athlete who asks and then the coach who answers,
            // back and forth, and every trip through the login form is a chance to
            // fat-finger a password in front of a panel. Debug builds only.
            if (BuildConfig.DEBUG) {
                Spacer(Modifier.height(18.dp))
                Text(
                    text = stringResource(R.string.demo_accounts_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.demo_switch_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                DemoAccountButtons(
                    accounts = listOf(DemoAccounts.Coach),
                    shape = VbButtonShape,
                )
            }

            Spacer(Modifier.height(10.dp))
            TextButton(
                onClick = onSignOut,
                shape = VbButtonShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.profile_sign_out),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun IdentityCard(
    profile: AthleteProfile,
    onEditName: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            val initials = profile.initials()
            if (initials == null) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            } else {
                Text(
                    text = initials,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = profile.name.takeIf { profile.hasName }
                    ?: stringResource(R.string.profile_name_unset),
                style = MaterialTheme.typography.headlineSmall,
                color = if (profile.hasName) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = profile.position
                    ?.let { stringResource(it.displayName) }
                    ?: stringResource(R.string.profile_position_unset),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(onClick = onEditName) {
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = stringResource(R.string.profile_edit_name),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PlayingDetailsCard(
    profile: AthleteProfile,
    onPositionSelected: (CourtPosition) -> Unit,
    onHandSelected: (DominantHand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.profile_position_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CourtPosition.entries.forEach { position ->
                    FilterChip(
                        selected = profile.position == position,
                        onClick = { onPositionSelected(position) },
                        label = { Text(stringResource(position.displayName)) },
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.profile_hand_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DominantHand.entries.forEach { hand ->
                    FilterChip(
                        selected = profile.dominantHand == hand,
                        onClick = { onHandSelected(hand) },
                        label = { Text(stringResource(hand.displayName)) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.profile_details_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TotalsCard(totals: TrainingTotals, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(18.dp)) {
            TotalRow(
                label = stringResource(R.string.profile_total_sessions),
                value = totals.sessionCount.toString(),
            )
            TotalRow(
                label = stringResource(R.string.profile_total_reps),
                value = totals.repCount.toString(),
            )
            TotalRow(
                label = stringResource(R.string.profile_total_time),
                value = formatDuration(totals.totalMillis),
            )
            TotalRow(
                label = stringResource(R.string.profile_total_best),
                value = totals.bestScore?.toString() ?: NO_VALUE,
                valueColor = totals.bestScore?.let { scoreColor(it) },
            )
            TotalRow(
                label = stringResource(R.string.profile_total_most_trained),
                value = totals.mostTrained
                    ?.let { stringResource(it.displayName) }
                    ?: NO_VALUE,
            )
        }
    }
}

@Composable
private fun TotalRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.merge(VbTabularNumerals),
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The privacy claim, on the screen rather than only in a store listing.
 *
 * It is a real commitment the app keeps - inference runs on-device and no frame
 * leaves the phone - and the one place an athlete filming themselves in a gym is
 * likely to go looking for it is their own profile.
 */
@Composable
private fun PrivacyCard(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.profile_privacy_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.profile_privacy_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NameDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_edit_name)) },
        text = {
            OutlinedTextField(
                value = draft,
                // Truncated on input rather than validated on submit: a length
                // limit the athlete only discovers when they press Save is a worse
                // experience than one that simply stops accepting characters.
                onValueChange = { draft = it.take(AthleteProfile.MAX_NAME_LENGTH) },
                singleLine = true,
                label = { Text(stringResource(R.string.profile_name_label)) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }) {
                Text(stringResource(R.string.profile_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.capture_cancel))
            }
        },
    )
}

private const val NO_VALUE = "—"

/**
 * Total training time as hours and minutes, or minutes alone under an hour.
 *
 * Seconds are dropped deliberately. This is a cumulative figure across every
 * session ever recorded, and at that scale a seconds column is noise that changes
 * while you are reading it.
 */
private fun formatDuration(totalMillis: Long): String {
    if (totalMillis <= 0L) return NO_VALUE
    val totalMinutes = TimeUnit.MILLISECONDS.toSeconds(totalMillis)
        .let { it / 60.0 }
        .roundToInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
