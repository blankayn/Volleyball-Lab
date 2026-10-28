package com.volleyballlab.player.feature.profile

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SportsVolleyball
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.R
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.domain.model.AthleteProfile
import com.volleyballlab.player.domain.model.CourtPosition
import com.volleyballlab.player.domain.model.DominantHand
import com.volleyballlab.player.domain.model.TrainingTotals
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.AuthUser
import com.volleyballlab.player.domain.repository.CoachingPreferences
import com.volleyballlab.player.domain.repository.CoachingPreferencesRepository
import com.volleyballlab.player.domain.repository.ProfileRepository
import com.volleyballlab.player.domain.repository.SessionRepository
import com.volleyballlab.player.ui.component.EntranceAnimation
import com.volleyballlab.player.ui.component.StudentCard
import com.volleyballlab.player.ui.component.CourtCardArtwork
import com.volleyballlab.player.ui.component.studentBackground
import com.volleyballlab.player.ui.component.GhostLayout
import com.volleyballlab.player.ui.component.GhostLoadingScreen
import com.volleyballlab.player.ui.component.scoreColor
import com.volleyballlab.player.ui.coaching.CoachingAudio
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
    val coachingPreferences: CoachingPreferences = CoachingPreferences(),
    val loaded: Boolean = false,
    val syncError: String? = null,
)

class ProfileViewModel(
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository,
    sessionRepository: SessionRepository,
    private val coachingPreferencesRepository: CoachingPreferencesRepository,
) : ViewModel() {

    val uiState: StateFlow<ProfileUiState> = combine(
        profileRepository.profile,
        sessionRepository.sessions,
        authRepository.currentUser,
        coachingPreferencesRepository.preferences,
        profileRepository.syncError,
    ) { profile, sessions, account, coachingPreferences, syncError ->
        ProfileUiState(profile, TrainingTotals.from(sessions), account, coachingPreferences, loaded = true, syncError = syncError)
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
            profileRepository.update(transform)
        }
    }

    fun onNameChanged(name: String) = edit {
        if (name.trim() == it.name) it else it.copy(name = name, firstName = "", lastName = "")
    }

    /** Tapping the selected chip clears it: the same gesture sets and unsets. */
    fun onPositionSelected(position: CourtPosition) = edit {
        it.copy(position = if (it.position == position) null else position)
    }

    fun onHandSelected(hand: DominantHand) = edit {
        it.copy(dominantHand = if (it.dominantHand == hand) null else hand)
    }

    fun onVoiceChanged(enabled: Boolean) {
        viewModelScope.launch { coachingPreferencesRepository.setVoiceEnabled(enabled) }
    }

    fun onHapticsChanged(enabled: Boolean) {
        viewModelScope.launch { coachingPreferencesRepository.setHapticsEnabled(enabled) }
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
                    sessionRepository = appContainer.sessionRepository,
                    coachingPreferencesRepository = appContainer.coachingPreferencesRepository,
                )
            }
        }
    }
}

/** The athlete's identity, training record, and account controls. */
@Composable
fun ProfileScreen(
    onSignedOut: () -> Unit, contentPadding: PaddingValues, modifier: Modifier = Modifier,
    onBrowseCoaches: () -> Unit = {},
) {
    val model: ProfileViewModel = viewModel(factory = ProfileViewModel.Factory)
    val state by model.uiState.collectAsStateWithLifecycle()
    if (!state.loaded) { GhostLoadingScreen(GhostLayout.Profile, contentPadding, modifier); return }
    AccountMenuScreen(coach = false, onLogout = { model.onSignOut(onSignedOut) }, contentPadding = contentPadding, modifier = modifier,
        banner = {
            com.volleyballlab.player.feature.coachmode.CoachWorkspaceIdentity(
                name = state.profile.name.ifBlank { state.account?.displayName.orEmpty() }.ifBlank { "Player" },
                subtitle = state.profile.position?.let { stringResource(it.displayName) } ?: "Your training workspace",
                initials = state.profile.initials(), eyebrow = "Student profile",
                portrait = state.profile.photoPath?.let { path -> { ProfilePhoto(path, Modifier.fillMaxSize()) } },
            )
        },
        sectionContent = { section -> StudentAccountSectionContent(section, state, model, onBrowseCoaches, onSignedOut) },
    )
}

