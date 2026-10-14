package com.volleyballlab.player.feature.profile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.firebase.functions.FirebaseFunctionsException
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.data.auth.GoogleIdResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun DeleteAccountCard(onDeleted: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = (context.applicationContext as VolleyballLabApplication).container
    val account by container.authRepository.currentUser.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    val model: AccountDeletionViewModel = viewModel(factory = AccountDeletionViewModel.Factory)
    val deletion by model.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val busy = confirming || deletion.busy
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(deletion.deleted) { if (deletion.deleted) { open = false; password = ""; onDeleted() } }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(20.dp)) {
            Text("Delete account", style = MaterialTheme.typography.titleMedium)
            Text("Permanently remove your account, profile, recordings, and coach connections.",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { open = true; error = null }, enabled = account != null) {
                Text("Delete my account", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (open) AlertDialog(onDismissRequest = { if (!busy) { open = false; password = "" } },
        title = { Text("Permanently delete account?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This removes your saved training data and cannot be undone. Type DELETE to confirm.")
                OutlinedTextField(confirmation, { confirmation = it }, label = { Text("Confirmation") }, enabled = !busy, singleLine = true)
                if (account?.passwordProvider == true) OutlinedTextField(password, { password = it },
                    label = { Text("Current password") }, visualTransformation = PasswordVisualTransformation(), enabled = !busy, singleLine = true)
                else Text("Confirm your Google account to continue.")
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Removing account data…") }
                (error ?: deletion.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(enabled = !busy && confirmation == "DELETE" &&
                (account?.passwordProvider == false || password.isNotBlank()), onClick = deleteClick@{
                val expectedUid = account?.uid ?: return@deleteClick
                confirming = true; error = null
                scope.launch {
                    try {
                        var token: String? = null
                        if (account?.passwordProvider == false) when (val result = container.googleCredentialClient.requestIdToken(context)) {
                            is GoogleIdResult.Success -> token = result.idToken
                            is GoogleIdResult.Cancelled -> return@launch
                            is GoogleIdResult.Failure -> error("Google confirmation failed. Try again.")
                        }
                        model.delete(expectedUid, password.takeIf { token == null }, token)
                        password = ""
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        error = if (failure is FirebaseFunctionsException && failure.code == FirebaseFunctionsException.Code.NOT_FOUND)
                            "Account deletion is awaiting server activation. Your account has not been deleted."
                        else "Deletion did not finish. Check your password and connection, then retry."
                    } finally { confirming = false }
                }
            }) { Text("Delete permanently", color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton(onClick = { open = false; password = "" }, enabled = !busy) { Text("Cancel") } })
}
