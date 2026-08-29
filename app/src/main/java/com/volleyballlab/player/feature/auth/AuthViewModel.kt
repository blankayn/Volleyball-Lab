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
import com.volleyballlab.player.domain.model.UserRole
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

/**
 * The state of one auth form, shared by the login and sign-up screens.
 *
 * @param error a message to show under the form. Cleared on the next keystroke -
 *   an error about a password the athlete has since changed is stale, and leaving
 *   it up makes a corrected form look like it is still wrong.
 * @param notice a non-error message, currently only the password-reset confirmation.
 */
data class AuthFormState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val rememberMe: Boolean = true,
    val passwordVisible: Boolean = false,
    val submitting: Boolean = false,
    @param:StringRes val error: Int? = null,
    @param:StringRes val notice: Int? = null,
) {
    val canSubmitLogin: Boolean
        get() = !submitting && email.isNotBlank() && password.isNotBlank()

    val canSubmitSignUp: Boolean
        get() = canSubmitLogin && name.isNotBlank()
}

class AuthViewModel(
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val roleRepository: RoleRepository,
    private val googleCredentialClient: GoogleCredentialClient,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthFormState())
    val state: StateFlow<AuthFormState> = _state.asStateFlow()

    fun onNameChange(value: String) = edit { it.copy(name = value.take(AthleteProfile.MAX_NAME_LENGTH)) }
    fun onEmailChange(value: String) = edit { it.copy(email = value) }
    fun onPasswordChange(value: String) = edit { it.copy(password = value) }
    fun onRememberChange(value: Boolean) = _state.update { it.copy(rememberMe = value) }
    fun togglePasswordVisible() = _state.update { it.copy(passwordVisible = !it.passwordVisible) }

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
            form.name.isBlank() -> R.string.auth_error_name_required
            else -> validateEmail(form.email) ?: validatePassword(form.password)
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
                    val existing = profileRepository.profile.first()
                    profileRepository.save(existing.copy(name = form.name.trim()))
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
        val existing = profileRepository.profile.first()
        if (existing.hasName || account.displayName.isBlank()) return
        profileRepository.save(existing.copy(name = account.displayName))
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
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null, notice = null) }
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
    }
}
