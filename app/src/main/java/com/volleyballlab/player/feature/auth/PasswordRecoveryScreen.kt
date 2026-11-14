package com.volleyballlab.player.feature.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.functions.FirebaseFunctionsException
import com.volleyballlab.player.R
import com.volleyballlab.player.data.auth.FirebasePasswordRecoveryService
import com.volleyballlab.player.data.auth.PasswordRecoveryService
import com.volleyballlab.player.data.auth.RecoveryChallenge
import com.volleyballlab.player.data.auth.VerifiedRecovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class RecoveryStep { Email, Verify, Password, LinkSent, Done }

@Composable
internal fun PasswordRecoveryScreen(
    initialEmail: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    recovery: PasswordRecoveryService = remember { FirebasePasswordRecoveryService() },
) {
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var step by remember { mutableStateOf(RecoveryStep.Email) }
    var code by remember { mutableStateOf("") }
    var challenge by remember { mutableStateOf<RecoveryChallenge?>(null) }
    var verified by remember { mutableStateOf<VerifiedRecovery?>(null) }
    var resendSeconds by remember { mutableIntStateOf(0) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }

    fun back() {
        if (busy) return
        error = null
        code = ""
        challenge = null
        verified = null
        password = ""
        confirmation = ""
        when (step) {
            RecoveryStep.Email, RecoveryStep.LinkSent, RecoveryStep.Done -> onClose()
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
                    (failure as? FirebaseFunctionsException)?.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
                        R.string.recovery_too_many_requests
                    (failure as? FirebaseFunctionsException)?.code == FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
                        if (step == RecoveryStep.Verify) R.string.recovery_invalid_code else R.string.recovery_request_failed
                    (failure as? FirebaseAuthException)?.errorCode in
                        setOf("ERROR_INVALID_ACTION_CODE", "ERROR_EXPIRED_ACTION_CODE") -> {
                        code = ""
                        verified = null
                        password = ""
                        confirmation = ""
                        step = RecoveryStep.Verify
                        R.string.recovery_invalid_code
                    }
                    else -> R.string.recovery_request_failed
                }
            } finally {
                busy = false
            }
        }
    }

    fun sendCode() {
        error = AuthViewModel.validateEmail(email)
        if (error == null) runRequest {
            challenge = recovery.sendRecovery(email)
            resendSeconds = challenge?.resendAfterSeconds ?: 0
            code = ""
            step = if (challenge == null) RecoveryStep.LinkSent else RecoveryStep.Verify
        }
    }

    LaunchedEffect(resendSeconds) {
        if (resendSeconds > 0) {
            delay(1_000)
            resendSeconds--
        }
    }

    BackHandler { back() }
    AuthScaffold(
        title = when (step) {
            RecoveryStep.Email -> R.string.recovery_email_title
            RecoveryStep.Verify, RecoveryStep.LinkSent -> R.string.recovery_verify_title
            RecoveryStep.Password -> R.string.recovery_password_title
            RecoveryStep.Done -> R.string.recovery_done_title
        },
        subtitle = when (step) {
            RecoveryStep.Email -> R.string.recovery_email_body
            RecoveryStep.Verify -> R.string.recovery_verify_body
            RecoveryStep.Password -> R.string.recovery_password_body
            RecoveryStep.LinkSent -> R.string.recovery_link_sent_body
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
                Spacer(Modifier.height(24.dp))
                AuthPrimaryButton(
                    if (recovery.usesEmailCode) R.string.recovery_send else R.string.recovery_send_email,
                    ::sendCode, email.isNotBlank() && !busy, busy,
                )
            }
            RecoveryStep.Verify -> {
                Text(email.trim(), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                AuthField(R.string.recovery_code, R.string.recovery_code_hint, code,
                    { code = it.filter { digit -> digit in '0'..'9' }.take(6); error = null }, Icons.Rounded.Lock,
                    keyboardType = KeyboardType.NumberPassword, enabled = !busy)
                AuthMessage(error, null)
                Spacer(Modifier.height(24.dp))
                AuthPrimaryButton(R.string.recovery_verify, {
                    runRequest {
                        verified = recovery.verifyCode(requireNotNull(challenge), code)
                        code = ""
                        step = RecoveryStep.Password
                    }
                }, code.length == 6 && !busy, busy)
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = ::sendCode,
                    enabled = !busy && resendSeconds == 0,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(if (resendSeconds > 0) stringResource(R.string.recovery_resend_wait, resendSeconds)
                        else stringResource(R.string.recovery_resend))
                }
            }
            RecoveryStep.Password -> {
                Text(verified?.email.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                AuthField(R.string.recovery_new_password, R.string.auth_password_placeholder, password,
                    { password = it; error = null }, Icons.Rounded.Lock,
                    keyboardType = KeyboardType.Password, isPassword = true, enabled = !busy)
                Spacer(Modifier.height(12.dp))
                AuthField(R.string.recovery_confirm_password, R.string.auth_password_placeholder, confirmation,
                    { confirmation = it; error = null }, Icons.Rounded.Lock,
                    keyboardType = KeyboardType.Password, isPassword = true, enabled = !busy)
                AuthMessage(error, null)
                Spacer(Modifier.height(24.dp))
                AuthPrimaryButton(R.string.recovery_save, {
                    error = AuthViewModel.validatePassword(password)
                        ?: AuthViewModel.validatePasswordConfirmation(password, confirmation)
                    if (error == null) runRequest {
                        recovery.resetPassword(requireNotNull(verified), password)
                        verified = null
                        password = ""
                        confirmation = ""
                        step = RecoveryStep.Done
                    }
                }, password.isNotEmpty() && confirmation.isNotEmpty() && !busy, busy)
            }
            RecoveryStep.LinkSent, RecoveryStep.Done ->
                AuthPrimaryButton(R.string.recovery_back_sign_in, onClose, true, false)
        }
        if (step != RecoveryStep.Done && step != RecoveryStep.LinkSent) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = ::back,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(if (step == RecoveryStep.Email) R.string.recovery_back_sign_in else R.string.recovery_start_over))
            }
        }
    }
}
