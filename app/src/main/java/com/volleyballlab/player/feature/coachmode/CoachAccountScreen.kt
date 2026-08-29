package com.volleyballlab.player.feature.coachmode

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
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
import com.volleyballlab.player.domain.model.CoachProfile
import com.volleyballlab.player.domain.model.CoachSpecialisation
import com.volleyballlab.player.domain.model.DemoAccounts
import com.volleyballlab.player.domain.model.UserRole
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.AuthUser
import com.volleyballlab.player.domain.repository.CoachRepository
import com.volleyballlab.player.domain.repository.RoleRepository
import com.volleyballlab.player.feature.auth.DemoAccountButtons
import com.volleyballlab.player.ui.component.EntranceAnimation
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CoachAccountUiState(
    val profile: CoachProfile = CoachProfile.Empty,
    val account: AuthUser? = null,
)

class CoachAccountViewModel(
    private val coachRepository: CoachRepository,
    private val authRepository: AuthRepository,
    private val roleRepository: RoleRepository,
) : ViewModel() {

    val uiState: StateFlow<CoachAccountUiState> = combine(
        coachRepository.coachProfile,
        authRepository.currentUser,
    ) { profile, account ->
        CoachAccountUiState(profile, account)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CoachAccountUiState(),
    )

    init {
        seedNameFromAccount()
    }

    /**
     * Copies the account's display name into the coach profile, if there is
     * nothing there already.
     *
     * A coach who signed up gave their name on that form; without this it would
     * sit on the Firebase account and in the athlete profile while their own
     * Account tab called them "Your coach". Guarded, so a name the coach has since
     * edited here is never overwritten by whatever the account was registered
     * under - the same bargain `AuthViewModel.mirrorAccountNameIntoProfile` makes.
     */
    private fun seedNameFromAccount() {
        viewModelScope.launch {
            val existing = coachRepository.coachProfile.first()
            if (existing.hasName) return@launch
            val accountName = authRepository.currentUser.first()?.displayName.orEmpty()
            if (accountName.isBlank()) return@launch
            coachRepository.saveCoachProfile(existing.copy(name = accountName))
        }
    }

    /**
     * Edits are applied against the last emitted profile, not against a copy the
     * screen holds, so two quick edits cannot have the second silently revert the
     * first. Same rule as the athlete's Profile tab.
     */
    private fun edit(transform: (CoachProfile) -> CoachProfile) {
        viewModelScope.launch {
            coachRepository.saveCoachProfile(transform(coachRepository.coachProfile.first()))
        }
    }

    fun onName(value: String) = edit { it.copy(name = value.take(CoachProfile.MAX_NAME_LENGTH)) }

    fun onTeam(value: String) =
        edit { it.copy(teamName = value.take(CoachProfile.MAX_TEAM_LENGTH)) }

    fun onOrganisation(value: String) =
        edit { it.copy(organisation = value.take(CoachProfile.MAX_TEAM_LENGTH)) }

    fun onLicence(value: String) = edit { it.copy(licenceNumber = value.take(40)) }

    /** Tapping the selected chip clears it: the same gesture sets and unsets. */
    fun onSpecialisation(value: CoachSpecialisation) = edit {
        it.copy(specialisation = if (it.specialisation == value) null else value)
    }

    /**
     * Years coaching, from raw keyboard input.
     *
     * A blank field means "not stated", not zero, so it clears the value rather
     * than storing 0 - a coach who has not filled this in has not claimed to be in
     * their first year.
     */
    fun onExperience(raw: String) = edit { profile ->
        val digits = raw.filter(Char::isDigit).take(2)
        profile.copy(
            yearsExperience = digits.toIntOrNull()
                ?.coerceIn(0, CoachProfile.MAX_EXPERIENCE_YEARS),
        )
    }

    fun onNotifySubmissions(value: Boolean) = edit { it.copy(notifySubmissions = value) }
    fun onNotifyDigest(value: Boolean) = edit { it.copy(notifyWeeklyDigest = value) }
    fun onNotifySafety(value: Boolean) = edit { it.copy(notifySafetyAlerts = value) }

    /**
     * Hands this account back to the player shell.
     *
     * Nothing is deleted: the roster, the assignments and the notes all stay under
     * this uid, so switching back restores the team intact. That matters because
     * the switch is also the recovery path for an account that landed in the wrong
     * shell on a fresh install - see [UserRole].
     */
    fun switchToPlayer(onSwitched: () -> Unit) {
        viewModelScope.launch {
            roleRepository.setRole(UserRole.Player)
            onSwitched()
        }
    }

    fun signOut(onSignedOut: () -> Unit) {
        viewModelScope.launch {
            authRepository.signOut()
            onSignedOut()
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CoachAccountViewModel(
                    coachRepository = appContainer.coachRepository,
                    authRepository = appContainer.authRepository,
                    roleRepository = appContainer.roleRepository,
                )
            }
        }
    }
}

