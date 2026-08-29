package com.volleyballlab.player.feature.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
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
 * Creating an account.
 *
 * Asks for a name as well as credentials, because the athlete's name is the one
 * thing the rest of the app visibly uses - the dashboard greets them by it - and
 * collecting it here means the Profile tab is not the first place they have to go.
 * It is stored on the Firebase account and mirrored into the local profile, so it
 * survives both a reinstall and being offline.
 *
 * ## This form only ever makes players
 *
 * There is no coach option here and there should not be one: coaches register on
 * the web portal, and the mobile app only receives accounts that already exist.
 * See [com.volleyballlab.player.domain.model.CoachAccounts] for how one is
 * recognised at login, and [com.volleyballlab.player.domain.model.DemoCoach] for
 * how the coach shell is reached in a debug build while that portal is unwritten.
 */
@Composable
fun SignUpScreen(
    onAuthenticated: (UserRole) -> Unit,
    onBackToLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: AuthViewModel = viewModel(factory = AuthViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()

    AuthScaffold(
        title = R.string.auth_signup_title,
        subtitle = R.string.auth_signup_subtitle,
        modifier = modifier,
    ) {
        AuthField(
            label = R.string.auth_name_label,
            placeholder = R.string.auth_name_placeholder,
            value = state.name,
            onValueChange = viewModel::onNameChange,
            leadingIcon = Icons.Rounded.PersonOutline,
            enabled = !state.submitting,
        )
        Spacer(Modifier.height(16.dp))
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
            placeholder = R.string.auth_password_new_placeholder,
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

        Spacer(Modifier.height(10.dp))
        Text(
            text = pluralStringResource(
                R.plurals.auth_password_hint,
                AuthViewModel.MIN_PASSWORD_LENGTH,
                AuthViewModel.MIN_PASSWORD_LENGTH,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        AuthMessage(error = state.error, notice = state.notice)

        Spacer(Modifier.height(20.dp))
        AuthPrimaryButton(
            label = R.string.auth_signup_action,
            onClick = { viewModel.signUp(onAuthenticated) },
            enabled = state.canSubmitSignUp,
            busy = state.submitting,
        )

        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.auth_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.auth_have_account),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.auth_login_action),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = LinkColor,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .clickable(enabled = !state.submitting, onClick = onBackToLogin),
            )
        }
    }
}
