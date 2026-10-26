package com.volleyballlab.player.feature.train.capture

import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.repository.SessionRepository
import com.volleyballlab.player.pose.PoseDetector
import com.volleyballlab.player.pose.TestPoses
import com.volleyballlab.player.pose.analysis.SessionScorer
import com.volleyballlab.player.pose.model.PoseFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the hands-free start.
 *
 * The gate is what makes the capture screen usable at all: the framing it insists
 * on puts the athlete metres away from a phone whose Start button they then
 * cannot reach. These cases pin the two ways it could fail badly - never firing,
 * so the athlete is stranded across the court, and firing when it should not, so
 * the app records an empty gym or overrides a cancel.
 *
 * Time is virtual throughout. The gate counts ticks rather than reading the clock
 * partly so that it can be driven this way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureHoldGateTest {

    private val dispatcher = StandardTestDispatcher()
    private val detector = FakePoseDetector()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `holding acceptable framing starts the countdown without a tap`() = captureTest { vm ->
        feedWellFramedPoses()

        advanceTimeBy(CaptureViewModel.AUTO_START_HOLD_MS + TICK_MS)
        runCurrent()

        assertTrue(
            "Expected the gate to fire, was ${vm.uiState.value}",
            vm.uiState.value is CaptureUiState.CountingDown,
        )
    }

    @Test
    fun `the gate reports how much longer the athlete must hold`() = captureTest { vm ->
        feedWellFramedPoses()

        advanceTimeBy(1_000)
        runCurrent()

        val framing = vm.uiState.value as CaptureUiState.Framing
        assertTrue("Gate never started filling", framing.holdProgress > 0f)
        assertTrue("Gate filled too fast", framing.holdProgress < 1f)
        assertEquals(2, framing.holdSecondsLeft)
    }

    /**
     * The detector emits nothing at all when it finds no pose, so the gate cannot
     * wait to be told the athlete has gone. Without the staleness check, this is
     * the test that records an empty gym.
     */
    @Test
    fun `walking out of shot empties the gate instead of starting`() = captureTest { vm ->
        val feed = feedWellFramedPoses()
        advanceTimeBy(1_500)
        runCurrent()

        feed.cancel()
        advanceTimeBy(CaptureViewModel.AUTO_START_HOLD_MS * 2)
        runCurrent()

        val state = vm.uiState.value
        assertTrue("Expected to still be framing, was $state", state is CaptureUiState.Framing)
        assertEquals(0f, (state as CaptureUiState.Framing).holdProgress, 0f)
    }

    @Test
    fun `stale skeleton clears and a new pose appears when tracking resumes`() = captureTest { vm ->
        val feed = feedWellFramedPoses()
        advanceTimeBy(TICK_MS * 2)
        runCurrent()
        assertNotNull(vm.poseFrame.value)

        feed.cancel()
        advanceTimeBy(600)
        runCurrent()
        assertNull(vm.poseFrame.value)

        detector.emit(TestPoses.wellFramedStanding(1_000L))
        runCurrent()
        assertNotNull(vm.poseFrame.value)
    }

    @Test
    fun `cancelling the countdown does not let the gate restart it`() = captureTest { vm ->
        feedWellFramedPoses()
        advanceTimeBy(CaptureViewModel.AUTO_START_HOLD_MS + TICK_MS)
        runCurrent()

        vm.onCancelCountdown()
        // Twice the gate duration, with the athlete standing perfectly framed the
        // whole time: an armed gate would have fired twice over.
        advanceTimeBy(CaptureViewModel.AUTO_START_HOLD_MS * 2)
        runCurrent()

        val state = vm.uiState.value
        assertTrue("Cancel was undone by the gate, state was $state", state is CaptureUiState.Framing)
    }

    @Test
    fun `stepping out of frame and back in re-arms a cancelled gate`() = captureTest { vm ->
        val firstFeed = feedWellFramedPoses()
        advanceTimeBy(CaptureViewModel.AUTO_START_HOLD_MS + TICK_MS)
        runCurrent()
        vm.onCancelCountdown()

        // Out of shot: no poses at all, long enough to go stale.
        firstFeed.cancel()
        advanceTimeBy(1_000)
        runCurrent()

        // Back in shot, and this time nobody touches the phone.
        feedWellFramedPoses()
        advanceTimeBy(CaptureViewModel.AUTO_START_HOLD_MS + TICK_MS)
        runCurrent()

        assertTrue(
            "Gate did not re-arm, state was ${vm.uiState.value}",
            vm.uiState.value is CaptureUiState.CountingDown,
        )
    }

    @Test
    fun `the start button still works for anyone standing by the phone`() = captureTest { vm ->
        feedWellFramedPoses()
        advanceTimeBy(TICK_MS * 2)
        runCurrent()

        vm.onStartRequested()
        runCurrent()

        assertTrue(vm.uiState.value is CaptureUiState.CountingDown)
    }

    /**
     * Runs [body] against a live view model and then shuts it down.
     *
     * The teardown is not optional. The hold gate is an endless ticker on
     * `viewModelScope`, which is not a child of the test scope, so nothing else
     * would ever cancel it - and `runTest` advances the scheduler to idle once the
     * body returns, which against an endless ticker is a virtual-time spin that no
     * timeout interrupts. Clearing the store cancels the scope, exactly as
     * navigating away from the capture screen does.
     */
    private fun captureTest(body: suspend TestScope.(CaptureViewModel) -> Unit): TestResult =
        runTest(dispatcher) {
            val viewModel = CaptureViewModel(
                skill = Skill.Spiking,
                drillId = "test-drill",
                detectorFactory = { detector },
                demoDetectorFactory = { detector },
                sessionRepository = NoOpSessionRepository,
                sessionScorer = SessionScorer(),
                allowDemoDetector = false,
            )
            val store = ViewModelStore().apply { put("capture", viewModel) }
            // These tests isolate the existing framing gate; guided orientation
            // has dedicated fixtures with anatomically correct front/rear signs.
            viewModel.setGuidedAngles(false)
            // Lets the detector start up and the gate get its first tick queued.
            runCurrent()
            try {
                body(viewModel)
            } finally {
                store.clear()
                runCurrent()
            }
        }

    /**
     * Emits a well-framed pose at roughly camera rate until cancelled.
     *
     * The pose is identical every frame, which is what the validator's stability
     * check wants to see: a settled athlete rather than a wobbling phone.
     *
     * On [TestScope.backgroundScope] rather than the test scope, because an
     * endless feed launched as a child of the test would be awaited when the body
     * returns and hang in the same way the gate would.
     */
    private fun TestScope.feedWellFramedPoses(): Job = backgroundScope.launch {
        var timestampMs = 0L
        while (true) {
            detector.emit(TestPoses.wellFramedStanding(timestampMs))
            timestampMs += FRAME_INTERVAL_MS
            delay(FRAME_INTERVAL_MS)
        }
    }

    private class FakePoseDetector : PoseDetector {
        private val _poses = MutableSharedFlow<PoseFrame>(
            replay = 1,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        override val poses: SharedFlow<PoseFrame> = _poses.asSharedFlow()

        fun emit(frame: PoseFrame) {
            _poses.tryEmit(frame)
        }

        override fun submit(bitmap: Bitmap, timestampMs: Long, mirrored: Boolean) = Unit

        override fun close() = Unit
    }

    private object NoOpSessionRepository : SessionRepository {
        override val sessions: Flow<List<SessionResult>> = flowOf(emptyList())
        override suspend fun save(session: SessionResult) = Unit
        override suspend fun byId(id: String): SessionResult? = null
        override suspend fun delete(id: String) = Unit
        override suspend fun deleteVideo(id: String) = Unit
    }

    private companion object {
        /** One gate tick, in the units the gate itself counts in. */
        const val TICK_MS = 100L
        const val FRAME_INTERVAL_MS = 33L
    }
}
