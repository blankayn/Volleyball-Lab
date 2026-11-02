package com.volleyballlab.player.pose.review

import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Stride/crop-aware SDR conversion; samples the destination size directly. */
internal object Yuv420Rgb {
    data class Plane(val data: ByteBuffer, val rowStride: Int, val pixelStride: Int)
    data class Pixels(val width: Int, val height: Int, val argb: IntArray)

    fun convert(
        y: Plane, u: Plane, v: Plane,
        left: Int, top: Int, width: Int, height: Int,
        rotation: Int, maxWidth: Int, maxHeight: Int,
        bt709: Boolean, fullRange: Boolean,
    ): Pixels {
        require(width in 1..8192 && height in 1..8192 && left >= 0 && top >= 0)
        require(rotation in listOf(0, 90, 180, 270) && maxWidth > 0 && maxHeight > 0)
        val rotatedWidth = if (rotation % 180 == 0) width else height
        val rotatedHeight = if (rotation % 180 == 0) height else width
        val scale = minOf(1.0, maxWidth.toDouble() / rotatedWidth, maxHeight.toDouble() / rotatedHeight)
        val outWidth = (rotatedWidth * scale).roundToInt().coerceIn(1,maxWidth)
        val outHeight = (rotatedHeight * scale).roundToInt().coerceIn(1,maxHeight)
        val xs = IntArray(outWidth) { ((2L * it + 1) * rotatedWidth / (2L * outWidth)).toInt() }
        val ys = IntArray(outHeight) { ((2L * it + 1) * rotatedHeight / (2L * outHeight)).toInt() }
        val output = IntArray(outWidth * outHeight)
        val yBase = y.data.position(); val uBase = u.data.position(); val vBase = v.data.position()
        for (row in 0 until outHeight) for (col in 0 until outWidth) {
            val rx = xs[col]; val ry = ys[row]
            val sx = left + when (rotation) { 90 -> ry; 180 -> width - 1 - rx; 270 -> width - 1 - ry; else -> rx }
            val sy = top + when (rotation) { 90 -> height - 1 - rx; 180 -> height - 1 - ry; 270 -> rx; else -> ry }
            val luminance = y.data.get(yBase + sy * y.rowStride + sx * y.pixelStride).toInt() and 255
            val blue = (u.data.get(uBase + sy / 2 * u.rowStride + sx / 2 * u.pixelStride).toInt() and 255) - 128
            val red = (v.data.get(vBase + sy / 2 * v.rowStride + sx / 2 * v.pixelStride).toInt() and 255) - 128
            val c = if (fullRange) luminance * 256 else (luminance - 16) * 298
            val r: Int; val g: Int; val b: Int
            if (fullRange) {
                r = (c + (if (bt709) 403 else 359) * red + 128) shr 8
                g = (c - (if (bt709) 48 else 88) * blue - (if (bt709) 120 else 183) * red + 128) shr 8
                b = (c + (if (bt709) 475 else 454) * blue + 128) shr 8
            } else {
                r = (c + (if (bt709) 459 else 409) * red + 128) shr 8
                g = (c - (if (bt709) 55 else 100) * blue - (if (bt709) 136 else 208) * red + 128) shr 8
                b = (c + (if (bt709) 541 else 516) * blue + 128) shr 8
            }
            output[row * outWidth + col] = (255 shl 24) or (r.coerceIn(0, 255) shl 16) or
                (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
        return Pixels(outWidth, outHeight, output)
    }
}
