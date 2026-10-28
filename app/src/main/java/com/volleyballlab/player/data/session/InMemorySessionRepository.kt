package com.volleyballlab.player.data.session

import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Sessions held in memory for the lifetime of the process.
 *
 * ## This is a placeholder, and it is labelled as one on purpose
 *
 * Sessions do not survive the app being killed. That is not an oversight and it
 * is not something to fix by quietly adding a database here - the persistence
 * decision is blocked on conflict 2 in the capstone document, where the network
 * model specifies Cloud Firestore and the data dictionary specifies MySQL tables.
 *
 * Writing a Room schema now would mean picking a winner on the team's behalf, and
 * a half-committed schema is harder to walk back than none. So the training
 * feature is built against [SessionRepository], this satisfies it well enough to
 * run the app end to end, and whichever backing store the team lands on becomes
 * one new class implementing the same interface.
 *
 * The next implementation must keep two properties this one already has:
 * [sessions] emits on every change, and [save] does not require a network.
 */
class InMemorySessionRepository : SessionRepository {

    private val state = MutableStateFlow<List<SessionResult>>(emptyList())

    override val sessions: Flow<List<SessionResult>> = state.asStateFlow()

    override suspend fun save(session: SessionResult) {
        state.update { current ->
            // Replace by id rather than append, so re-saving a session after a
            // threshold change updates it instead of duplicating it.
            (current.filterNot { it.id == session.id } + session)
                .sortedByDescending { it.startedAtEpochMs }
        }
    }

    override suspend fun byId(id: String): SessionResult? =
        state.value.firstOrNull { it.id == id }

    override suspend fun delete(id: String) {
        state.update { current -> current.filterNot { it.id == id } }
    }

    override suspend fun deleteVideo(id: String) {
        state.update { current ->
            current.map { if (it.id == id) it.copy(videoPath = null) else it }
        }
    }
}
