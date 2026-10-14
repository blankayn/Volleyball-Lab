package com.volleyballlab.player.feature.auth

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.R
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.data.auth.GoogleCredentialClient
import com.volleyballlab.player.data.auth.GoogleIdResult
import com.volleyballlab.player.domain.model.AthleteProfile
import com.volleyballlab.player.domain.model.CoachAccounts
import com.volleyballlab.player.domain.model.CourtPosition
import com.volleyballlab.player.domain.model.DominantHand
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.model.UserRole
import com.volleyballlab.player.domain.model.AddressOption
import com.volleyballlab.player.domain.model.AddressArea
import com.volleyballlab.player.domain.model.ProfileAddress
import com.volleyballlab.player.domain.repository.AddressCatalog
import com.volleyballlab.player.domain.repository.AuthOutcome
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.ProfileRepository
import com.volleyballlab.player.domain.repository.RoleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

/**
 * The state of one auth form, shared by the login and sign-up screens.
 *
 * @param error a message to show under the form. Cleared on the next keystroke -
 *   an error about a password the athlete has since changed is stale, and leaving
 *   it up makes a corrected form look like it is still wrong.
 * @param notice a non-error message, currently only the password-reset confirmation.
 */
data class AuthFormState(
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val position: CourtPosition? = null,
    val dominantHand: DominantHand? = null,
    val goalSkill: Skill? = null,
    val country: AddressOption? = null,
    val area: AddressOption? = null,
    val city: AddressOption? = null,
    val barangay: AddressOption? = null,
    val countries: List<AddressOption> = emptyList(),
    val areas: List<AddressArea> = emptyList(),
    val barangays: List<AddressOption> = emptyList(),
    val loadingCountries: Boolean = false,
    val loadingAreas: Boolean = false,
    val loadingBarangays: Boolean = false,
    val locationLoadFailed: Boolean = false,
    val rememberMe: Boolean = true,
    val passwordVisible: Boolean = false,
    val confirmPasswordVisible: Boolean = false,
    val submitting: Boolean = false,
    @param:StringRes val error: Int? = null,
    @param:StringRes val notice: Int? = null,
) {
    val name: String get() = listOf(firstName.trim(), lastName.trim()).filter { it.isNotEmpty() }.joinToString(" ")
    val cities: List<AddressOption> get() = areas.firstOrNull { it.id == area?.id }?.cities.orEmpty()
    val address: ProfileAddress? get() = country?.let { ProfileAddress(it, area, city, barangay) }
    val canSubmitLogin: Boolean
        get() = !submitting && email.isNotBlank() && password.isNotBlank()

    val canSubmitSignUp: Boolean
        get() = canSubmitLogin && firstName.isNotBlank() && lastName.isNotBlank() &&
            confirmPassword.isNotBlank() && country != null
}

