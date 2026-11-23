package com.volleyballlab.player.feature.profile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = AccountMenuSurface) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your plan", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Text(if (plan.isPro) "Pro" else "Free", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold)
                }
                Surface(shape = RoundedCornerShape(50), color = if (plan.isPro) colors.primary else colors.surfaceContainerHighest) {
                    Text(if (plan.isPro) "PRO" else "FREE", Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        color = if (plan.isPro) colors.onPrimary else colors.onSurfaceVariant)
                }
            }
            if (ProEntitlement.isGranted(account?.uid)) {
                Text(if (account?.uid == ProEntitlement.OWNER_UID) "Owner Pro access" else "Pro access granted by the owner",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }

            PlanComparison(isPro = plan.isPro)
            if (!plan.isPro) {
                Text("Free reports reset every Monday.", style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }

            val offer = checkout
            val testCheckoutUrl = BuildConfig.XENDIT_TEST_CHECKOUT_URL
            // Development builds only: a fixed Xendit link that opens checkout but cannot grant Pro.
            fun openTestCheckout() {
                message = null
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(testCheckoutUrl)))
                } catch (_: ActivityNotFoundException) {
                    message = "Install a browser to open checkout."
                }
            }
            if (testCheckoutUrl.isNotEmpty() || (!plan.isPro && offer != null)) {
                Surface(shape = RoundedCornerShape(18.dp), color = colors.primary.copy(alpha = .08f),
                    border = BorderStroke(1.dp, colors.primary.copy(alpha = .25f))) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (plan.isPro) "Pro payment" else "Upgrade to Pro", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (!plan.isPro && offer?.enabled == true) {
                            val price = if (offer.priceCentavos % 100 == 0L) (offer.priceCentavos / 100).toString()
                                else BigDecimal.valueOf(offer.priceCentavos, 2).toPlainString()
                            val access = if (offer.durationDays == 0L) "lifetime access" else "${offer.durationDays} days"
                            Text("₱$price · $access · one-time GCash payment", style = MaterialTheme.typography.bodyMedium)
                            if (account?.emailVerified == false) {
                                Text("Verify your email before paying.", style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant)
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
                                        if (failure.code == "checkout_not_configured" && testCheckoutUrl.isNotEmpty()) openTestCheckout()
                                        else message = when (failure.code) {
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
                            Button(onClick = ::openTestCheckout, modifier = Modifier.fillMaxWidth()) { Text("Checkout") }
                        } else {
                            Text("GCash checkout is currently unavailable.", style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant)
                        }
                        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (plan.isPro && onBrowseCoaches != null) {
                OutlinedButton(onClick = onBrowseCoaches, modifier = Modifier.fillMaxWidth()) {
                    Text("Find a coach")
                }
            }
        }
    }
}

private sealed interface PlanValue {
    data class Label(val text: String) : PlanValue
    data object Included : PlanValue
    data object NotIncluded : PlanValue
}

private data class PlanRow(val feature: String, val free: PlanValue, val pro: PlanValue)

private val planRows = listOf(
    PlanRow("Drills", PlanValue.Label("5"), PlanValue.Label("All 15")),
    PlanRow("Detailed AI reports", PlanValue.Label("${ReportAllowance.FREE_WEEKLY_LIMIT} a week"), PlanValue.Label("Unlimited")),
    PlanRow("Session summaries", PlanValue.Included, PlanValue.Included),
    PlanRow("Coach connection", PlanValue.NotIncluded, PlanValue.Included),
)

/**
 * Each feature sits above its Free and Pro values, so no label shares a line with them.
 * Nothing has a fixed height: text wraps and the pair grows together. On a very narrow
 * screen or with enlarged text the two values stack instead of sharing the width.
 */
@Composable
private fun PlanComparison(isPro: Boolean) {
    val stacked = LocalDensity.current.fontScale > 1.15f || LocalConfiguration.current.screenWidthDp < 340
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        planRows.forEach { row ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.feature, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (stacked) {
                    PlanCell("Free", row.free, current = !isPro, Modifier.fillMaxWidth())
                    PlanCell("Pro", row.pro, current = isPro, Modifier.fillMaxWidth())
                } else {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlanCell("Free", row.free, current = !isPro, Modifier.weight(1f).fillMaxHeight())
                        PlanCell("Pro", row.pro, current = isPro, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanCell(plan: String, value: PlanValue, current: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = colors.surface.copy(alpha = if (current) 1f else .45f),
        border = if (current) BorderStroke(1.5.dp, colors.primary) else null,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(plan, style = MaterialTheme.typography.labelSmall,
                color = if (current) colors.primary else colors.onSurfaceVariant)
            Text(
                when (value) {
                    is PlanValue.Label -> value.text
                    PlanValue.Included -> "Included"
                    PlanValue.NotIncluded -> "Not included"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (value == PlanValue.NotIncluded) colors.onSurfaceVariant else colors.onSurface,
            )
        }
    }
}
