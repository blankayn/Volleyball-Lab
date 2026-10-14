package com.volleyballlab.player.domain.repository

import androidx.annotation.StringRes
import com.volleyballlab.player.R
import kotlinx.coroutines.flow.Flow

/**
 * A signed-in athlete, reduced to what this app actually uses.
 *
 * Deliberately not the provider's user object. Nothing outside the auth
 * implementation should be able to reach a token, a provider list or a reload
 * method, because the moment a screen can, the choice of provider stops being an
 * implementation detail and becomes something every screen depends on.
 */
data class AuthUser(
    val uid: String,
    val email: String,
    val displayName: String,
    val emailVerified: Boolean = false,
    val passwordProvider: Boolean = true,
)

/**
 * Why a sign-in attempt failed, in terms the athlete can act on.
 *
 * The provider distinguishes far more cases than this. Most of that detail is
 * either the same instruction to the user - "check the address and try again" - or
 * something they cannot act on at all, and a screen that reports every distinct
 * provider error verbatim is reporting the provider's internals rather than helping.
 *
 * [WrongCredentials] merges "no such user" and "wrong password" on purpose. Telling
 * an attacker which of the two it was turns the login form into a way to test
 * whether an address has an account here.
 */
enum class AuthError(@param:StringRes val message: Int) {
    WrongCredentials(R.string.auth_error_credentials),
    EmailAlreadyUsed(R.string.auth_error_email_taken),
    WeakPassword(R.string.auth_error_weak_password),
    InvalidEmail(R.string.auth_error_invalid_email),
    Network(R.string.auth_error_network),

    /**
     * The project has this sign-in method switched off. Not something the athlete
     * can fix, so the message points at whoever set the project up instead of
     * asking them to try again at something that cannot work yet.
     */
    ProviderDisabled(R.string.auth_error_provider_disabled),

    /**
     * Authentication has not been turned on for the Firebase project at all - a
     * different state from having it on with this one method disabled, and a
     * different fix, so it gets its own message rather than being folded in.
     */
    ProjectNotConfigured(R.string.auth_error_not_configured),
    Unknown(R.string.auth_error_unknown),
    RecentLoginRequired(R.string.auth_error_recent_login),
    NoSession(R.string.auth_error_no_session),
    TooManyRequests(R.string.auth_error_rate_limit),
}

sealed interface AuthOutcome {
    data object Success : AuthOutcome

    /**
     * The athlete dismissed the account sheet. Not a failure: nothing went wrong
     * and there is nothing to report, so the screen simply returns to rest.
     */
    data object Cancelled : AuthOutcome

    data class Failure(val error: AuthError) : AuthOutcome
}

/**
 * Account and session state.
 *
 * ## Note for the team
 *
 * `StartupViewModel` has always said the capstone's authentication module belongs to
 * another member. This interface is the seam it described: the screens and the
 * startup gate depend on this, never on Firebase, so if that work lands separately
 * it replaces one implementation class rather than every caller.
 */
interface AuthRepository {

    /** The signed-in athlete, or null. Emits again on sign-in and sign-out. */
    val currentUser: Flow<AuthUser?>

    /**
     * The signed-in athlete's uid right now, or null.
     *
     * A synchronous snapshot alongside the flow, because local storage has to be
     * scoped to an account on the *write* path too, and a suspending read there
     * would let a save land against whatever the flow last emitted rather than
     * against whoever is actually signed in.
     */
    val currentUid: String?

    /**
     * The session to resume at startup, or null to show the login screen.
     *
     * Returns null when the athlete did not tick "Remember me" on their last
     * sign-in, and signs the stale session out on the way. The provider keeps a
     * credential on disk regardless of what the checkbox said, so honouring it has
     * to happen here - otherwise the box is decorative, which is worse than not
     * offering it.
     */
    suspend fun restoreSession(): AuthUser?

    suspend fun signIn(email: String, password: String, rememberSession: Boolean): AuthOutcome

    /**
     * Exchanges a Google ID token for a Firebase session.
     *
     * Takes the token rather than fetching it, because obtaining one needs an
     * Activity to host the system account sheet, and a repository that demands an
     * Activity is a repository that cannot be used from anywhere else - or tested.
     * `GoogleCredentialClient` owns that half.
     */
    suspend fun signInWithGoogle(idToken: String, rememberSession: Boolean): AuthOutcome

    suspend fun signUp(name: String, email: String, password: String): AuthOutcome

    suspend fun sendPasswordReset(email: String): AuthOutcome

    suspend fun sendVerificationEmail(): AuthOutcome = AuthOutcome.Failure(AuthError.ProviderDisabled)
    suspend fun refreshAccount(): AuthOutcome = AuthOutcome.Failure(AuthError.ProviderDisabled)
    suspend fun changePassword(currentPassword: String, newPassword: String): AuthOutcome = AuthOutcome.Failure(AuthError.ProviderDisabled)

    suspend fun signOut()
}
