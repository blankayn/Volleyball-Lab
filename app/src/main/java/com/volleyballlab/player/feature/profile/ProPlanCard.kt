package com.volleyballlab.player.feature.profile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.data.plan.FirestorePlanRepository
import com.volleyballlab.player.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ProPlanCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val graph = remember { (context.applicationContext as VolleyballLabApplication).container }
    val repository = graph.planRepository as FirestorePlanRepository
    val plan by repository.plan.collectAsStateWithLifecycle(UserPlan.Free)
    val config by repository.checkoutConfiguration.collectAsStateWithLifecycle(ProCheckoutConfiguration())
    val reports by graph.trainingWorkspaceRepository.reports.collectAsStateWithLifecycle(emptyList())
    val account by graph.authRepository.currentUser.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
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
                if (config.ready) {
                    val price = String.format(locale, "₱%.2f", config.priceCentavos / 100.0)
                    Text("$price · ${if (config.durationDays == 0) "Permanent access" else "${config.durationDays} days of access"}")
                    Text("GCash checkout opens securely in your browser. Return here after payment to view your plan.", style = MaterialTheme.typography.bodySmall)
                } else Text("GCash purchase setup is pending. Purchases will open when the merchant account and price are configured.", style = MaterialTheme.typography.bodySmall)
                Button(enabled = config.ready && account?.emailVerified == true && !busy, onClick = {
                    busy = true
                    scope.launch {
                        try { val url = repository.createGcashCheckout(); context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        catch (failure: Exception) { if (failure is CancellationException) throw failure; message = failure.message ?: "Checkout is unavailable." }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "Opening checkout…" else "Purchase Pro with GCash") }
                if (config.ready && account?.emailVerified != true) Text("Verify your email in Account security to purchase Pro.", style = MaterialTheme.typography.bodySmall)
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
