package com.volleyballlab.player.feature.train.capture

import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.repository.SessionRepository
import com.volleyballlab.player.pose.PoseDetector
import com.volleyballlab.player.pose.analysis.GuidedPoseFixtures
import com.volleyballlab.player.pose.analysis.SessionScorer
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame
import com.volleyballlab.player.pose.review.CapturedReviewInput
import com.volleyballlab.player.pose.review.CapturedReviewWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuidedCaptureIntegrationTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `five reps request side and turning motion cannot add reps`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            vm.setRepsPerAngle(5)
            feed.stand(100)
            assertTrue(vm.uiState.value is CaptureUiState.Recording)
            feed.stand(35)
            repeat(5) { feed.rep() }
            val turn = vm.uiState.value as CaptureUiState.Recording
            assertEquals(CameraAngle.Side, turn.rotation?.requestedAngle)
            assertEquals(5, turn.totalMetrics.reps)
            repeat(3) { feed.rep() }
            assertEquals(5, (vm.uiState.value as CaptureUiState.Recording).totalMetrics.reps)
            feed.stand(60, CameraAngle.Side)
            val next = vm.uiState.value as CaptureUiState.Recording
            assertNull(next.rotation)
            assertEquals(CameraAngle.Side, next.angle)
            assertEquals(0, next.stageMetrics.reps)
            assertEquals(5, next.totalMetrics.reps)
        }
    }

    @Test fun `ten rep choice does not turn at five and rear can be skipped`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            vm.setRepsPerAngle(10)
            feed.stand(100)
            feed.stand(35)
            repeat(5) { feed.rep() }
            assertNull((vm.uiState.value as CaptureUiState.Recording).rotation)
            repeat(5) { feed.rep() }
            val turn = vm.uiState.value as CaptureUiState.Recording
            assertEquals(10, turn.totalMetrics.reps)
            assertEquals(CameraAngle.Side, turn.rotation?.requestedAngle)
            vm.onSkipAngle()
            assertEquals(CameraAngle.Rear, (vm.uiState.value as CaptureUiState.Recording).rotation?.requestedAngle)
        }
    }

    @Test fun `finished result already has captured replay saved without another detector pass`() = runTest(dispatcher) {
        val detector = FakeDetector()
        val feedback = CompletableDeferred<CapturedReviewInput>()
        val finishWrite = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<SessionResult>()
        val calls = mutableListOf<String>()
        val repository = object : SessionRepository by NoOpRepository {
            override suspend fun save(session: SessionResult) { calls += "session"; saved.complete(session) }
        }
        val writer = CapturedReviewWriter { path, skill, input ->
            assertEquals("recording.mp4", path)
            assertEquals(Skill.Setting, skill)
            feedback.complete(input)
            finishWrite.await()
            calls += "feedback"
        }
        val vm = CaptureViewModel(Skill.Setting, "test", { detector }, { detector }, repository, SessionScorer(), false,
            capturedReviewWriter = writer)
        val store = ViewModelStore().apply { put("capture", vm) }
        runCurrent()
        try {
            val feed = Feed(this, detector)
            feed.stand(100)
            val id = (vm.uiState.value as CaptureUiState.Recording).sessionId
            val origin = feed.timestamp - 1_000
            vm.onVideoClock(id, feed.timestamp, 1_000, false)
            feed.stand(35)
            repeat(2) { feed.rep() }
            vm.onVideoFinalized(id, "recording.mp4")
            vm.onStopRequested()
            val input = feedback.await()
            assertTrue(input.frames.isNotEmpty())
            assertEquals(origin, input.videoOriginCameraMs)
            assertTrue(calls.isEmpty())
            assertEquals(CaptureUiState.Analysing, vm.uiState.value)
            finishWrite.complete(Unit)
            val result = saved.await()
            runCurrent()
            assertEquals(listOf("feedback", "session"), calls)
            assertEquals("recording.mp4", result.videoPath)
            assertTrue(result.repCount > 0)
            assertTrue(vm.uiState.value is CaptureUiState.Finished)
        } finally { store.clear(); runCurrent() }
    }

    @Test fun `failed session storage leaves analysis without reporting a saved result`() = runTest(dispatcher) {
        val detector = FakeDetector()
        val saveAttempted = CompletableDeferred<Unit>()
        val repository = object : SessionRepository by NoOpRepository {
            override suspend fun save(session: SessionResult) {
                saveAttempted.complete(Unit)
                throw java.io.IOException("Storage full")
            }
        }
        val vm = CaptureViewModel(Skill.Setting, "test", { detector }, { detector }, repository, SessionScorer(), false)
        val store = ViewModelStore().apply { put("capture", vm) }
        runCurrent()
        try {
            val feed = Feed(this, detector)
            feed.stand(100)
            val id = (vm.uiState.value as CaptureUiState.Recording).sessionId
            vm.onVideoFinalized(id, null)
            vm.onStopRequested()
            saveAttempted.await()
            runCurrent()
            assertEquals(CaptureUiState.SaveFailed, vm.uiState.value)
            assertFalse("A failed save must end the busy state", vm.uiState.value is CaptureUiState.Analysing)
            assertFalse("A failed save must not navigate to a missing result", vm.uiState.value is CaptureUiState.Finished)
        } finally { store.clear(); runCurrent() }
    }

    @Test fun `retry saves the retained session and video once despite repeated taps`() = runTest(dispatcher) {
        val detector = FakeDetector()
        val firstAttempt = CompletableDeferred<Unit>()
        val finishRetry = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<SessionResult>()
        val attempts = mutableListOf<SessionResult>()
        var reviewWrites = 0
        val repository = object : SessionRepository by NoOpRepository {
            override suspend fun save(session: SessionResult) {
                attempts += session
                if (attempts.size == 1) {
                    firstAttempt.complete(Unit)
                    throw java.io.IOException("Storage full")
                }
                finishRetry.await()
                saved.complete(session)
            }
        }
        val writer = CapturedReviewWriter { _, _, _ -> reviewWrites++ }
        val vm = CaptureViewModel(Skill.Setting, "test", { detector }, { detector }, repository,
            SessionScorer(), false, assignmentId = "assignment-1", capturedReviewWriter = writer)
        val store = ViewModelStore().apply { put("capture", vm) }
        runCurrent()
        try {
            vm.setGuidedAngles(true)
            val feed = Feed(this, detector)
            feed.stand(100)
            val id = (vm.uiState.value as CaptureUiState.Recording).sessionId
            feed.stand(35)
            repeat(2) { feed.rep() }
            vm.onVideoFinalized(id, "recording.mp4")
            vm.onStopRequested()
            firstAttempt.await()
            runCurrent()
            assertEquals(CaptureUiState.SaveFailed, vm.uiState.value)
            assertEquals(1, reviewWrites)
            val prepared = attempts.single()
            assertTrue(prepared.repCount > 0)
            assertEquals("assignment-1", prepared.assignmentId)
            assertEquals("recording.mp4", prepared.videoPath)
            assertTrue(prepared.angleStages.isNotEmpty())

            feed.rep()
            vm.onRetrySaveRequested()
            vm.onRetrySaveRequested()
            runCurrent()
            assertEquals(CaptureUiState.Analysing, vm.uiState.value)
            assertEquals(2, attempts.size)
            finishRetry.complete(Unit)
            assertEquals(prepared, saved.await())
            runCurrent()
            assertEquals(CaptureUiState.Finished(id, prepared.repCount), vm.uiState.value)
            assertEquals(1, reviewWrites)
            vm.onRetrySaveRequested()
            runCurrent()
            assertEquals(2, attempts.size)
        } finally { store.clear(); runCurrent() }
    }

    @Test fun `cancelled storage does not become a retryable save failure`() = runTest(dispatcher) {
        val detector = FakeDetector()
        val saveAttempted = CompletableDeferred<Unit>()
        val repository = object : SessionRepository by NoOpRepository {
            override suspend fun save(session: SessionResult) {
                saveAttempted.complete(Unit)
                throw kotlinx.coroutines.CancellationException("Capture closed")
            }
        }
        val vm = CaptureViewModel(Skill.Setting, "test", { detector }, { detector }, repository, SessionScorer(), false)
        val store = ViewModelStore().apply { put("capture", vm) }
        runCurrent()
        try {
            val feed = Feed(this, detector)
            feed.stand(100)
            val id = (vm.uiState.value as CaptureUiState.Recording).sessionId
            vm.onVideoFinalized(id, null)
            vm.onStopRequested()
            saveAttempted.await()
            runCurrent()
            assertFalse(vm.uiState.value is CaptureUiState.SaveFailed)
            assertFalse(vm.uiState.value is CaptureUiState.Finished)
        } finally { store.clear(); runCurrent() }
    }

    private suspend fun TestScope.withCapture(body: suspend TestScope.(CaptureViewModel, Feed) -> Unit) {
        val detector = FakeDetector()
        val vm = CaptureViewModel(Skill.Setting, "test", { detector }, { detector }, NoOpRepository, SessionScorer(), false)
        val store = ViewModelStore().apply { put("capture", vm) }
        runCurrent()
        try { body(vm, Feed(this, detector)) } finally { store.clear(); runCurrent() }
    }

    private class Feed(val scope: TestScope, val detector: FakeDetector) {
        var timestamp = 0L
        suspend fun stand(frames: Int, angle: CameraAngle = CameraAngle.Front) {
            repeat(frames) { emit(pose(angle)) }
        }
        suspend fun rep() {
            listOf(0f, .05f, .15f, .25f, .35f, .35f, .35f, .25f, .15f, .05f, 0f, 0f).forEach { lift ->
                val base = pose(CameraAngle.Front)
                val wrists = setOf(BodyPoint.LEFT_WRIST.ordinal, BodyPoint.RIGHT_WRIST.ordinal)
                emit(base.copy(
                    image = base.image.mapIndexed { index, point -> if (index in wrists) point.copy(y = point.y - lift) else point },
                    world = base.world.mapIndexed { index, point -> if (index in wrists) point.copy(y = point.y - lift * 2f) else point },
                ))
            }
        }
        private fun pose(angle: CameraAngle): PoseFrame = when (angle) {
            CameraAngle.Side -> GuidedPoseFixtures.side(timestamp)
            CameraAngle.Rear -> GuidedPoseFixtures.rear(timestamp)
            else -> GuidedPoseFixtures.front(timestamp)
        }
        private suspend fun emit(frame: PoseFrame) {
            detector.poses.emit(frame)
            scope.runCurrent()
            timestamp += 80
            scope.advanceTimeBy(80)
            scope.runCurrent()
        }
    }

    private class FakeDetector : PoseDetector {
        override val poses = MutableSharedFlow<PoseFrame>(extraBufferCapacity = 1)
        override fun submit(bitmap: Bitmap, timestampMs: Long, mirrored: Boolean) = Unit
        override fun close() = Unit
    }
    private object NoOpRepository : SessionRepository {
        override val sessions: Flow<List<SessionResult>> = flowOf(emptyList())
        override suspend fun save(session: SessionResult) = Unit
        override suspend fun byId(id: String): SessionResult? = null
        override suspend fun delete(id: String) = Unit
    }
}
