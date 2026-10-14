package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.FormCue
import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame
import org.junit.Assert.*
import org.junit.Test

class LiveRepCounterTest {
    private val analyzer = object : SkillAnalyzer {
        override val skill = Skill.Receiving
        override fun segmentationSignal(frame: PoseFrame) = frame.image(BodyPoint.NOSE).z
        override fun measure(cue: FormCue, window: RepWindow): Float? = null
    }
    private fun frame(ms: Long, signal: Float): PoseFrame = GuidedPoseFixtures.front(ms).let { frame ->
        frame.copy(image = frame.image.mapIndexed { index, point -> if (index == BodyPoint.NOSE.ordinal) point.copy(z = signal) else point })
    }
    private fun counter() = LiveRepCounter(analyzer, LiveRepCounter.Config(warmUpFrames = 3, rangeDecay = 0f))

    @Test fun `only completed causal window exposes distinct contact and follow through`() {
        val counter = counter()
        listOf(0f, 0f, 0f, 0.5f, 0.8f, 1f, 0.7f, 0.5f).forEachIndexed { index, value ->
            assertFalse(counter.onFrame(frame(index * 100L, value)))
        }
        assertNull(counter.currentPhase)
        assertTrue(counter.onFrame(frame(800, 0f)))
        assertEquals(1, counter.count)
        val window = counter.lastCompletedWindow!!
        assertEquals(500L, window.contactMs)
        assertEquals(800L, window.frameFor(RepPhase.FollowThrough)?.timestampMs)
        assertTrue(window.startMs < window.contactMs)
        assertEquals(RepPhase.Preparation, counter.currentPhase)
    }

    @Test fun `abandon and missing frame gap cannot join two halves into a rep`() {
        val counter = counter()
        listOf(0f, 0f, 0f, 0.5f, 1f).forEachIndexed { index, value -> counter.onFrame(frame(index * 100L, value)) }
        counter.resetSegment()
        assertFalse(counter.onFrame(frame(600, 0f)))
        assertEquals(0, counter.count)
        listOf(0f, 0f, 0.5f, 1f).forEachIndexed { index, value -> counter.onFrame(frame(700 + index * 100L, value)) }
        assertFalse(counter.onFrame(frame(2000, 0f)))
        assertEquals(0, counter.count)
    }

    @Test fun `resetSegment preserves completed count while reset clears it`() {
        val counter = counter()
        listOf(0f, 0f, 0f, 0.5f, 0.8f, 1f, 0.7f, 0.5f, 0f).forEachIndexed { index, value -> counter.onFrame(frame(index * 100L, value)) }
        assertEquals(1, counter.count)
        counter.resetSegment()
        assertEquals(1, counter.count)
        assertNull(counter.lastCompletedWindow)
        counter.reset()
        assertEquals(0, counter.count)
    }
}
