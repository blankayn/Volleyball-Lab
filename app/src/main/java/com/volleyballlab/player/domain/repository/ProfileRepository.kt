package com.volleyballlab.player.domain.repository

import com.volleyballlab.player.domain.model.AthleteProfile
import kotlinx.coroutines.flow.Flow

/**
 * The athlete's own details.
 *
 * ## Why this one does persist, when sessions do not
 *
 * Sessions are held in memory pending conflict 4 in the capstone document, which
 * is a real decision about a real schema and is not this file's to pre-empt. A
 * profile is different: three optional fields with no relational shape and no
 * sync story worth arguing about, and a name that vanishes on every cold start is
 * not a placeholder for a better implementation, it is just broken. So this is
 * backed by DataStore today.
 *
 * The interface still exists so that when accounts arrive, the local store
 * becomes a cache behind the same shape rather than a thing to be torn out.
 */
interface ProfileRepository {

    /** The current profile. Emits again on every edit. */
    val profile: Flow<AthleteProfile>

    suspend fun save(profile: AthleteProfile)
}