/**
 * The coach's own account. Figure 29 of the capstone document.
 *
 * Four sections, in the order the figure lists them: who they are and what team
 * they run, their credentials, their notification switches, and the way out.
 *
 * ## The identity block is a card, not a hero
 *
 * [CoachHero] is the shell's two-tone welcome and it needs a second line to put in
 * the primary colour - here that could only be the team name, which is already one
 * of the fields directly below and does not want announcing twice. The initials
 * disc says "this is you" more directly than a display-size greeting does on the
 * one tab that is about the account rather than about the team, so the top of this
 * screen is a plain [CoachCard]: avatar, name, and the address the session belongs
 * to. Everything below it is a titled section card, so the page reads as one stack
 * of settings rather than a dashboard with settings appended.
 *
 * ## Accent assignment
 *
 * Rust on the team block and on the way out, because both are about *people* - the
 * coach and the roster they own. Violet on credentials, which are paperwork. Indigo
 * on notifications, which are the queue of things that would otherwise wait for the
 * coach to come looking.
 *
 * ## The notification switches record a preference and send nothing
 *
 * The figure describes alerts for submissions, weekly summaries and biomechanical
 * risk warnings. Delivering any of those needs the Notification entity in Table 18
 * and a scheduler, neither of which this build has. The switches persist, and a
 * line under them says plainly that nothing is sent yet. Silent switches that look
 * like they work are worse than absent ones.
 */
