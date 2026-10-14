package com.volleyballlab.player.data.session

import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCloudMirrorTest {
    private val session = SessionResult("test", Skill.Setting, "set", 0, 100, emptyList())

    @Test fun `a stalled upload never holds result navigation and deletion stays ordered`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val cloud = object : SessionCloudStore {
            override suspend fun upload(ownerUid: String, session: SessionResult) {
                calls += "upload:$ownerUid"
                gate.await()
                calls += "uploaded"
            }
            override suspend fun delete(ownerUid: String, sessionId: String) { calls += "delete:$ownerUid" }
        }
        val mirror = SessionCloudMirror(cloud, { "player" }, backgroundScope)
        mirror.upload("player", session)
        mirror.delete("player", session.id)
        runCurrent()
        assertEquals(listOf("upload:player"), calls)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("upload:player", "uploaded", "delete:player"), calls)
    }

    @Test fun `queued work cannot be uploaded as a newly signed in account`() = runTest {
        var uid = "original"
        val calls = mutableListOf<String>()
        val cloud = object : SessionCloudStore {
            override suspend fun upload(ownerUid: String, session: SessionResult) { calls += ownerUid }
            override suspend fun delete(ownerUid: String, sessionId: String) { calls += ownerUid }
        }
        val mirror = SessionCloudMirror(cloud, { uid }, backgroundScope)
        mirror.upload("original", session)
        mirror.delete("original", session.id)
        uid = "other"
        runCurrent()
        assertTrue(calls.isEmpty())
        mirror.upload("other", session.copy(id = "other-session"))
        runCurrent()
        assertEquals(listOf("other"), calls)
    }

    @Test fun `a failed background mirror does not block later operations`() = runTest {
        var deleted = false
        val cloud = object : SessionCloudStore {
            override suspend fun upload(ownerUid: String, session: SessionResult) { error("Offline") }
            override suspend fun delete(ownerUid: String, sessionId: String) { deleted = true }
        }
        val mirror = SessionCloudMirror(cloud, { "player" }, backgroundScope)
        mirror.upload("player", session)
        mirror.delete("player", session.id)
        runCurrent()
        assertTrue(deleted)
    }
}
