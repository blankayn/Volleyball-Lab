package com.volleyballlab.player.feature.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.data.plan.FirestorePlanRepository
import com.volleyballlab.player.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ProPlanCard(modifier: Modifier = Modifier, onBrowseCoaches: (() -> Unit)? = null) {
    val context = LocalContext.current
    val graph = remember { (context.applicationContext as VolleyballLabApplication).container }
    val repository = graph.planRepository as FirestorePlanRepository
    val plan by repository.plan.collectAsStateWithLifecycle(UserPlan.Free)
    val reports by graph.trainingWorkspaceRepository.reports.collectAsStateWithLifecycle(emptyList())
    val account by graph.authRepository.currentUser.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showCodeDialog by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    val dayFlow = remember { com.volleyballlab.player.ui.component.calendarDayFlow() }
    val today by dayFlow.collectAsStateWithLifecycle(java.time.LocalDate.now())
    val usage = ReportAllowance.usage(reports, plan, today)
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (plan.isPro) "Pro account" else "Free account", style = MaterialTheme.typography.titleMedium)
            if (account?.uid == ProEntitlement.OWNER_UID) Text("Owner Pro access", style = MaterialTheme.typography.bodySmall)
            Text(if (usage.unlimited) "Unlimited detailed reports" else "${usage.remaining}/${usage.limit} detailed reports left this week")
            Text("Pro includes all 15 drills, unlimited detailed reports, and coach connection. Free includes five starter drills.", style = MaterialTheme.typography.bodySmall)
            if (!plan.isPro) {
                Text("Enter an access code to activate Pro. No payment is collected.", style = MaterialTheme.typography.bodySmall)
                Button(enabled = account != null && !busy, onClick = { code = ""; message = null; showCodeDialog = true }) {
                    Text("Subscribe to Pro")
                }
            }
            if (plan.isPro && onBrowseCoaches != null) {
                OutlinedButton(onClick = onBrowseCoaches, modifier = Modifier.fillMaxWidth()) {
                    Text("Find a coach")
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (showCodeDialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) showCodeDialog = false },
            title = { Text("Subscribe to Pro") },
            text = {
                Column {
                    Text("Enter your temporary access code. No payment is collected.")
                    OutlinedTextField(value = code, onValueChange = { code = it.filter(Char::isDigit).take(6); message = null },
                        label = { Text("Access code") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), enabled = !busy)
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = code.length == 6 && !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            repository.activateProWithCode(code)
                            showCodeDialog = false
                            message = "Pro activated."
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            message = failure.message ?: "Could not activate Pro. Please try again."
                        } finally { busy = false }
                    }
                }) { Text(if (busy) "Activating…" else "Activate Pro") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { showCodeDialog = false; message = null }) { Text("Cancel") } },
        )
    }
}
