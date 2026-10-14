package com.volleyballlab.player.domain.model

object ProEntitlement {
    /** Confirmed against Firebase Authentication for kirt@gmail.com, 28 September 2026. */
    const val OWNER_UID = "4j06UlYqbrbLzujmfi8VW5yEIOq2"
    fun plan(uid: String?, status: String?, expiresAtEpochMs: Long?, nowEpochMs: Long): UserPlan = when {
        uid == OWNER_UID -> UserPlan.Pro
        uid == null || status != "active" -> UserPlan.Free
        expiresAtEpochMs == 0L || (expiresAtEpochMs ?: -1L) > nowEpochMs -> UserPlan.Pro
        else -> UserPlan.Free
    }
}

data class ProCheckoutConfiguration(val enabled: Boolean = false, val priceCentavos: Long = 0,
    val durationDays: Int = 0, val liveMode: Boolean = false) {
    val ready: Boolean get() = enabled && liveMode && priceCentavos >= 100 && durationDays in 0..365
}
