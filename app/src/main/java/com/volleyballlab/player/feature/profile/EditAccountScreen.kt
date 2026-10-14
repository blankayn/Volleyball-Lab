package com.volleyballlab.player.feature.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.volleyballlab.player.feature.auth.AuthViewModel
import com.volleyballlab.player.feature.auth.SignUpLocationFields
import androidx.compose.ui.res.stringResource

@Composable
fun EditAccountScreen(onBack: () -> Unit, contentPadding: PaddingValues) {
    val model: AuthViewModel = viewModel(factory = AuthViewModel.Factory)
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.loadAccount() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding)
        .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row {
            IconButton(onClick = onBack, enabled = !state.submitting) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
            }
            Text("Edit account", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        }
        Text("Your profile card shows your name and country flag. Your address stays private.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(state.firstName, model::onFirstNameChange, label = { Text("First name") },
            singleLine = true, enabled = !state.submitting, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.lastName, model::onLastNameChange, label = { Text("Last name") },
            singleLine = true, enabled = !state.submitting, modifier = Modifier.fillMaxWidth())
        SignUpLocationFields(state, model)
        state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        Button(onClick = { model.saveAccount(onBack) }, enabled = !state.submitting &&
            state.firstName.isNotBlank() && state.lastName.isNotBlank() && state.country != null,
            modifier = Modifier.fillMaxWidth()) { Text(if (state.submitting) "Loading…" else "Save changes") }
        ProfileSyncCard(Modifier.fillMaxWidth())
    }
}

fun countryFlag(isoCode: String): String {
    val code = isoCode.uppercase(java.util.Locale.ROOT)
    if (code.length != 2 || code.any { it !in 'A'..'Z' }) return ""
    return code.map { String(Character.toChars(0x1F1E6 + it.code - 'A'.code)) }.joinToString("")
}
