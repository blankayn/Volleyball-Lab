package com.volleyballlab.player.feature.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
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
    Column(Modifier.fillMaxSize().background(Color.White).imePadding().verticalScroll(rememberScrollState()).padding(contentPadding)
        .padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        AccountPageHeader("Edit account", onBack)
        AccountIdentityEditor(onBack)
    }
}

@Composable
internal fun AccountIdentityEditor(onDone: () -> Unit) {
    val model: AuthViewModel = viewModel(factory = AuthViewModel.Factory)
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.loadAccount() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(state.firstName, model::onFirstNameChange, label = { Text("First name") },
            singleLine = true, enabled = !state.submitting, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.lastName, model::onLastNameChange, label = { Text("Last name") },
            singleLine = true, enabled = !state.submitting, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
        SignUpLocationFields(state, model)
        state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        Button(onClick = { model.saveAccount(onDone) }, enabled = !state.submitting &&
            state.firstName.isNotBlank() && state.lastName.isNotBlank() && state.country != null,
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (state.submitting) "Saving…" else "Save changes") }
        TextButton(onClick = onDone, enabled = !state.submitting, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
        ProfileSyncCard(Modifier.fillMaxWidth())
    }
}

fun countryFlag(isoCode: String): String {
    val code = isoCode.uppercase(java.util.Locale.ROOT)
    if (code.length != 2 || code.any { it !in 'A'..'Z' }) return ""
    return code.map { String(Character.toChars(0x1F1E6 + it.code - 'A'.code)) }.joinToString("")
}
