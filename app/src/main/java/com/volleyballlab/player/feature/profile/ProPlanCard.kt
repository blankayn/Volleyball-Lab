package com.volleyballlab.player.feature.profile

import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.Icons
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
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = AccountMenuSurface) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (plan.isPro) "Pro account" else "Free account", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(50), color = if (plan.isPro) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Text(if (plan.isPro) "PRO" else "FREE", Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        color = if (plan.isPro) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (ProEntitlement.isGranted(account?.uid)) Text(if (account?.uid == ProEntitlement.OWNER_UID) "Owner Pro access" else "Pro access granted by the owner", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PlanLine("Pro includes all 15 drills and coach connection. Free includes five starter drills.")
                PlanLine(if (plan.isPro) "Unlimited detailed AI reports."
                    else "${ReportAllowance.FREE_WEEKLY_LIMIT} detailed AI reports per week. The allowance resets every Monday.")
                PlanLine("Automatic session summaries are included with both plans after each live session.")
            }
            val testCheckoutUrl = BuildConfig.XENDIT_TEST_CHECKOUT_URL
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
                }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Opening checkout…" else "Checkout") }
            } else if (testCheckoutUrl.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        message = null
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(testCheckoutUrl)))
                        } catch (_: ActivityNotFoundException) {
                            message = "Install a browser to open checkout."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Checkout")
                }
            } else if (!plan.isPro && offer != null) {
                Text("GCash checkout is currently unavailable.", style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun PlanLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