@Composable
private fun StudentAccountSectionContent(
    section: AccountSection, state: ProfileUiState, model: ProfileViewModel,
    onBrowseCoaches: () -> Unit, onSignedOut: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (section) {
            AccountSection.Personal -> {
                PersonalInformationSummary(state.profile.name.ifBlank { state.account?.displayName.orEmpty() }.ifBlank { "Player" }, state.account?.email) { editing = !editing }
                if (editing) AccountIdentityEditor(onDone = { editing = false })
                ProfileTrainingTools(section = ProfileToolSection.Photo)
                PlayerDetailsCard(state.profile, model::onPositionSelected, model::onHandSelected)
                ProfileSyncCard(Modifier.fillMaxWidth())
                TotalsCard(state.totals)
            }
            AccountSection.Plan -> ProPlanCard(onBrowseCoaches = onBrowseCoaches)
            AccountSection.Settings -> InlineAccountSettings(coach = false) { selected ->
                StudentAccountSectionContent(selected, state, model, onBrowseCoaches, onSignedOut)
            }
            AccountSection.Training -> {
                CoachingPreferencesCard(state.coachingPreferences, model::onVoiceChanged, model::onHapticsChanged)
                ProfileTrainingTools(section = ProfileToolSection.Training)
            }
            AccountSection.Security -> AccountSecurityCard()
            AccountSection.Notifications -> NotificationInboxEntry()
            AccountSection.Privacy -> {
                PrivacyCard()
                DeleteAccountCard(onSignedOut)
            }
            AccountSection.Help -> AccountHelpContent(coach = false)
            else -> Unit
        }
    }
}

@Composable
private fun PlayerDetailsCard(profile: AthleteProfile, onPosition: (CourtPosition) -> Unit, onHand: (DominantHand) -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = AccountMenuSurface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Player details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Court position", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CourtPosition.entries.forEach { position ->
                    androidx.compose.material3.FilterChip(profile.position == position, { onPosition(position) },
                        label = { Text(stringResource(position.displayName)) }, modifier = Modifier.heightIn(min = 44.dp))
                }
            }
            Text("Dominant hand", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DominantHand.entries.forEach { hand ->
                    androidx.compose.material3.FilterChip(profile.dominantHand == hand, { onHand(hand) },
                        label = { Text(stringResource(hand.displayName)) }, modifier = Modifier.heightIn(min = 44.dp))
                }
            }
        }
    }
}

@Composable
private fun CoachingPreferencesCard(
    preferences: CoachingPreferences,
    onVoiceChanged: (Boolean) -> Unit,
    onHapticsChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    StudentCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = AccountMenuSurface,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Live coaching", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            CoachingPreferenceRow(
                title = "Voice coaching",
                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                description = "Receive spoken guidance during training.",
                checked = preferences.voiceEnabled,
                onCheckedChange = onVoiceChanged,
            )
            CoachingPreferenceRow(
                title = "Haptic feedback",
                icon = Icons.Rounded.Vibration,
                description = "Confirm good reps with a short vibration.",
                checked = preferences.hapticsEnabled,
                onCheckedChange = onHapticsChanged,
            )
            TextButton(onClick = {
                try {
                    context.startActivity(CoachingAudio.settingsIntent())
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "Speech settings aren't available on this device.", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Speech settings") }
            Text(
                "Voice coaching uses an installed offline English voice. Install a voice in Speech settings if speech is unavailable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CoachingPreferenceRow(
    title: String,
    icon: ImageVector,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFFFF1E6)) {
            Icon(icon, contentDescription = null, tint = ProfileOrange, modifier = Modifier.padding(10.dp).size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TotalsCard(totals: TrainingTotals, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.profile_section_totals), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("ALL TIME", style = MaterialTheme.typography.labelSmall, color = ProfileOrange, letterSpacing = 1.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TrainingMetricTile(stringResource(R.string.profile_total_sessions), totals.sessionCount.toString(), Modifier.weight(1f))
            TrainingMetricTile(stringResource(R.string.profile_total_reps), totals.repCount.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TrainingMetricTile(stringResource(R.string.profile_total_time), formatDuration(totals.totalMillis), Modifier.weight(1f))
            TrainingMetricTile(
                "Best form estimate",
                totals.bestScore?.toString() ?: NO_VALUE,
                Modifier.weight(1f),
                valueColor = totals.bestScore?.let { scoreColor(it) } ?: ProfileOrange,
            )
        }
        totals.mostTrained?.let { skill ->
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SportsVolleyball, contentDescription = null, tint = ProfileOrange, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.profile_total_most_trained), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(skill.displayName), style = MaterialTheme.typography.labelLarge, color = Color(0xFF743507))
                }
            }
        }
    }
}

@Composable
private fun TrainingMetricTile(label: String, value: String, modifier: Modifier, valueColor: Color = ProfileOrange) {
    StudentCard(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall.merge(VbTabularNumerals), fontWeight = FontWeight.Bold, color = valueColor, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Explains local analysis and deliberate private sharing. */
@Composable
internal fun PrivacyCard(modifier: Modifier = Modifier) {
    StudentCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = AccountMenuSurface,
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Shield, contentDescription = null, tint = ProfileOrange, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column {
            Text(
                text = stringResource(R.string.profile_privacy_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
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
}

private val ProfileOrange = Color(0xFFCC5500)
private val ProfileBackground = Color(0xFFF0F8FF)

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
