package com.volleyballlab.player.feature.profile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.volleyballlab.player.BuildConfig
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.data.plan.FirestorePlanRepository
import com.volleyballlab.player.data.plan.GcashCheckoutException
import com.volleyballlab.player.domain.model.*
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ProPlanCard(modifier: Modifier = Modifier, onBrowseCoaches: (() -> Unit)? = null) {
    val context = LocalContext.current
    val graph = remember { (context.applicationContext as VolleyballLabApplication).container }
    val repository = graph.planRepository as FirestorePlanRepository
    val plan by repository.plan.collectAsStateWithLifecycle(UserPlan.Free)
    val checkout by repository.checkoutConfiguration.collectAsStateWithLifecycle(null)
    val account by graph.authRepository.currentUser.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var busy by remember(account?.uid) { mutableStateOf(false) }
    var message by remember(account?.uid) { mutableStateOf<String?>(null) }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = AccountMenuSurface) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (plan.isPro) "Pro account" else "Free account", style = MaterialTheme.typography.titleMedium)
            if (account?.uid == ProEntitlement.OWNER_UID) Text("Owner Pro access", style = MaterialTheme.typography.bodySmall)
            Text("Pro includes all 15 drills and coach connection. Free includes five starter drills.", style = MaterialTheme.typography.bodySmall)
            Text(if (plan.isPro) "Unlimited detailed AI reports."
                else "${ReportAllowance.FREE_WEEKLY_LIMIT} detailed AI reports per week. The allowance resets every Monday.",
                style = MaterialTheme.typography.bodySmall)
            Text("Automatic session summaries are included with both plans after each live session.",
                style = MaterialTheme.typography.bodySmall)
            val offer = checkout
            if (!plan.isPro && offer?.enabled == true) {
                val price = BigDecimal.valueOf(offer.priceCentavos, 2).toPlainString()
                val access = if (offer.durationDays == 0L) "permanent Pro access" else "${offer.durationDays} days of Pro access"
                Text("₱$price for $access. One-time payment.", style = MaterialTheme.typography.bodySmall)
                if (account?.emailVerified == false) {
                    Text("Verify your email before paying.", style = MaterialTheme.typography.bodySmall)
                }
                Button(enabled = account?.emailVerified == true && !busy, onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        try {
                            val url = repository.createGcashCheckout(offer)
                            if (url == null) {
                                message = "Your Pro status is updating."
                            } else {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                message = "After you pay in GCash, return here to see your Pro status."
                            }
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: GcashCheckoutException) {
                            message = when (failure.code) {
                                "offer_changed" -> "The Pro offer changed. Review the updated price and try again."
                                "payment_processing" -> "Xendit is still confirming your previous checkout. Check again shortly."
                                "previous_checkout_open" -> "The offer changed while your previous checkout was open. Try again after it expires."
                                "checkout_needs_review" -> "Your previous checkout needs review. Contact support before trying to pay again."
                                "verify_email_required" -> "Verify your email before paying."
                                "invalid_session", "sign_in_required", "account_changed" -> "Sign in again to continue."
                                "checkout_not_configured" -> "GCash checkout is currently unavailable."
                                else -> "Could not open GCash checkout. Please try again."
                            }
                        } catch (_: Exception) {
                            message = "Could not open GCash checkout. Please try again."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(if (busy) "Opening checkout…" else "Pay with GCash") }
            } else if (!plan.isPro && offer != null) {
                Text("GCash checkout is currently unavailable.", style = MaterialTheme.typography.bodySmall)
            }
            val testCheckoutUrl = BuildConfig.XENDIT_TEST_CHECKOUT_URL
            if (testCheckoutUrl.isNotEmpty()) {
                Text("Test checkout uses simulated payments and won't unlock Pro.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    message = null
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(testCheckoutUrl)))
                    } catch (_: ActivityNotFoundException) {
                        message = "Install a browser to open the Xendit test checkout."
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open Xendit test checkout")
                }
            }
            if (plan.isPro && onBrowseCoaches != null) {
                OutlinedButton(onClick = onBrowseCoaches, modifier = Modifier.fillMaxWidth()) {
                    Text("Find a coach")
                }
            }
            if (!plan.isPro || testCheckoutUrl.isNotEmpty()) {
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
