package com.volleyballlab.player.feature.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.ActionCodeResult
import com.google.firebase.auth.ActionCodeUrl
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.volleyballlab.player.R
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.data.auth.awaitResult
import com.volleyballlab.player.domain.repository.AuthError
import com.volleyballlab.player.domain.repository.AuthOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class RecoveryStep { Email, Verify, Password, Done }

@Composable
internal fun PasswordRecoveryScreen(initialEmail: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as VolleyballLabApplication
    val repository = app.container.authRepository
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var step by remember { mutableStateOf(RecoveryStep.Email) }
    var link by remember { mutableStateOf("") }
    var code by remember { mutableStateOf<String?>(null) }
    var verifiedEmail by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }

    fun back() {
        if (busy) return
        error = null
        link = ""
        code = null
        verifiedEmail = ""
        password = ""
        confirmation = ""
        when (step) {
            RecoveryStep.Email, RecoveryStep.Done -> onClose()
            RecoveryStep.Verify, RecoveryStep.Password -> step = RecoveryStep.Email
        }
    }

    fun runRequest(request: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                request()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = when {
                    failure is FirebaseAuthWeakPasswordException -> R.string.auth_error_weak_password
                    (failure as? FirebaseAuthException)?.errorCode in
                        setOf("ERROR_INVALID_ACTION_CODE", "ERROR_EXPIRED_ACTION_CODE") -> {
                        code = null
                        verifiedEmail = ""
                        password = ""
                        confirmation = ""
                        step = RecoveryStep.Verify
                        R.string.recovery_invalid_link
                    }
                    else -> R.string.recovery_request_failed
                }
            } finally {
                busy = false
            }
        }
    }

    BackHandler { back() }
    AuthScaffold(
        title = when (step) {
            RecoveryStep.Email -> R.string.recovery_email_title
            RecoveryStep.Verify -> R.string.recovery_verify_title
            RecoveryStep.Password -> R.string.recovery_password_title
            RecoveryStep.Done -> R.string.recovery_done_title
        },
        subtitle = when (step) {
            RecoveryStep.Email -> R.string.recovery_email_body
            RecoveryStep.Verify -> R.string.recovery_verify_body
            RecoveryStep.Password -> R.string.recovery_password_body
            RecoveryStep.Done -> R.string.recovery_done_body
        },
        modifier = modifier,
    ) {
        when (step) {
            RecoveryStep.Email -> {
                AuthField(R.string.auth_email_label, R.string.auth_email_placeholder, email,
                    { email = it; error = null }, Icons.Rounded.MailOutline,
                    keyboardType = KeyboardType.Email, enabled = !busy)
                AuthMessage(error, null)
                AuthPrimaryButton(R.string.recovery_send, {
                    error = AuthViewModel.validateEmail(email)
                    if (error == null) runRequest {
                        when (val result = repository.sendPasswordReset(email)) {
                            AuthOutcome.Success -> step = RecoveryStep.Verify
                            AuthOutcome.Cancelled -> Unit
                            is AuthOutcome.Failure -> if (result.error == AuthError.WrongCredentials) {
                                step = RecoveryStep.Verify
                            } else {
                                error = result.error.message
                            }
                        }
                    }
                }, email.isNotBlank() && !busy, busy)
                TextButton(onClick = { error = null; step = RecoveryStep.Verify }, enabled = !busy) {
                    Text(stringResource(R.string.recovery_have_link))
                }
            }
            RecoveryStep.Verify -> {
                AuthField(R.string.recovery_link, R.string.recovery_link_hint, link,
                    { link = it; error = null }, Icons.Rounded.MailOutline,
                    keyboardType = KeyboardType.Uri, enabled = !busy)
                AuthMessage(error, null)
                AuthPrimaryButton(R.string.recovery_verify, {
                    val action = link.trim().takeIf { it.startsWith("https://", ignoreCase = true) }
                        ?.let { ActionCodeUrl.parseLink(it) }
                    val actionCode = action?.takeIf { it.operation == ActionCodeResult.PASSWORD_RESET }?.code
                    if (actionCode.isNullOrBlank()) {
                        error = R.string.recovery_invalid_link
                    } else runRequest {
                        val auth = FirebaseAuth.getInstance()
                        check(auth.currentUser == null)
                        verifiedEmail = auth.verifyPasswordResetCode(actionCode).awaitResult()
                        code = actionCode
                        link = ""
                        step = RecoveryStep.Password
                    }
                }, link.isNotBlank() && !busy, busy)
            }
            RecoveryStep.Password -> {
                Text(verifiedEmail)
                Spacer(Modifier.height(12.dp))
                AuthField(R.string.recovery_new_password, R.string.auth_password_placeholder, password,
                    { password = it; error = null }, Icons.Rounded.Lock,
                    keyboardType = KeyboardType.Password, isPassword = true, enabled = !busy)
                Spacer(Modifier.height(12.dp))
                AuthField(R.string.recovery_confirm_password, R.string.auth_password_placeholder, confirmation,
                    { confirmation = it; error = null }, Icons.Rounded.Lock,
                    keyboardType = KeyboardType.Password, isPassword = true, enabled = !busy)
                AuthMessage(error, null)
                AuthPrimaryButton(R.string.recovery_save, {
                    error = AuthViewModel.validatePassword(password)
                        ?: AuthViewModel.validatePasswordConfirmation(password, confirmation)
                    if (error == null) runRequest {
                        val auth = FirebaseAuth.getInstance()
                        check(auth.currentUser == null)
                        auth.confirmPasswordReset(requireNotNull(code), password).awaitResult()
                        code = null
                        verifiedEmail = ""
                        password = ""
                        confirmation = ""
                        step = RecoveryStep.Done
                    }
                }, password.isNotEmpty() && confirmation.isNotEmpty() && !busy, busy)
            }
            RecoveryStep.Done -> AuthPrimaryButton(R.string.recovery_back_sign_in, onClose, true, false)
        }
        if (step != RecoveryStep.Done) {
            TextButton(onClick = ::back, enabled = !busy) {
                Text(stringResource(if (step == RecoveryStep.Email) R.string.recovery_back_sign_in else R.string.recovery_start_over))
            }
        }
    }
}
