package com.volleyballlab.player.feature.train.result

import androidx.lifecycle.ViewModelStore
import com.volleyballlab.player.data.session.InMemorySessionRepository
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingRestoreTest {
    private fun verify(block: suspend TestScope.(InMemorySessionRepository, ViewModelStore) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val repository = InMemorySessionRepository()
        repository.save(SessionResult("recording", Skill.Setting, "set", 0, 100, emptyList()))
        try { block(repository, store) }
        finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun restoredVideoIsRetainedWithTheOriginalSession() = verify { repository, store ->
        val cloud = MutableStateFlow(false)
        var downloads = 0
        val model = SessionResultViewModel(repository, "recording", cloud) {
            downloads++
            "/private/restored.mp4"
        }
        store.put("result", model)
        runCurrent()
        model.downloadVideo()
        runCurrent()
        assertEquals(0, downloads)
        cloud.value = true
        runCurrent()
        model.downloadVideo()
        runCurrent()
        assertEquals(1, downloads)
        assertEquals("/private/restored.mp4", repository.byId("recording")!!.videoPath)
        val ready = model.uiState.value as ResultUiState.Ready
        assertTrue(ready.cloudSaved)
        assertFalse(ready.downloading)
    }

    @Test fun interruptedDownloadCanBeRetriedWithoutOverlappingTransfers() = verify { repository, store ->
        val transfer = CompletableDeferred<String?>()
        var downloads = 0
        val model = SessionResultViewModel(repository, "recording", MutableStateFlow(true)) {
            downloads++
            if (downloads == 1) transfer.await() else "/private/retried.mp4"
        }
        store.put("result", model)
        runCurrent()
        model.downloadVideo()
        model.downloadVideo()
        runCurrent()
        assertEquals(1, downloads)
        transfer.completeExceptionally(IllegalStateException("Offline"))
        runCurrent()
        assertNull(repository.byId("recording")!!.videoPath)
        assertTrue((model.uiState.value as ResultUiState.Ready).downloadFailed)
        model.downloadVideo()
        runCurrent()
        assertEquals("/private/retried.mp4", repository.byId("recording")!!.videoPath)
        assertFalse((model.uiState.value as ResultUiState.Ready).downloadFailed)
    }

    @Test fun removingLocalVideoRetainsTheCloudDownloadAction() = verify { repository, store ->
        repository.save(repository.byId("recording")!!.copy(videoPath = "/private/video.mp4"))
        val model = SessionResultViewModel(repository, "recording", MutableStateFlow(true))
        store.put("result", model)
        runCurrent()
        model.deleteVideo()
        runCurrent()
        val ready = model.uiState.value as ResultUiState.Ready
        assertNull(ready.session.videoPath)
        assertTrue(ready.cloudSaved)
    }
}
