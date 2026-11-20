package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.pose.analysis.LiveTrackingGate.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reported defect: standing correctly side-on, the athlete was told to
 * reposition and no rep was counted, because the view detector flickers
 * side-on and every flicker was acted on.
 */
class LiveTrackingGateTest {

    private val side = OrientationEstimate(CameraAngle.Side, 0.85f, fullBodyVisible = true, exposedSide = Side.Right)
    private val unsure = OrientationEstimate(CameraAngle.Unknown, 0f, fullBodyVisible = true)
    private val front = OrientationEstimate(CameraAngle.Front, 0.9f, fullBodyVisible = true)
    private val lost = OrientationEstimate(CameraAngle.Unknown, 0f, fullBodyVisible = false)
    private val turnSideOn = SpokenCue("Turn side-on again.", "Side-on.")

    /** Side-on, the far arm and leg are hidden, so the mean limb visibility is low. */
    private val sideOnLimbs = 0.45f

    @Test
    fun `a side-on athlete whose view flickers at rest is never told to turn`() {
        val gate = LiveTrackingGate()
        val outcomes = (0 until 90).map { i ->
            // Every other pair of frames reads as unsure, as on real side-view footage.
            val flicker = i % 4 >= 2
            gate.update(i * 33L, if (flicker) unsure else side, if (flicker) turnSideOn else null,
                inRep = false, limbVisibility = sideOnLimbs)
        }
        assertTrue(outcomes.none { it.outcome == Outcome.WrongFacing || it.outcome == Outcome.Interrupt })
    }

    @Test
    fun `an athlete who really faces the wrong way is told within a second`() {
        val gate = LiveTrackingGate()
        val outcomes = (0..30).map { i ->
            gate.update(i * 33L, front, turnSideOn, inRep = false, limbVisibility = 0.9f)
        }
        val first = outcomes.indexOfFirst { it.outcome == Outcome.WrongFacing }
        assertTrue(first in 1..30)
        assertTrue(first * 33L >= LiveTrackingGate.WRONG_FACING_HOLD_MS)
        assertEquals(turnSideOn, outcomes[first].correction)
    }

    @Test
    fun `a swing that turns the shoulders square mid-rep keeps the rep`() {
        val gate = LiveTrackingGate()
        repeat(10) { gate.update(it * 33L, side, null, inRep = false, limbVisibility = sideOnLimbs) }
        // Mid-swing the detector reads front or unsure, while the far limbs stay hidden.
        val swing = listOf(front, unsure, front, unsure, side)
        val outcomes = swing.mapIndexed { i, view ->
            gate.update(400L + i * 33L, view, turnSideOn, inRep = true, limbVisibility = sideOnLimbs)
        }
        assertTrue(outcomes.all { it.outcome == Outcome.Track })
    }

    @Test
    fun `a brief tracking dropout is skipped and a long one interrupts`() {
        val gate = LiveTrackingGate()
        gate.update(0L, side, null, inRep = true, limbVisibility = sideOnLimbs)
        assertEquals(Outcome.Skip, gate.update(33L, lost, null, inRep = true, limbVisibility = 0.2f).outcome)
        assertEquals(Outcome.Skip, gate.update(300L, lost, null, inRep = true, limbVisibility = 0.2f).outcome)
        assertEquals(Outcome.Interrupt, gate.update(500L, lost, null, inRep = true, limbVisibility = 0.2f).outcome)
        assertEquals(Outcome.Track, gate.update(533L, side, null, inRep = true, limbVisibility = sideOnLimbs).outcome)
    }

    @Test
    fun `facing the camera at rest ends the side-on stance and its relaxed visibility`() {
        val gate = LiveTrackingGate()
        gate.update(0L, side, null, inRep = false, limbVisibility = sideOnLimbs)
        gate.update(33L, front, null, inRep = false, limbVisibility = 0.9f)
        // Facing forward with half the body hidden is not a usable frame.
        assertEquals(Outcome.Skip, gate.update(66L, front, null, inRep = false, limbVisibility = 0.45f).outcome)
    }
}
