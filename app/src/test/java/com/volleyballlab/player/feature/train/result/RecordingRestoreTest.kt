package com.volleyballlab.player.feature.train.result

import androidx.lifecycle.ViewModelStore
import com.volleyballlab.player.data.session.InMemorySessionRepository
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingRestoreTest {
    @Test fun backgroundRestoreAppearsWithoutAnyUserAction() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val repository = InMemorySessionRepository()
        val session = SessionResult("recording", Skill.Setting, "set", 0, 100, emptyList())
        repository.save(session)
        val cloud = MutableStateFlow(true)
        val model = SessionResultViewModel(repository, session.id, cloud)
        store.put("result", model)
        try {
            runCurrent()
            assertNull((model.uiState.value as ResultUiState.Ready).session.videoPath)
            repository.save(session.copy(videoPath = "/private/restored.mp4"))
            runCurrent()
            val restored = model.uiState.value as ResultUiState.Ready
            assertEquals("/private/restored.mp4", restored.session.videoPath)
            assertTrue(restored.cloudSaved)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
}
