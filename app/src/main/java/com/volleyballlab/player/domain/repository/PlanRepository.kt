package com.volleyballlab.player.domain.repository

import com.volleyballlab.player.domain.model.UserPlan
import kotlinx.coroutines.flow.Flow

/** The signed-in account's tier. Production reads trusted owner/server entitlements. */
interface PlanRepository {
    /** Emits on account changes and entitlement updates; unknown access defaults to Free. */
    val plan: Flow<UserPlan>

    /** Legacy demonstration operation. Production implementations cannot grant paid access. */
    suspend fun upgradeToPro()

    /** Legacy demonstration operation. Production implementations cannot revoke server access. */
    suspend fun downgradeToFree()
}
