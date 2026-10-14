package com.volleyballlab.player.pose

/**
 * Keeps MediaPipe live-stream timestamps strictly increasing across CameraX
 * rebinds. Camera sensors do not promise the same timestamp origin after a lens
 * switch; one backward jump otherwise makes every later detectAsync call fail.
 * An offset preserves the spacing between later frames in the new stream.
 */
internal class MonotonicFrameClock {
    private var offsetMs = 0L
    private var lastOutputMs = Long.MIN_VALUE

    @Synchronized
    fun adjust(cameraTimestampMs: Long): Long {
        var adjusted = cameraTimestampMs + offsetMs
        if (adjusted <= lastOutputMs) {
            offsetMs += lastOutputMs + 1L - adjusted
            adjusted = lastOutputMs + 1L
        }
        lastOutputMs = adjusted
        return adjusted
    }
}