class AuthViewModel(
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val roleRepository: RoleRepository,
    private val googleCredentialClient: GoogleCredentialClient,
    private val addressCatalog: AddressCatalog,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthFormState())
    val state: StateFlow<AuthFormState> = _state.asStateFlow()
    private var countriesJob: Job? = null
    private var areasJob: Job? = null
    private var barangaysJob: Job? = null
    private var editingUid: String? = null

    fun loadAccount() {
        if (editingUid != null) return
        editingUid = authRepository.currentUid ?: return
        _state.update { it.copy(submitting = true) }
        viewModelScope.launch {
            val profile = profileRepository.current()
            if (authRepository.currentUid != editingUid) return@launch
            _state.update { it.copy(firstName = profile.firstName.ifBlank { profile.name.substringBeforeLast(' ', profile.name) }.take(AthleteProfile.MAX_NAME_PART_LENGTH),
                lastName = profile.lastName.ifBlank { profile.name.substringAfterLast(' ', "") }.take(AthleteProfile.MAX_NAME_PART_LENGTH), country = profile.address?.country, area = profile.address?.area,
                city = profile.address?.city, barangay = profile.address?.barangay, submitting = true) }
            try {
                val countries = addressCatalog.countries()
                val areas = profile.address?.country?.let { addressCatalog.areas(it.id) }.orEmpty()
                val barangays = profile.address?.takeIf { it.country.id == "PH" }?.city
                    ?.let { addressCatalog.barangays(it.id) }.orEmpty()
                _state.update { it.copy(countries = countries, areas = areas, barangays = barangays,
                    submitting = false) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                _state.update { it.copy(submitting = false, locationLoadFailed = true) }
            }
        }
    }

    fun saveAccount(onSaved: () -> Unit) {
        val form = _state.value
        if (form.submitting || editingUid == null || editingUid != authRepository.currentUid) return
        when {
            form.firstName.isBlank() -> return fail(R.string.auth_error_first_name_required)
            form.lastName.isBlank() -> return fail(R.string.auth_error_last_name_required)
            form.country == null -> return fail(R.string.auth_error_country_required)
        }
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            if (editingUid != authRepository.currentUid) {
                _state.update { it.copy(submitting = false) }
                return@launch
            }
            try {
                profileRepository.update { it.copy(name = form.name, firstName = form.firstName.trim(),
                    lastName = form.lastName.trim(), address = form.address) }
                if (authRepository.currentUid == editingUid) onSaved()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                fail(R.string.auth_error_unknown)
            }
        }
    }

    fun loadLocations() {
        if (_state.value.countries.isNotEmpty() || countriesJob?.isActive == true) return
        _state.update { it.copy(loadingCountries = true, locationLoadFailed = false) }
        countriesJob = viewModelScope.launch {
            try {
                val countries = addressCatalog.countries()
                _state.update { it.copy(countries = countries, loadingCountries = false) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                _state.update { it.copy(loadingCountries = false, locationLoadFailed = true) }
            }
        }
    }

    fun onCountryChange(value: AddressOption?) {
        if (value == _state.value.country) return
        areasJob?.cancel(); barangaysJob?.cancel()
        edit { it.copy(country = value, area = null, city = null, barangay = null,
            areas = emptyList(), barangays = emptyList(), loadingAreas = value != null,
            loadingBarangays = false, locationLoadFailed = false) }
        if (value == null) return
        areasJob = viewModelScope.launch {
            try {
                val areas = addressCatalog.areas(value.id)
                _state.update { if (it.country == value) it.copy(areas = areas, loadingAreas = false) else it }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                _state.update { if (it.country == value) it.copy(loadingAreas = false, locationLoadFailed = true) else it }
            }
        }
    }

    fun onAreaChange(value: AddressOption?) {
        if (value == _state.value.area) return
        barangaysJob?.cancel()
        edit { it.copy(area = value, city = null, barangay = null, barangays = emptyList(),
            loadingBarangays = false, locationLoadFailed = false) }
    }

    fun onCityChange(value: AddressOption?) {
        if (value == _state.value.city) return
        barangaysJob?.cancel()
        val needsBarangays = value != null && _state.value.country?.id == "PH"
        edit { it.copy(city = value, barangay = null, barangays = emptyList(),
            loadingBarangays = needsBarangays, locationLoadFailed = false) }
        if (!needsBarangays) return
        barangaysJob = viewModelScope.launch {
            try {
                val barangays = addressCatalog.barangays(value.id)
                _state.update { if (it.city == value && it.country?.id == "PH")
                    it.copy(barangays = barangays, loadingBarangays = false) else it }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                _state.update { if (it.city == value) it.copy(loadingBarangays = false, locationLoadFailed = true) else it }
            }
        }
    }

    fun onBarangayChange(value: AddressOption?) = edit { it.copy(barangay = value) }

    fun retryLocations() {
        val current = _state.value
        if (countriesJob?.isActive == true) return
        _state.update { it.copy(loadingCountries = true, locationLoadFailed = false) }
        countriesJob = viewModelScope.launch {
            try {
                val countries = addressCatalog.countries()
                val areas = current.country?.let { addressCatalog.areas(it.id) }.orEmpty()
                val barangays = current.city?.takeIf { current.country?.id == "PH" }
                    ?.let { addressCatalog.barangays(it.id) }.orEmpty()
                _state.update { state -> state.copy(countries = countries, loadingCountries = false,
                    areas = if (state.country == current.country) areas else state.areas,
                    barangays = if (state.country == current.country && state.city == current.city) barangays else state.barangays) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                _state.update { it.copy(loadingCountries = false, locationLoadFailed = true) }
            }
        }
    }

    fun onFirstNameChange(value: String) = edit { it.copy(firstName = value.take(AthleteProfile.MAX_NAME_PART_LENGTH)) }
    fun onLastNameChange(value: String) = edit { it.copy(lastName = value.take(AthleteProfile.MAX_NAME_PART_LENGTH)) }
    fun onEmailChange(value: String) = edit { it.copy(email = value) }
    fun onPasswordChange(value: String) = edit { it.copy(password = value) }
    fun onConfirmPasswordChange(value: String) = edit { it.copy(confirmPassword = value) }
    fun onPositionChange(value: CourtPosition?) = edit { it.copy(position = value) }
    fun onHandChange(value: DominantHand?) = edit { it.copy(dominantHand = value) }
    fun onGoalSkillChange(value: Skill?) = edit { it.copy(goalSkill = value) }
    fun onRememberChange(value: Boolean) = _state.update { it.copy(rememberMe = value) }
    fun togglePasswordVisible() = _state.update { it.copy(passwordVisible = !it.passwordVisible) }
    fun toggleConfirmPasswordVisible() = _state.update { it.copy(confirmPasswordVisible = !it.confirmPasswordVisible) }

    /** Any edit clears the previous outcome, so stale feedback cannot linger. */
    private fun edit(transform: (AuthFormState) -> AuthFormState) =
        _state.update { transform(it).copy(error = null, notice = null) }

    /**
     * Signs in. The form asks nothing about roles, by design.
     *
     * [onSuccess] receives the role the account turns out to have, so the caller
     * can decline to navigate when the coach shell is about to replace the whole
     * graph anyway. Without that, a coach signing in sees one frame of the
     * athlete's dashboard before the swap.
     */
    fun signIn(onSuccess: (UserRole) -> Unit) {
        val form = _state.value
        val complaint = validateEmail(form.email) ?: validatePassword(form.password)
        if (complaint != null) return fail(complaint)

        submit(onSuccess) {
            authRepository.signIn(form.email, form.password, form.rememberMe).also { outcome ->
                if (outcome is AuthOutcome.Success) grantCoachRoleIfAddressed(form.email)
            }
        }
    }

    fun signUp(onSuccess: (UserRole) -> Unit) {
        val form = _state.value
        val complaint = when {
            form.firstName.isBlank() -> R.string.auth_error_first_name_required
            form.lastName.isBlank() -> R.string.auth_error_last_name_required
            form.country == null -> R.string.auth_error_country_required
            else -> validateEmail(form.email) ?: validatePassword(form.password)
                ?: validatePasswordConfirmation(form.password, form.confirmPassword)
        }
        if (complaint != null) return fail(complaint)

        submit(onSuccess) {
            authRepository.signUp(form.name, form.email, form.password).also { outcome ->
                if (outcome is AuthOutcome.Success) {
                    // The role is written before anything else, because it decides
                    // which shell the next frame builds. Both stores are scoped by
                    // uid and the uid only exists now that sign-up has returned.
                    grantCoachRoleIfAddressed(form.email)

                    // The name goes into the local profile as well as onto the
                    // account, so the dashboard can greet the athlete on the very
                    // first frame rather than after a round trip that may not have
                    // a network. A coach's name lands in the coach profile the
                    // first time they open their Account tab; duplicating it here
                    // would mean two names to keep in step.
                    profileRepository.update { existing ->
                        existing.copy(
                            name = form.name.trim(),
                            firstName = form.firstName.trim(),
                            lastName = form.lastName.trim(),
                            address = form.address,
                            position = form.position ?: existing.position,
                            dominantHand = form.dominantHand ?: existing.dominantHand,
                            goalSkill = form.goalSkill ?: existing.goalSkill,
                        )
                    }
                }
            }
        }
    }

    /**
     * Signs in with Google, in two steps that fail for different reasons.
     *
     * Getting the ID token is the system's business - an account sheet the athlete
     * can dismiss - and exchanging it for a session is Firebase's. Keeping them
     * apart is what lets a dismissed sheet return the form to rest silently while a
     * rejected token still reports something.
     *
     * @param activityContext must be an Activity: Credential Manager renders a
     *   system sheet and cannot do that from an application context.
     */
    fun signInWithGoogle(activityContext: Context, onSuccess: (UserRole) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null, notice = null) }

            when (val identity = googleCredentialClient.requestIdToken(activityContext)) {
                is GoogleIdResult.Cancelled ->
                    _state.update { it.copy(submitting = false) }

                is GoogleIdResult.Failure ->
                    _state.update { it.copy(submitting = false, error = identity.messageRes) }

                is GoogleIdResult.Success -> {
                    val outcome = authRepository
                        .signInWithGoogle(identity.idToken, _state.value.rememberMe)
                    if (outcome is AuthOutcome.Success) {
                        mirrorAccountNameIntoProfile()
                        // The address comes from the Google account, not from the
                        // form - the form was never filled in on this path.
                        authRepository.currentUser.first()
                            ?.email
                            ?.let { grantCoachRoleIfAddressed(it) }
                        onSuccess(roleRepository.current())
                    } else if (outcome is AuthOutcome.Failure) {
                        _state.update { it.copy(submitting = false, error = outcome.error.message) }
                    } else {
                        _state.update { it.copy(submitting = false) }
                    }
                }
            }
        }
    }

    /**
     * Gives this account the coach role if its address says it is one.
     *
     * Promotion only, never demotion - see [CoachAccounts] for why that asymmetry
     * matters. Must be called after the auth call has returned, because the role
     * store is scoped by uid and there is no uid until then.
     */
    private suspend fun grantCoachRoleIfAddressed(email: String) {
        if (CoachAccounts.isCoachAddress(email)) roleRepository.setRole(UserRole.Coach)
    }

    /**
     * Copies the account's name into the local profile, if there is nothing there
     * already.
     *
     * Guarded, because the athlete may have set their own name in the Profile tab,
     * and overwriting a chosen name with whatever the provider has on file would
     * undo a deliberate edit every time they signed in.
     */
    private suspend fun mirrorAccountNameIntoProfile() {
        val account = authRepository.currentUser.first() ?: return
        if (account.displayName.isNotBlank()) profileRepository.seedAccountName(account.displayName)
    }

    /**
     * Sends a reset mail, and says so whether or not the address has an account.
     *
     * Firebase already declines to reveal that distinction, and the wording here
     * matches: "if that address has an account". Confirming which addresses are
     * registered would turn this link into an account-enumeration tool.
     */
    fun sendPasswordReset() {
        val form = _state.value
        val complaint = validateEmail(form.email)
        if (complaint != null) return fail(complaint)

        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null, notice = null) }
            val outcome = authRepository.sendPasswordReset(form.email)
            _state.update {
                when (outcome) {
                    is AuthOutcome.Success ->
                        it.copy(submitting = false, notice = R.string.auth_reset_sent)
                    // Unreachable for a reset - there is no sheet to dismiss - but
                    // spelled out rather than folded into an else, so adding a
                    // fourth outcome later fails here loudly instead of silently
                    // being treated as a cancellation.
                    is AuthOutcome.Cancelled -> it.copy(submitting = false)
                    is AuthOutcome.Failure ->
                        it.copy(submitting = false, error = outcome.error.message)
                }
            }
        }
    }

    private fun submit(onSuccess: (UserRole) -> Unit, action: suspend () -> AuthOutcome) {
        if (_state.value.submitting) return
        _state.update { it.copy(submitting = true, error = null, notice = null) }
        viewModelScope.launch {
            when (val outcome = action()) {
                is AuthOutcome.Success -> {
                    // Read after the action, never from the form: by now there is a
                    // session, so the role store is scoped to the right uid and can
                    // answer for the account rather than for the last thing typed.
                    //
                    // Left submitting: navigation is about to replace this screen,
                    // and re-enabling the button first lets a fast double tap fire
                    // a second sign-in against a session that already exists.
                    onSuccess(roleRepository.current())
                }
                is AuthOutcome.Cancelled ->
                    _state.update { it.copy(submitting = false) }
                is AuthOutcome.Failure ->
                    _state.update { it.copy(submitting = false, error = outcome.error.message) }
            }
        }
    }

    private fun fail(@StringRes message: Int) =
        _state.update { it.copy(error = message, submitting = false) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AuthViewModel(
                    authRepository = appContainer.authRepository,
                    profileRepository = appContainer.profileRepository,
                    roleRepository = appContainer.roleRepository,
                    googleCredentialClient = appContainer.googleCredentialClient,
                    addressCatalog = appContainer.addressCatalog,
                )
            }
        }

        /** Firebase's own floor. Rejecting it here saves a pointless round trip. */
        const val MIN_PASSWORD_LENGTH = 6

        /**
         * Deliberately not `android.util.Patterns.EMAIL_ADDRESS`.
         *
         * That constant is part of the Android framework, so it is null under the
         * JVM unit tests this project runs, and a validator that cannot be tested
         * is a validator nobody checks. This is also intentionally loose: the only
         * job here is catching an obvious typo before spending a network call, and
         * the authority on whether an address exists is the mail that either
         * arrives or does not.
         */
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        @StringRes
        fun validateEmail(email: String): Int? =
            if (EMAIL.matches(email.trim())) null else R.string.auth_error_invalid_email

        @StringRes
        fun validatePassword(password: String): Int? =
            if (password.length >= MIN_PASSWORD_LENGTH) null else R.string.auth_error_weak_password

        @StringRes
        fun validatePasswordConfirmation(password: String, confirmation: String): Int? = when {
            confirmation.isBlank() -> R.string.auth_error_confirm_required
            password != confirmation -> R.string.auth_error_password_mismatch
            else -> null
        }
    }
}
