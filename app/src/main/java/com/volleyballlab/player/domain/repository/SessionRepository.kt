package com.volleyballlab.player.domain.repository

import com.volleyballlab.player.domain.model.SessionResult
import kotlinx.coroutines.flow.Flow

/**
 * Storage for completed training sessions.
 *
 * ## Offline first, by requirement rather than by preference
 *
 * Capture and analysis run entirely on-device, so a session is complete and
 * scored before any network is involved. Saving must therefore succeed with the
 * phone in aeroplane mode in a gym basement, and sync is a later, separate
 * concern. An implementation that needs a network to accept a save is wrong for
 * this app no matter how convenient it is.
 *
 * ## Why [sessions] is a Flow
 *
 * The Progress screen belongs to another team member and will observe this.
 * Exposing a stream rather than a suspend list means their screen updates when a
 * session is saved without the two features having to know about each other.
 */
interface SessionRepository {

    /** All stored sessions, newest first. Emits again whenever the set changes. */
    val sessions: Flow<List<SessionResult>>

    suspend fun save(session: SessionResult)

    suspend fun byId(id: String): SessionResult?

    suspend fun delete(id: String)

    /** Removes the private recording but keeps the scored session. */
    suspend fun deleteVideo(id: String)
}
