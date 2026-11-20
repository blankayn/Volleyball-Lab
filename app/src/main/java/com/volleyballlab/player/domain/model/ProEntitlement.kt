package com.volleyballlab.player.domain.model

object ProEntitlement {
    /** Confirmed against Firebase Authentication for kirt@gmail.com, 28 September 2026. */
    const val OWNER_UID = "4j06UlYqbrbLzujmfi8VW5yEIOq2"

    /**
     * Accounts given Pro by the owner without a payment, checked in the app only.
     * Server rules still need a paid entitlement for the Pro-only coach request.
     */
    val GRANTED_UIDS: Set<String> = setOf(
        OWNER_UID,
        // orimarogio ("V Cirni"), added at the owner's request, 17 November 2026.
        "nqECw5SDFrQEX5ksP4eGODBq4dV2",
    )

    fun isGranted(uid: String?): Boolean = uid != null && uid in GRANTED_UIDS

    fun plan(uid: String?, status: String?, expiresAtEpochMs: Long?, nowEpochMs: Long): UserPlan = when {
        isGranted(uid) -> UserPlan.Pro
        uid == null || status != "active" -> UserPlan.Free
        expiresAtEpochMs == 0L || (expiresAtEpochMs ?: -1L) > nowEpochMs -> UserPlan.Pro
        else -> UserPlan.Free
    }
}

data class ProCheckoutConfiguration(
    val enabled: Boolean,
    val priceCentavos: Long,
    val durationDays: Long,
)
