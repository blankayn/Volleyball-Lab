package com.volleyballlab.player.feature.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.UserRole

/**
 * One login form for both halves of the app.
 *
 * There is deliberately nothing here that asks whether you are a player or a
 * coach. The role is a property of the account (`User_Role`, Table 7), so the
 * question has already been answered by the time anyone reaches this screen, and
 * asking again would let a coach sign in as a player by tapping the wrong chip.
 *
 * @param onAuthenticated receives the role the account turned out to have, so the
 *   caller can route - or, for a coach, deliberately not navigate at all, because
 *   the shell swap is about to replace this whole graph.
 */
@Composable
fun LoginScreen(
    onAuthenticated: (UserRole) -> Unit,
    onSignUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: AuthViewModel = viewModel(factory = AuthViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Credential Manager renders a system sheet, so it needs the Activity that is
    // hosting this composable. LocalContext is not guaranteed to be one - Compose
    // can hand back a ContextWrapper - so it is unwrapped rather than cast, which
    // is a crash waiting for the one device that wraps it.
    val activity = LocalContext.current.findActivity()

    AuthScaffold(
        title = R.string.auth_login_title,
        subtitle = R.string.auth_login_subtitle,
        modifier = modifier,
    ) {
        AuthField(
            label = R.string.auth_email_label,
            placeholder = R.string.auth_email_placeholder,
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            leadingIcon = Icons.Rounded.MailOutline,
            keyboardType = KeyboardType.Email,
            enabled = !state.submitting,
        )
        Spacer(Modifier.height(16.dp))
        AuthField(
            label = R.string.auth_password_label,
            placeholder = R.string.auth_password_placeholder,
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            leadingIcon = Icons.Rounded.Lock,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            isPassword = true,
            passwordVisible = state.passwordVisible,
            onTogglePasswordVisible = viewModel::togglePasswordVisible,
            enabled = !state.submitting,
        )

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            AuthCheckbox(
                checked = state.rememberMe,
                onCheckedChange = viewModel::onRememberChange,
                label = R.string.auth_remember_me,
            )
            Text(
                text = stringResource(R.string.auth_forgot_password),
                style = MaterialTheme.typography.bodyMedium,
                color = LinkColor,
                modifier = Modifier.clickable(enabled = !state.submitting) {
                    viewModel.sendPasswordReset()
                },
            )
        }

        AuthMessage(error = state.error, notice = state.notice)

        Spacer(Modifier.height(20.dp))
        AuthPrimaryButton(
            label = R.string.auth_login_action,
            onClick = { viewModel.signIn(onAuthenticated) },
            enabled = state.canSubmitLogin,
            busy = state.submitting,
        )

        Spacer(Modifier.height(22.dp))
        AuthDivider()
        Spacer(Modifier.height(16.dp))

        AuthProviderButton(
            label = R.string.auth_provider_google,
            modifier = Modifier.fillMaxWidth(),
            mark = { GoogleMark() },
            // Null while a request is in flight, which is what makes the button
            // both inert and visibly muted. Two account sheets racing each other
            // is not a state worth having.
            onClick = if (state.submitting || activity == null) {
                null
            } else {
                { viewModel.signInWithGoogle(activity, onAuthenticated) }
            },
        )

        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.auth_no_account),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.auth_signup_action),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = LinkColor,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .clickable(enabled = !state.submitting, onClick = onSignUp),
            )
        }
    }
}

@Composable
internal fun AuthMessage(error: Int?, notice: Int?) {
    val message = error ?: notice ?: return
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(message),
        style = MaterialTheme.typography.bodySmall,
        color = if (error != null) MaterialTheme.colorScheme.error else LinkColor,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GoogleMark() {
    Image(
        painter = painterResource(R.drawable.ic_google_g),
        contentDescription = null,
        modifier = Modifier.size(18.dp),
    )
}

/**
 * Walks the context chain to the Activity hosting this composable.
 *
 * `LocalContext.current as Activity` is the usual shortcut and it is a latent
 * crash: Compose is free to hand back a `ContextWrapper`, and every
 * `ContextThemeWrapper` is one. Returning null lets the caller disable the button
 * rather than taking the app down when it is pressed.
 */
internal fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

internal val LinkColor = Color(0xFF2563EB)
