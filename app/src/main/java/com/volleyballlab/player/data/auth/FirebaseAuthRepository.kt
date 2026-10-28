package com.volleyballlab.player.data.auth

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.FirebaseTooManyRequestsException
import kotlinx.coroutines.CancellationException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.userProfileChangeRequest
import com.google.firebase.messaging.FirebaseMessaging
import com.volleyballlab.player.domain.repository.AuthError
import com.volleyballlab.player.domain.repository.AuthOutcome
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.AuthUser
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** One DataStore per file for the process, as the API requires. */
private val Context.sessionPreferences: DataStore<Preferences> by preferencesDataStore(
    name = "auth_session",
)

/**
 * [AuthRepository] on Firebase Authentication, email and password.
 *
 * ## Google and Apple are not wired, and the reason is in google-services.json
 *
 * That file currently carries `"oauth_client": []`. Google Sign-In needs an OAuth
 * client, which the Firebase console only creates once a SHA-1 signing fingerprint
 * has been registered against the Android app. Until that happens there is nothing
 * for a Google button to call, so the login screen shows it disabled rather than
 * offering a control that cannot work. To enable it:
 *
 * 1. `./gradlew :app:signingReport` and copy the debug SHA-1.
 * 2. Firebase console, Project settings, add the fingerprint to the Android app.
 * 3. Authentication, Sign-in method, enable Google.
 * 4. Re-download `google-services.json` into `app/`.
 *
 * Apple is a separate matter: Firebase can do it on Android through `OAuthProvider`,
 * but configuring Sign in with Apple requires a paid Apple Developer Program
 * membership, so that button stays disabled regardless of what happens in Firebase.
 *
 * ## Why the Task API is bridged by hand
 *
 * The usual bridge is `kotlinx-coroutines-play-services` for its `Task.await()`.
 * That is one more artifact in the build for one extension function, and
 * [awaitResult] below is that function. It handles the three outcomes the naive
 * version misses: a failed task carries its exception, a cancelled task cancels the
 * coroutine rather than resuming it with null, and the continuation resumes exactly
 * once in every path.
 */
class FirebaseAuthRepository(
    private val auth: FirebaseAuth,
    private val context: Context,
) : AuthRepository {

    override val currentUid: String? get() = auth.currentUser?.uid

    override val currentUser: Flow<AuthUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.toAuthUser()) }
        val tokenListener = FirebaseAuth.IdTokenListener { provider: FirebaseAuth -> trySend(provider.currentUser?.toAuthUser()) }
        auth.addAuthStateListener(listener)
        auth.addIdTokenListener(tokenListener)
        awaitClose { auth.removeAuthStateListener(listener); auth.removeIdTokenListener(tokenListener) }
    }

    /**
     * Firebase restores a cached session synchronously from disk during its own
     * initialisation, so this does not need a network round trip and works offline.
     * That matters at startup: an athlete opening the app in a gym with no signal
     * must not be thrown back to the login screen.
     */
    override suspend fun restoreSession(): AuthUser? {
        val user = auth.currentUser ?: return null
        if (rememberSession()) return user.toAuthUser()
        // They asked not to be remembered, so the credential Firebase kept anyway
        // is discarded here rather than silently honoured.
        signOut()
        return null
    }

    override suspend fun signIn(
        email: String,
        password: String,
        rememberSession: Boolean,
    ): AuthOutcome = attempt {
        auth.signInWithEmailAndPassword(email.trim(), password).awaitResult()
        setRememberSession(rememberSession)
    }

    override suspend fun signInWithGoogle(idToken: String, rememberSession: Boolean): AuthOutcome =
        attempt {
            auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                .awaitResult()
            setRememberSession(rememberSession)
        }

    override suspend fun signUp(name: String, email: String, password: String): AuthOutcome =
        attempt {
            val created = auth.createUserWithEmailAndPassword(email.trim(), password).awaitResult()
            // The display name is set on the account itself, not only in the local
            // profile, so it survives a reinstall and is there for the coach portal
            // when that lands. A failure here is not worth failing the sign-up over:
            // the account exists, and a missing name is a cosmetic problem the
            // Profile tab can fix.
            runCatching {
                created.user
                    ?.updateProfile(userProfileChangeRequest { displayName = name.trim() })
                    ?.awaitResult()
            }
            // Signing up is an explicit act of setting the account up on this
            // phone; being asked to log in again on the very next launch would
            // read as the sign-up not having worked.
            setRememberSession(true)
            runCatching { created.user?.sendEmailVerification()?.awaitResult() }
        }

    override suspend fun sendPasswordReset(email: String): AuthOutcome = attempt {
        auth.sendPasswordResetEmail(email.trim()).awaitResult()
    }

    override suspend fun sendVerificationEmail(): AuthOutcome {
        val user = auth.currentUser ?: return AuthOutcome.Failure(AuthError.NoSession)
        if (user.isEmailVerified) return AuthOutcome.Success
        return attempt { user.sendEmailVerification().awaitResult() }
    }

    override suspend fun refreshAccount(): AuthOutcome {
        val user = auth.currentUser ?: return AuthOutcome.Failure(AuthError.NoSession)
        return attempt { user.reload().awaitResult(); user.getIdToken(true).awaitResult() }
    }

    override suspend fun changePassword(currentPassword: String, newPassword: String): AuthOutcome {
        val user = auth.currentUser ?: return AuthOutcome.Failure(AuthError.NoSession)
        val email = user.email ?: return AuthOutcome.Failure(AuthError.ProviderDisabled)
        if (user.providerData.none { it.providerId == EmailAuthProvider.PROVIDER_ID }) return AuthOutcome.Failure(AuthError.ProviderDisabled)
        if (newPassword.length < 6) return AuthOutcome.Failure(AuthError.WeakPassword)
        return attempt {
            user.reauthenticate(EmailAuthProvider.getCredential(email, currentPassword)).awaitResult()
            user.updatePassword(newPassword).awaitResult()
        }
    }

    override suspend fun signOut() {
        runCatching {
            withTimeoutOrNull(2_000) { FirebaseMessaging.getInstance().unregister().awaitResult() }
        }
        auth.signOut()
        setRememberSession(false)
    }

    private suspend fun rememberSession(): Boolean =
        context.sessionPreferences.data.map { it[KEY_REMEMBER] != false }.first()

    private suspend fun setRememberSession(remember: Boolean) {
        context.sessionPreferences.edit { it[KEY_REMEMBER] = remember }
    }

    private companion object {
        const val TAG = "VbAuth"

        /**
         * Absent means remembered. A fresh install has no preference and no session
         * either, so the default only ever applies to an account created by a build
         * that predates this flag.
         */
        val KEY_REMEMBER = booleanPreferencesKey("remember_session")
    }

    private inline fun <T> attempt(block: () -> T): AuthOutcome =
        runCatching { block() }.fold(
            onSuccess = { AuthOutcome.Success },
            onFailure = { failure ->
                if (failure is CancellationException) throw failure
                // The screen shows a sentence the athlete can act on, which
                // necessarily discards everything needed to diagnose the cause.
                // Without this line an unmapped failure is a dead end: the user
                // reads "something went wrong" and logcat says nothing at all.
                Log.w(TAG, "Auth request failed", failure)
                AuthOutcome.Failure(failure.toAuthError())
            },
        )
}