@Composable
fun CoachAccountScreen(
    onSwitchedToPlayer: () -> Unit,
    onSignedOut: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val viewModel: CoachAccountViewModel = viewModel(factory = CoachAccountViewModel.Factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val profile = state.profile

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "identity") {
            EntranceAnimation(index = 0) {
                CoachCard(Modifier.padding(horizontal = CoachGutter)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InitialsAvatar(
                            initials = profile.initials(),
                            diameter = 56,
                            accent = CoachAccent.Rust,
                        )
                        HorizontalGap(14)
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = profile.name.ifBlank {
                                    stringResource(R.string.coach_coach_default)
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            state.account?.email?.takeIf { it.isNotBlank() }?.let { email ->
                                Text(
                                    text = stringResource(R.string.profile_signed_in_as, email),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "team") {
            EntranceAnimation(index = 1) {
                CoachSectionCard(
                    title = stringResource(R.string.coach_account_team),
                    icon = Icons.Rounded.Group,
                    accent = CoachAccent.Rust,
                    modifier = Modifier.padding(horizontal = CoachGutter),
                ) {
                    AccountField(
                        label = stringResource(R.string.coach_account_name),
                        value = profile.name,
                        onValueChange = viewModel::onName,
                    )
                    Spacer(Modifier.height(12.dp))
                    AccountField(
                        label = stringResource(R.string.coach_account_team_name),
                        value = profile.teamName,
                        onValueChange = viewModel::onTeam,
                    )
                    Spacer(Modifier.height(12.dp))
                    AccountField(
                        label = stringResource(R.string.coach_account_organisation),
                        value = profile.organisation,
                        onValueChange = viewModel::onOrganisation,
                    )
                }
            }
        }

        item(key = "credentials") {
            EntranceAnimation(index = 2) {
                CoachSectionCard(
                    title = stringResource(R.string.coach_account_credentials),
                    icon = Icons.Rounded.WorkspacePremium,
                    accent = CoachAccent.Violet,
                    modifier = Modifier.padding(horizontal = CoachGutter),
                ) {
                    Text(
                        text = stringResource(R.string.coach_account_specialisation),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    // Scrolled rather than wrapped: the chips are one ranked list of
                    // specialisations and a flow layout would break it into ragged
                    // rows whose length changes with the locale.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoachSpecialisation.All.forEach { candidate ->
                            FilterChip(
                                selected = profile.specialisation == candidate,
                                onClick = { viewModel.onSpecialisation(candidate) },
                                label = { Text(stringResource(candidate.displayName)) },
                                shape = CoachPillShape,
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    AccountField(
                        label = stringResource(R.string.coach_account_experience),
                        value = profile.yearsExperience?.toString().orEmpty(),
                        onValueChange = viewModel::onExperience,
                        keyboardType = KeyboardType.Number,
                    )

                    Spacer(Modifier.height(12.dp))
                    AccountField(
                        label = stringResource(R.string.coach_account_licence),
                        value = profile.licenceNumber,
                        onValueChange = viewModel::onLicence,
                    )
                    Spacer(Modifier.height(10.dp))
                    // Inside the card, under the field it qualifies: "nothing here is
                    // verified" is a caveat about this licence box specifically, and
                    // floating it between cards would leave it attached to nothing.
                    CoachFootnote(stringResource(R.string.coach_account_licence_note))
                }
            }
        }

        item(key = "notifications") {
            EntranceAnimation(index = 3) {
                CoachSectionCard(
                    title = stringResource(R.string.coach_account_notifications),
                    icon = Icons.Rounded.Notifications,
                    accent = CoachAccent.Indigo,
                    modifier = Modifier.padding(horizontal = CoachGutter),
                ) {
                    SwitchRow(
                        label = stringResource(R.string.coach_account_notify_submissions),
                        checked = profile.notifySubmissions,
                        onCheckedChange = viewModel::onNotifySubmissions,
                    )
                    SwitchRow(
                        label = stringResource(R.string.coach_account_notify_digest),
                        checked = profile.notifyWeeklyDigest,
                        onCheckedChange = viewModel::onNotifyDigest,
                    )
                    SwitchRow(
                        label = stringResource(R.string.coach_account_notify_safety),
                        checked = profile.notifySafetyAlerts,
                        onCheckedChange = viewModel::onNotifySafety,
                    )
                    Spacer(Modifier.height(10.dp))
                    // The load-bearing sentence of this whole section, and it stays in
                    // the same card as the switches it is about. A coach who reads only
                    // the switches must not be able to miss it.
                    CoachFootnote(stringResource(R.string.coach_account_notify_note))
                }
            }
        }

        item(key = "exit") {
            EntranceAnimation(index = 4) {
                // Given the same titled-card treatment as the sections above rather
                // than a bare card, so the way out is a destination on the page
                // instead of two loose buttons after the last setting. The sign-out
                // is the only text button on the screen and the only thing in the
                // error colour, which is what keeps it from being tapped by accident
                // while still being findable.
                CoachSectionCard(
                    title = stringResource(R.string.coach_account_switch_role),
                    icon = Icons.Rounded.SwapHoriz,
                    accent = CoachAccent.Rust,
                    modifier = Modifier.padding(horizontal = CoachGutter),
                ) {
                    Text(
                        text = stringResource(R.string.coach_account_switch_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = { viewModel.switchToPlayer(onSwitchedToPlayer) },
                        shape = CoachPillShape,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.coach_account_switch_role))
                    }
                    // The other half of the switch on the athlete's Profile tab, and
                    // the reason both exist: a coach account with nobody on it
                    // demonstrates nothing, so showing this feature means becoming
                    // the player who asks and then coming back here to answer.
                    // Debug builds only - see DemoAccounts.
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
                            accounts = listOf(DemoAccounts.Player),
                            shape = CoachPillShape,
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { viewModel.signOut(onSignedOut) },
                        shape = CoachPillShape,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.profile_sign_out))
                    }
                }
            }
        }
    }
}

/**
 * A labelled text field, with the label set above the box rather than inside it.
 *
 * Material's floating label is the usual answer, but it animates out of the way as
 * soon as a field has content, and a returning coach sees this card with every box
 * already filled. A static label keeps each row readable at a glance and keeps the
 * vertical rhythm identical whether a field is empty or not.
 */
@Composable
private fun AccountField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            // The card's own inner radius, so a field reads as part of the card it
            // sits in rather than as a control dropped on top of it.
            shape = CoachInnerShape,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
