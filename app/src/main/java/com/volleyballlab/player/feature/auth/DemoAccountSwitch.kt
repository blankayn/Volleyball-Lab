package com.volleyballlab.player.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.R
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.data.auth.DemoSignIn
import com.volleyballlab.player.domain.model.DemoAccount
import com.volleyballlab.player.domain.model.UserRole
import com.volleyballlab.player.domain.repository.AuthOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Busy and error, which is all a one-tap control has room to be. */
data class DemoSwitchState(
    val submitting: Boolean = false,
    val error: Int? = null,
)

/**
 * Drives the demo-account controls, wherever they appear.
 *
 * One ViewModel for three screens across both shells. It holds no reference to
 * which account is "current" and offers no default target - every caller names the
 * account it wants - because the athlete's Profile tab offers the coach fixture to
 * *any* account, including a real athlete's, and inferring the target from the
 * signed-in role would make that button do nothing on the one account it is most
 * useful from.
 */
class DemoSwitchViewModel(private val demoSignIn: DemoSignIn) : ViewModel() {

    private val _state = MutableStateFlow(DemoSwitchState())
    val state: StateFlow<DemoSwitchState> = _state.asStateFlow()

    /**
     * Signs into [account] and reports the role it turned out to have.
     *
     * [onEntered] exists for the login screen, which is inside the player shell and
     * therefore has to navigate off the auth stack itself when the account is an
     * athlete. From inside either shell there is nothing to do: writing the role
     * makes the role flow emit and `VolleyballLabApp` rebuilds the graph.
     */
    fun enter(account: DemoAccount, onEntered: (UserRole) -> Unit = {}) {
        if (_state.value.submitting) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            when (val outcome = demoSignIn.enter(account)) {
                is AuthOutcome.Success -> {
                    // Left submitting on purpose. Either the shell is about to be
                    // torn down or the caller is about to navigate, and re-enabling
                    // the buttons first invites a second sign-in against a session
                    // that already exists.
                    onEntered(account.role)
                }

                is AuthOutcome.Cancelled ->
                    _state.update { it.copy(submitting = false) }

                is AuthOutcome.Failure ->
                    _state.update { it.copy(submitting = false, error = outcome.error.message) }
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { DemoSwitchViewModel(appContainer.demoSignIn) }
        }
    }
}

/**
 * One button per standing test account, plus whatever went wrong.
 *
 * ## Content only - the caller owns the card
 *
 * The two shells have genuinely different visual languages (the athlete's rounded
 * surfaces, the coach shell's tinted section cards) and this appears inside both.
 * Rendering its own container would mean one of the two screens carrying a panel
 * that belongs to the other, so the host supplies the card and the button [shape]
 * and this supplies the behaviour.
 *
 * @param accounts what to offer. Two on the login screen, one from inside a shell -
 *   there is no point offering the account you are already signed into.
 * @param onEntered see [DemoSwitchViewModel.enter].
 */
@Composable
fun DemoAccountButtons(
    accounts: List<DemoAccount>,
    shape: Shape,
    modifier: Modifier = Modifier,
    onEntered: (UserRole) -> Unit = {},
) {
    val viewModel: DemoSwitchViewModel = viewModel(factory = DemoSwitchViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        accounts.forEach { account ->
            OutlinedButton(
                onClick = { viewModel.enter(account, onEntered) },
                enabled = !state.submitting,
                shape = shape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.demo_open_account, account.name))
            }
        }

        val error = state.error
        if (error != null) {
            Text(
                text = stringResource(error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.demo_accounts_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
