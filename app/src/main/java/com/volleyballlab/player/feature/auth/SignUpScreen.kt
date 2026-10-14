package com.volleyballlab.player.feature.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.volleyballlab.player.domain.model.AddressOption

/**
 * Creating an account.
 *
 * Asks for a name as well as credentials, because the athlete's name is the one
 * thing the rest of the app visibly uses - the dashboard greets them by it - and
 * collecting it here means the Profile tab is not the first place they have to go.
 * It is stored on the Firebase account and mirrored into the local profile, so it
 * survives both a reinstall and being offline.
 *
 * Address selections use the account-scoped profile store.
 * Account roles are resolved after registration rather than chosen on this form.
 */
@Composable
fun SignUpScreen(
    onAuthenticated: (UserRole) -> Unit,
    onBackToLogin: () -> Unit,
    modifier: Modifier = Modifier,
    model: AuthViewModel = viewModel(factory = AuthViewModel.Factory),
) {
    val viewModel = model
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.loadLocations() }

    AuthScaffold(
        title = R.string.auth_signup_title,
        subtitle = R.string.auth_signup_subtitle,
        modifier = modifier,
    ) {
        AuthField(
            label = R.string.auth_first_name_label,
            placeholder = R.string.auth_first_name_placeholder,
            value = state.firstName,
            onValueChange = viewModel::onFirstNameChange,
            leadingIcon = Icons.Rounded.PersonOutline,
            enabled = !state.submitting,
        )
        Spacer(Modifier.height(16.dp))
        AuthField(
            label = R.string.auth_last_name_label,
            placeholder = R.string.auth_last_name_placeholder,
            value = state.lastName,
            onValueChange = viewModel::onLastNameChange,
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
            imeAction = ImeAction.Next,
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

        Spacer(Modifier.height(16.dp))
        AuthField(
            label = R.string.auth_confirm_password_label,
            placeholder = R.string.auth_confirm_password_placeholder,
            value = state.confirmPassword,
            onValueChange = viewModel::onConfirmPasswordChange,
            leadingIcon = Icons.Rounded.Lock,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            isPassword = true,
            passwordVisible = state.confirmPasswordVisible,
            onTogglePasswordVisible = viewModel::toggleConfirmPasswordVisible,
            enabled = !state.submitting,
            errorMessage = state.error?.takeIf { it == R.string.auth_error_password_mismatch },
        )
        Spacer(Modifier.height(24.dp))
        SignUpLocationFields(state, viewModel)
        Spacer(Modifier.height(16.dp))

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
            text = stringResource(R.string.auth_signup_profile_note),
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

@Composable
internal fun SignUpLocationFields(state: AuthFormState, model: AuthViewModel) {
    val enabled = !state.submitting
    val philippines = state.country?.id == "PH"
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text(stringResource(R.string.auth_location_title), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = TitleColor)
        Text(stringResource(R.string.auth_location_optional), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SignUpChoiceField(stringResource(R.string.auth_country_label), state.country, state.countries,
            model::onCountryChange, enabled && !state.loadingCountries && state.countries.isNotEmpty(),
            stringResource(if (state.loadingCountries) R.string.auth_options_loading else R.string.auth_choose_country), false)
        SignUpChoiceField(stringResource(if (philippines) R.string.auth_area_ph_label else R.string.auth_area_world_label),
            state.area, state.areas.map { AddressOption(it.id, it.name) }, model::onAreaChange,
            enabled && !state.loadingAreas && state.areas.isNotEmpty(), stringResource(when {
                state.country == null -> R.string.auth_select_country_first
                state.loadingAreas -> R.string.auth_options_loading
                state.areas.isEmpty() -> R.string.auth_options_empty
                else -> R.string.auth_choose_area
            }))
        SignUpChoiceField(stringResource(R.string.auth_city_label), state.city, state.cities, model::onCityChange,
            enabled && state.area != null && state.cities.isNotEmpty(), stringResource(when {
                state.area == null -> R.string.auth_select_area_first
                state.cities.isEmpty() -> R.string.auth_options_empty
                else -> R.string.auth_choose_city
            }))
        if (philippines) SignUpChoiceField(stringResource(R.string.auth_barangay_label), state.barangay,
            state.barangays, model::onBarangayChange, enabled && !state.loadingBarangays && state.barangays.isNotEmpty(),
            stringResource(when {
                state.city == null -> R.string.auth_select_city_first
                state.loadingBarangays -> R.string.auth_options_loading
                state.barangays.isEmpty() -> R.string.auth_options_empty
                else -> R.string.auth_choose_barangay
            }))
        if (state.locationLoadFailed) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.auth_location_load_failed), Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(model::retryLocations, enabled = enabled) { Text(stringResource(R.string.auth_retry_location)) }
        }
    }
}

/** Editable dropdown anchor filters a bounded list; only selecting an option saves it. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SignUpChoiceField(
    label: String, selected: AddressOption?, options: List<AddressOption>,
    onSelected: (AddressOption?) -> Unit, enabled: Boolean, placeholder: String, allowClear: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var width by remember { mutableStateOf(240.dp) }
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val blank = stringResource(R.string.auth_options_skip)
    val choices = remember(options, query, allowClear, blank) {
        val all = if (allowClear) listOf(AddressOption("", blank)) + options else options
        all.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    }
    LaunchedEffect(selected, options, enabled) { expanded = false; query = "" }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = TitleColor)
        Spacer(Modifier.height(8.dp))
        ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = {
            if (enabled) { expanded = it; query = "" }
        }) {
            OutlinedTextField(value = if (expanded) query else selected?.name.orEmpty(),
                onValueChange = { query = it; expanded = true }, enabled = enabled,
                singleLine = true, shape = RoundedCornerShape(12.dp),
                placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded && enabled) },
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled)
                    .onSizeChanged { width = with(density) { it.width.toDp() } }
                    .semantics { contentDescription = label })
            ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = {
                expanded = false; query = ""; focus.clearFocus()
            }) {
                if (choices.isEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.auth_options_no_match)) },
                    onClick = {}, enabled = false)
                else LazyColumn(Modifier.width(width).height((choices.size.coerceAtMost(5) * 52).dp)) {
                    items(choices, key = { it.id }) { choice ->
                        DropdownMenuItem(text = { Text(choice.name) },
                            modifier = Modifier.testTag("signup-option-$label-${choice.id}"), onClick = {
                            onSelected(choice.takeIf { it.id.isNotEmpty() })
                            expanded = false; query = ""; focus.clearFocus()
                        })
                    }
                }
            }
        }
    }
}