private fun FirebaseUser.toAuthUser() = AuthUser(
    uid = uid,
    email = email.orEmpty(),
    displayName = displayName.orEmpty(),
    emailVerified = isEmailVerified,
    passwordProvider = providerData.any { it.providerId == EmailAuthProvider.PROVIDER_ID },
)

/**
 * Maps a provider exception onto something the athlete can act on.
 *
 * ## A `when (this)` on types was not enough
 *
 * The subject-less form matters here. `CONFIGURATION_NOT_FOUND` - Authentication
 * never provisioned on the Firebase project - arrives as a plain
 * `com.google.firebase.FirebaseException`, *not* as a `FirebaseAuthException`,
 * despite being thrown by an auth call. A branch on the auth subclass silently
 * misses it, and the athlete gets "something went wrong" for a fault that has a
 * precise cause and a one-click fix. So the message check sits at the top level,
 * where the exact type cannot exclude it.
 *
 * Order is load-bearing: every specific subclass must be tested before the general
 * one, or the general branch swallows it.
 *
 * `FirebaseAuthInvalidCredentialsException` covers both a malformed address and a
 * wrong password, which need different instructions, so its error code is read to
 * tell them apart. Anything unrecognised becomes [AuthError.Unknown] rather than
 * being reported verbatim - provider messages are written for developers, and
 * "There is no user record corresponding to this identifier" is not a sentence to
 * put in front of someone trying to log in. The `Log.w` above keeps the real
 * exception recoverable when that happens.
 */
private fun Throwable.toAuthError(): AuthError = when {
    this is FirebaseAuthRecentLoginRequiredException -> AuthError.RecentLoginRequired
    this is FirebaseTooManyRequestsException -> AuthError.TooManyRequests
    this is FirebaseAuthWeakPasswordException -> AuthError.WeakPassword
    this is FirebaseAuthUserCollisionException -> AuthError.EmailAlreadyUsed
    this is FirebaseAuthInvalidUserException -> AuthError.WrongCredentials
    this is FirebaseAuthInvalidCredentialsException ->
        if (errorCode == "ERROR_INVALID_EMAIL") AuthError.InvalidEmail else AuthError.WrongCredentials
    this is FirebaseNetworkException -> AuthError.Network
    // The sign-in method exists but is switched off for this project.
    this is FirebaseAuthException && errorCode == "ERROR_OPERATION_NOT_ALLOWED" ->
        AuthError.ProviderDisabled
    // Authentication has never been turned on at all, so there is no Identity
    // Toolkit config to answer the request. The real cause is only in the message;
    // the wrapper exception says nothing but "an internal error has occurred".
    message?.contains(CONFIGURATION_NOT_FOUND) == true -> AuthError.ProjectNotConfigured
    else -> AuthError.Unknown
}

private const val CONFIGURATION_NOT_FOUND = "CONFIGURATION_NOT_FOUND"

/**
 * Awaits a [Task], propagating failure and cancellation rather than flattening
 * either into a null result.
 */
private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val failure = task.exception
        when {
            failure != null -> continuation.resumeWithException(failure)
            task.isCanceled -> continuation.cancel()
            else -> continuation.resume(task.result)
        }
    }
}
