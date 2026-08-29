package com.volleyballlab.player.domain.repository

import com.volleyballlab.player.domain.model.UserPlan
import kotlinx.coroutines.flow.Flow

/**
 * Which tier the signed-in account is on.
 *
 * ## There is no payment here, and there must not appear to be
 *
 * [upgradeToPro] flips a local flag. It takes no card, contacts no processor and
 * charges nobody, and every screen that reaches it is required to say so before it
 * is tapped. This is a capstone prototype: a convincing-looking checkout that
 * quietly did nothing would be the single most dishonest thing in the app, and a
 * *real* one is out of scope by an order of magnitude.
 *
 * When billing does land - Play Billing, most likely - this interface is what it
 * implements, and the only thing that changes above it is that [upgradeToPro]
 * becomes slow and can fail.
 *
 * ## Free is the safe default
 *
 * Every read that cannot answer returns [UserPlan.Free]. Erring toward the lower
 * tier costs a user who has upgraded one tap to fix; erring the other way gives
 * the paid feature away to everyone whose file happened not to load.
 */
interface PlanRepository {

    /** The current account's plan. Emits again on sign-in, sign-out and upgrade. */
    val plan: Flow<UserPlan>

    /**
     * Grants Pro on this device.
     *
     * Named for what it does rather than for what it stands in for - `purchasePro`
     * would be a lie in the method name, which is the hardest kind to notice later.
     */
    suspend fun upgradeToPro()

    /**
     * Drops back to Free.
     *
     * Exists so the upgrade can be undone while demonstrating the app - the locked
     * state is half of what the Pro gate is worth showing, and without this the
     * only way back to it is clearing app data.
     */
    suspend fun downgradeToFree()
}
