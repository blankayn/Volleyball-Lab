package com.volleyballlab.player.data.plan

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.volleyballlab.player.data.cloud.awaitCloud
import com.volleyballlab.player.data.cloud.snapshots
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.PlanRepository
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class FirestorePlanRepository(private val db: FirebaseFirestore, private val auth: AuthRepository) : PlanRepository {
    val checkoutConfiguration: Flow<ProCheckoutConfiguration?> = auth.currentUser
        .map { it?.uid }.distinctUntilChanged().flatMapLatest { uid ->
            if (uid == null) flowOf(null) else checkoutForAccount()
        }

    private fun checkoutForAccount() = db.collection("billingConfig").document("pro").snapshots().map { document ->
        val fields = document?.data.orEmpty()
        val price = fields["priceCentavos"] as? Long ?: 0
        val days = fields["durationDays"] as? Long ?: -1
        ProCheckoutConfiguration(
            enabled = fields["enabled"] == true && fields["liveMode"] == true &&
                price in 100L..10_000_000L && days in 0L..365L && httpsUri(fields["returnUrl"] as? String) != null,
            priceCentavos = price,
            durationDays = days,
        )
    }
    override val plan: Flow<UserPlan> = auth.currentUser.flatMapLatest { user ->
        if (user == null) flowOf(UserPlan.Free)
        else if (user.uid == ProEntitlement.OWNER_UID) flowOf(UserPlan.Pro)
        else combine(db.collection("entitlements").document(user.uid).snapshots(), flow {
            while (true) { emit(System.currentTimeMillis()); delay(60_000) }
        }) { document, now ->
            ProEntitlement.plan(user.uid, document?.getString("status"), (document?.get("expiresAtEpochMs") as? Number)?.toLong(), now)
        }.distinctUntilChanged()
    }
    /** Legacy callers cannot grant or revoke a server entitlement. */
    override suspend fun upgradeToPro() = Unit
    override suspend fun downgradeToFree() = Unit

    /** Returns the hosted URL, or null if the server confirms the account already has Pro. */
    suspend fun createGcashCheckout(offer: ProCheckoutConfiguration): String? {
        val user = FirebaseAuth.getInstance().currentUser ?: throw GcashCheckoutException("sign_in_required")
        val token = user.getIdToken(true).awaitCloud().token ?: throw GcashCheckoutException("invalid_session")
        val result = withContext(Dispatchers.IO) {
            val connection = URL(CHECKOUT_ENDPOINT).openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 50_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            try {
                val body = buildJsonObject {
                    put("priceCentavos", offer.priceCentavos)
                    put("durationDays", offer.durationDays)
                }
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                val success = connection.responseCode in 200..299
                val response = if (success) connection.inputStream else connection.errorStream
                val payload = response?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (!success) {
                    val code = runCatching { Json.parseToJsonElement(payload).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                    throw GcashCheckoutException(code ?: "checkout_unavailable")
                }
                Json.parseToJsonElement(payload).jsonObject
            } finally { connection.disconnect() }
        }
        if (auth.currentUid != user.uid) throw GcashCheckoutException("account_changed")
        if (result["status"]?.jsonPrimitive?.content == "pro_active") return null
        val url = result["checkoutUrl"]?.jsonPrimitive?.content
        val uri = httpsUri(url)
        if (uri == null || uri.host !in XENDIT_CHECKOUT_HOSTS || uri.port !in listOf(-1, 443)) {
            throw GcashCheckoutException("invalid_checkout_url")
        }
        return url
    }

    private companion object {
        const val CHECKOUT_ENDPOINT = "https://volleyball-lab-payments.volleyball-lab-session-ai.workers.dev/createGcashCheckout"
        val XENDIT_CHECKOUT_HOSTS = setOf("checkout.xendit.co", "xen.to")

        fun httpsUri(value: String?): URI? = value?.let {
            runCatching { URI(it) }.getOrNull()?.takeIf { uri ->
                uri.scheme == "https" && uri.host != null && uri.userInfo == null
            }
        }
    }
}

class GcashCheckoutException(val code: String) : Exception(code)
