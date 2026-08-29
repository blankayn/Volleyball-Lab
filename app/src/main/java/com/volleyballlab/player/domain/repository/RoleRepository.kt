package com.volleyballlab.player.domain.repository

import com.volleyballlab.player.domain.model.UserRole
import kotlinx.coroutines.flow.Flow

/**
 * Which shell the signed-in account sees.
 *
 * ## Why a repository and not a field on the profile
 *
 * [ProfileRepository] holds an *athlete's* details, and a coach has none. Putting a
 * role on it would make every coach carry an empty court position around, and would
 * mean the navigation graph - which needs the role on the very first frame after
 * login - depends on a store that exists to serve one tab.
 *
 * Keeping it separate also matches where it is going. The document puts `User_Role`
 * on the User table (Table 7), so this becomes a read of the account once conflict 4
 * is settled and the profile store stays exactly as it is.
 *
 * ## The default is [UserRole.Player], deliberately
 *
 * An account this device has never seen reads as a player. That is the safe
 * direction: a coach who lands in the player shell is one tap from fixing it in
 * Profile, whereas defaulting the other way would drop every new athlete into a
 * dashboard about other people's training.
 */
interface RoleRepository {

    /** The current account's role. Emits again on sign-in, sign-out and switch. */
    val role: Flow<UserRole>

    /** Reads once, for callers that cannot wait on a flow - startup routing. */
    suspend fun current(): UserRole

    suspend fun setRole(role: UserRole)
}
