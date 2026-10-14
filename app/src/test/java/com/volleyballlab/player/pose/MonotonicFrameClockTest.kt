package com.volleyballlab.player.pose

import org.junit.Assert.assertEquals
import org.junit.Test

class MonotonicFrameClockTest {
    @Test fun preservesNormalCameraFrameSpacing() {
        val clock = MonotonicFrameClock()
        assertEquals(1_000L, clock.adjust(1_000L))
        assertEquals(1_033L, clock.adjust(1_033L))
        assertEquals(1_066L, clock.adjust(1_066L))
    }

    @Test fun rebasesNewCameraClockAfterLensSwitch() {
        val clock = MonotonicFrameClock()
        assertEquals(5_000L, clock.adjust(5_000L))
        assertEquals(5_033L, clock.adjust(5_033L))
        assertEquals(5_034L, clock.adjust(100L))
        assertEquals(5_067L, clock.adjust(133L))
    }

    @Test fun duplicateMillisecondTimestampStillAdvances() {
        val clock = MonotonicFrameClock()
        assertEquals(100L, clock.adjust(100L))
        assertEquals(101L, clock.adjust(100L))
        assertEquals(102L, clock.adjust(100L))
    }
}
