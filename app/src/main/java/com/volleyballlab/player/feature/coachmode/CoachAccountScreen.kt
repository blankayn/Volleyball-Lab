package com.volleyballlab.player.feature.coachmode

import com.volleyballlab.player.feature.profile.AccountSection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Person
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.feature.profile.AccountSecurityCard
import com.volleyballlab.player.R
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.domain.model.CoachProfile
import com.volleyballlab.player.domain.model.CoachSpecialisation
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.AuthUser
import com.volleyballlab.player.domain.repository.CoachRepository
import com.volleyballlab.player.ui.component.EntranceAnimation
import com.volleyballlab.player.ui.component.GhostLayout
import com.volleyballlab.player.ui.component.GhostLoadingScreen
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CoachAccountUiState(
    val profile: CoachProfile = CoachProfile.Empty,
    val account: AuthUser? = null,
    val loaded: Boolean = false,
    val identity: com.volleyballlab.player.domain.model.AthleteProfile = com.volleyballlab.player.domain.model.AthleteProfile.Anonymous,
)

class CoachAccountViewModel(
    private val coachRepository: CoachRepository,
    private val authRepository: AuthRepository,
    private val profileRepository: com.volleyballlab.player.domain.repository.ProfileRepository,
    private val beforeSignOut: suspend () -> Unit = {},
) : ViewModel() {

    val uiState: StateFlow<CoachAccountUiState> = combine(
        coachRepository.coachProfile,
        authRepository.currentUser,
        profileRepository.profile,
    ) { profile, account, identity ->
        CoachAccountUiState(profile.copy(name = identity.name.ifBlank { profile.name }), account, loaded = true, identity = identity)
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
            val identity = profileRepository.current()
            val current = coachRepository.coachProfile.first()
            coachRepository.saveCoachProfile(transform(current).let {
                if (identity.hasName) it.copy(name = identity.name.take(CoachProfile.MAX_NAME_LENGTH)) else it
            })
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


    fun signOut(onSignedOut: () -> Unit) {
        viewModelScope.launch {
            beforeSignOut()
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
                    profileRepository = appContainer.profileRepository,
                    beforeSignOut = appContainer.coachPresenceReporter::beforeSignOut,
                )
            }
        }
    }
}

/** Account identity and settings grouped into Profile, Preferences and Security. */
@Composable
fun CoachAccountScreen(
    onSignedOut: () -> Unit, contentPadding: PaddingValues, modifier: Modifier = Modifier,
) {
    val model: CoachAccountViewModel = viewModel(factory = CoachAccountViewModel.Factory)
    val state by model.uiState.collectAsStateWithLifecycle()
    if (!state.loaded) { GhostLoadingScreen(GhostLayout.Profile, contentPadding, modifier); return }
    com.volleyballlab.player.feature.profile.AccountMenuScreen(coach = true,
        onLogout = { model.signOut(onSignedOut) }, contentPadding = contentPadding, modifier = modifier,
        banner = {
            CoachWorkspaceIdentity(state.identity.name.ifBlank { state.profile.name }.ifBlank { "Coach" },
                state.profile.teamName.ifBlank { "Your coaching workspace" }, state.profile.initials(), "Coach profile",
                portrait = state.identity.photoPath?.let { path -> { com.volleyballlab.player.feature.profile.ProfilePhoto(path, Modifier.fillMaxSize()) } })
        },
        sectionContent = { section -> CoachAccountSectionContent(section, state, model, onSignedOut) },
    )
}

@Composable
private fun CoachAccountSectionContent(section: AccountSection, state: CoachAccountUiState, model: CoachAccountViewModel, onSignedOut: () -> Unit) {
    var editing by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val profile = state.profile
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (section) {
            AccountSection.Personal -> {
                com.volleyballlab.player.feature.profile.PersonalInformationSummary(state.identity.name.ifBlank { profile.name }.ifBlank { "Coach" }, state.account?.email) { editing = !editing }
                if (editing) com.volleyballlab.player.feature.profile.AccountIdentityEditor(onDone = { editing = false })
                com.volleyballlab.player.feature.profile.ProfileTrainingTools(section = com.volleyballlab.player.feature.profile.ProfileToolSection.Photo, publishCoachPhoto = true)
                com.volleyballlab.player.feature.profile.ProfileSyncCard(Modifier.fillMaxWidth())
            }
            AccountSection.Team -> {
                AccountSectionCard("Your team") {
                    AccountField(stringResource(R.string.coach_account_team_name), profile.teamName, model::onTeam)
                    Spacer(Modifier.height(14.dp))
                    AccountField(stringResource(R.string.coach_account_organisation), profile.organisation, model::onOrganisation)
                }
                AccountSectionCard("Coaching background") {
                    Text(stringResource(R.string.coach_account_specialisation), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CoachSpecialisation.All.forEach { candidate ->
                            FilterChip(profile.specialisation == candidate, { model.onSpecialisation(candidate) },
                                label = { Text(stringResource(candidate.displayName)) }, shape = CoachPillShape)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    AccountField(stringResource(R.string.coach_account_experience), profile.yearsExperience?.toString().orEmpty(), model::onExperience, KeyboardType.Number)
                    Spacer(Modifier.height(14.dp))
                    AccountField(stringResource(R.string.coach_account_licence), profile.licenceNumber, model::onLicence)
                }
                com.volleyballlab.player.feature.profile.ProfileSyncCard(Modifier.fillMaxWidth())
            }
            AccountSection.Settings -> com.volleyballlab.player.feature.profile.InlineAccountSettings(coach = true) { selected ->
                CoachAccountSectionContent(selected, state, model, onSignedOut)
            }
            AccountSection.Notifications -> {
                AccountSectionCard("Training notifications") {
                    SwitchRow(stringResource(R.string.coach_account_notify_submissions), profile.notifySubmissions, model::onNotifySubmissions)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                    SwitchRow(stringResource(R.string.coach_account_notify_digest), profile.notifyWeeklyDigest, model::onNotifyDigest)
                }
                com.volleyballlab.player.feature.profile.NotificationInboxEntry()
            }
            AccountSection.Security -> AccountSecurityCard()
            AccountSection.Privacy -> {
                com.volleyballlab.player.feature.profile.PrivacyCard()
                com.volleyballlab.player.feature.profile.DeleteAccountCard(onSignedOut)
            }
            AccountSection.Help -> com.volleyballlab.player.feature.profile.AccountHelpContent(coach = true)
            else -> Unit
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

@Composable
private fun AccountSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CoachCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .42f)),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}
