package com.volleyballlab.player.feature.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.domain.repository.AuthOutcome
import kotlinx.coroutines.launch

/** Shared by the athlete and coach account screens. Passwords never enter saved state. */
@Composable
fun AccountSecurityCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val auth = remember { (context.applicationContext as VolleyballLabApplication).container.authRepository }
    val account by auth.currentUser.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    var old by remember { mutableStateOf("") }
    var fresh by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    fun request(success: String, block: suspend () -> AuthOutcome) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                message = when (val outcome = block()) {
                    AuthOutcome.Success -> success
                    AuthOutcome.Cancelled -> null
                    is AuthOutcome.Failure -> resources.getString(outcome.error.message)
                }
            } finally { busy = false }
        }
    }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Security", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(if (account?.emailVerified == true) "Email verified" else "Email not verified", style = MaterialTheme.typography.bodyMedium)
            if (account?.emailVerified == false) {
                Text("Confirm your email address to verify your account.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !busy, onClick = { request("Verification email sent. Check your inbox.", auth::sendVerificationEmail) }) { Text("Send verification email") }
                TextButton(enabled = !busy, onClick = { request("Account status refreshed.", auth::refreshAccount) }) { Text("Refresh verification status") }
            }
            if (account?.passwordProvider == true) {
                OutlinedButton(enabled = !busy, onClick = { changing = true; message = null }) { Text("Change password") }
            } else if (account != null) Text("Manage your password with your sign-in provider.", style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    if (changing) AlertDialog(
        onDismissRequest = { if (!busy) { changing = false; old = ""; fresh = ""; confirm = "" } },
        title = { Text("Change password") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(old, { old = it }, label = { Text("Current password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(fresh, { fresh = it }, label = { Text("New password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(confirm, { confirm = it }, label = { Text("Confirm new password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Text("At least 6 characters. Your current password confirms ownership.", style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it) }
        } },
        confirmButton = { TextButton(enabled = !busy && old.isNotEmpty() && fresh.length >= 6 && fresh == confirm, onClick = {
            request("Password updated.") {
                val outcome = auth.changePassword(old, fresh)
                if (outcome == AuthOutcome.Success) { changing = false; old = ""; fresh = ""; confirm = "" }
                outcome
            }
        }) { Text("Update") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { changing = false; old = ""; fresh = ""; confirm = "" }) { Text("Cancel") } },
    )
}
