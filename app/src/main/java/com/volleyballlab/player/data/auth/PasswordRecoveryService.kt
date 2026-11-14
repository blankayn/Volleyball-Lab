package com.volleyballlab.player.data.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.functions.FirebaseFunctions
import com.volleyballlab.player.BuildConfig
import com.volleyballlab.player.data.cloud.awaitCloud
import java.net.URI
import java.util.concurrent.TimeUnit

data class RecoveryChallenge(val id: String, val resendAfterSeconds: Int)
data class VerifiedRecovery(val email: String, val actionCode: String)

interface PasswordRecoveryService {
    val usesEmailCode: Boolean get() = true
    /** Returns a challenge for code entry, or null when a reset link was emailed. */
    suspend fun sendRecovery(email: String): RecoveryChallenge?
    suspend fun verifyCode(challenge: RecoveryChallenge, code: String): VerifiedRecovery
    suspend fun resetPassword(recovery: VerifiedRecovery, password: String)
}

class FirebasePasswordRecoveryService : PasswordRecoveryService {
    override val usesEmailCode = BuildConfig.PASSWORD_RECOVERY_URL.isNotBlank()

    override suspend fun sendRecovery(email: String): RecoveryChallenge? {
        if (!usesEmailCode) {
            try {
                FirebaseAuth.getInstance().sendPasswordResetEmail(email.trim()).awaitResult()
            } catch (failure: FirebaseAuthException) {
                // Keep the same response for addresses without an account.
                if (failure.errorCode != "ERROR_USER_NOT_FOUND") throw failure
            }
            return null
        }
        val result = call("sendCode", mapOf("email" to email.trim()))
        return RecoveryChallenge(
            id = requireNotNull(result["challengeId"] as? String),
            resendAfterSeconds = (result["resendAfterSeconds"] as? Number)?.toInt()?.coerceIn(0, 120) ?: 60,
        )
    }

    override suspend fun verifyCode(challenge: RecoveryChallenge, code: String): VerifiedRecovery {
        val result = call("verifyCode", mapOf("challengeId" to challenge.id, "code" to code))
        val actionCode = requireNotNull(result["actionCode"] as? String)
        val auth = FirebaseAuth.getInstance()
        check(auth.currentUser == null) { "Return to sign in before resetting your password." }
        val email = auth.verifyPasswordResetCode(actionCode).awaitResult()
        check(email.equals(result["email"] as? String, ignoreCase = true))
        return VerifiedRecovery(email, actionCode)
    }

    override suspend fun resetPassword(recovery: VerifiedRecovery, password: String) {
        val auth = FirebaseAuth.getInstance()
        check(auth.currentUser == null) { "Return to sign in before resetting your password." }
        auth.confirmPasswordReset(recovery.actionCode, password).awaitResult()
    }

    private suspend fun call(path: String, data: Map<String, String>): Map<*, *> {
        val base = BuildConfig.PASSWORD_RECOVERY_URL
        check(base.isNotBlank()) { "Password recovery is being configured." }
        val endpoint = URI(base).resolve("/$path").toURL()
        val callable = FirebaseFunctions.getInstance("asia-southeast1").getHttpsCallableFromUrl(endpoint)
        callable.setTimeout(25, TimeUnit.SECONDS)
        return requireNotNull(callable.call(data).awaitCloud().data as? Map<*, *>)
    }
}
