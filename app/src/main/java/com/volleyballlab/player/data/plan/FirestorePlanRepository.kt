package com.volleyballlab.player.data.plan

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.volleyballlab.player.data.cloud.awaitCloud
import com.volleyballlab.player.data.cloud.snapshots
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.PlanRepository
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
    val checkoutConfiguration = db.collection("billingConfig").document("pro").snapshots().map { document ->
        val fields = document?.data.orEmpty()
        ProCheckoutConfiguration(fields["enabled"] == true, (fields["priceCentavos"] as? Number)?.toLong() ?: 0,
            (fields["durationDays"] as? Number)?.toInt() ?: 0, fields["liveMode"] == true)
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

    suspend fun createGcashCheckout(): String {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in before purchasing Pro.")
        require(user.isEmailVerified) { "Verify your email before purchasing Pro." }
        require(checkoutConfiguration.first().ready) { "GCash checkout is not configured yet." }
        val token = user.getIdToken(false).awaitCloud().token ?: error("Sign in again to continue.")
        val url = withContext(Dispatchers.IO) {
            val connection = URL(CHECKOUT_ENDPOINT).openConnection() as HttpsURLConnection
            connection.requestMethod = "POST"; connection.connectTimeout = 15_000; connection.readTimeout = 15_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            try {
                connection.outputStream.use { it.write("{}".toByteArray()) }
                if (connection.responseCode !in 200..299) error("Checkout is unavailable. Please retry later.")
                val result = Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
                result["checkoutUrl"]?.jsonPrimitive?.content ?: error("Checkout could not be opened.")
            } finally { connection.disconnect() }
        }
        require(URL(url).let { it.protocol == "https" && it.host == "checkout.paymongo.com" }) { "Checkout returned an invalid address." }
        require(auth.currentUid == user.uid) { "Your account changed. Please start checkout again." }
        return url
    }
    private companion object { const val CHECKOUT_ENDPOINT = "https://asia-southeast1-volleyball-lab.cloudfunctions.net/createGcashCheckout" }
}
