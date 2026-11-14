package com.volleyballlab.player.data.session

import com.volleyballlab.player.data.cloud.CloudSchema
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.CueScore
import com.volleyballlab.player.domain.model.RepResult
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.drilltest.DrillTestAuth
import com.volleyballlab.player.drilltest.DrillTestStore
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionHistoryRestoreTest {
    private val saved = SessionResult(
        "saved", Skill.Setting, "set", 1_000, 500,
        listOf(RepResult(0, 0, 250, 500, listOf(CueScore(CueId.SetHandsAboveForehead, 0.5f, 0.8f)))),
    )

    @Test fun `sign in after reinstall restores saved cloud sessions to local history`() = runTest {
        val auth = DrillTestAuth().apply { switch(null) }
        val store = DrillTestStore()
        val cloud = TestCloud { uid ->
            listOfNotNull(CloudSchema.sessionOf(CloudSchema.sessionDoc(saved, uid, emptyList(), 1_000)))
        }
        val repository = DataStoreSessionRepository(store, auth, cloud, {}, backgroundScope)

        auth.switch("player")
        runCurrent()

        assertEquals(listOf(saved), repository.sessions.first())
        assertEquals(saved, repository.byId(saved.id))
        assertEquals(listOf(saved), DataStoreSessionRepository(store, auth, null, {}, backgroundScope).sessions.first())
        assertEquals(emptyList<String>(), cloud.uploads)
    }

    @Test fun `retry merges missing scores without replacing local records or reviving a deletion`() = runTest {
        val auth = DrillTestAuth()
        val local = saved.copy(id = "local", videoPath = "local.mp4", assignmentId = "assignment")
        val deleted = saved.copy(id = "deleted")
        var attempts = 0
        val cloud = TestCloud {
            if (++attempts == 1) throw IOException("Offline")
            listOf(local.copy(videoPath = null, assignmentId = null), deleted, saved.copy(startedAtEpochMs = 2_000))
        }
        val repository = DataStoreSessionRepository(DrillTestStore(), auth, cloud, {}, backgroundScope)
        repository.save(local)
        runCurrent()
        assertEquals(listOf(local), repository.sessions.first())

        repository.delete(deleted.id)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf(saved.copy(startedAtEpochMs = 2_000), local), repository.sessions.first())
        assertEquals(listOf(local.id), cloud.uploads)
    }

    @Test fun `switching accounts discards the previous account restore`() = runTest {
        val auth = DrillTestAuth("original")
        val original = CompletableDeferred<List<SessionResult>>()
        val other = saved.copy(id = "other")
        val cloud = TestCloud { uid -> if (uid == "original") original.await() else listOf(other) }
        val repository = DataStoreSessionRepository(DrillTestStore(), auth, cloud, {}, backgroundScope)
        runCurrent()

        auth.switch("other")
        runCurrent()
        original.complete(listOf(saved))
        runCurrent()

        assertEquals(listOf(other), repository.sessions.first())
        assertEquals(emptyList<SessionResult>(), repository.sessionsOf("original").first())
    }

    private class TestCloud(private val load: suspend (String) -> List<SessionResult>) : SessionCloudStore {
        val uploads = mutableListOf<String>()
        override suspend fun loadHistory(ownerUid: String) = load(ownerUid)
        override suspend fun upload(ownerUid: String, session: SessionResult) { uploads += session.id }
        override suspend fun delete(ownerUid: String, sessionId: String) = Unit
    }
}
