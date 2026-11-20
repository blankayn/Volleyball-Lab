package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.Landmark
import com.volleyballlab.player.pose.model.PoseFrame
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Replays labelled clips (`<clip>__<side|front|back>.csv`, PublicClipReplay's
 * format) through [CameraOrientationDetector] and the live acceptance rule of
 * [OrientationStabilityGate], and records why side-view frames are refused.
 *
 * An evaluation tool, run only on request:
 *
 *     VL_ORIENTATION_DIR=<dir with landmarks/> ./gradlew :app:testDebugUnitTest --tests '*OrientationReplay*'
 *
 * Writes `<dir>/results.csv` (one row per clip) and prints a summary.
 */
class OrientationReplay {

    @Test
    fun replayLabelledViews() {
        val dir = System.getenv("VL_ORIENTATION_DIR")?.let(::File)
        assumeTrue(dir != null && File(dir, "landmarks").isDirectory)
        val clips = File(dir, "landmarks").listFiles { f -> f.extension == "csv" }.orEmpty().sortedBy { it.name }
        val detector = CameraOrientationDetector()
        val header = listOf("clip", "view", "frames", "side", "front", "rear", "unknown", "accepted",
            "torso_unreliable", "too_small", "shoulders_square", "hips_square", "no_near_side",
            "side_low_confidence", "not_full_body")
        val rows = mutableListOf(header)
        val totals = mutableMapOf<String, IntArray>()
        for (clip in clips) {
            val view = clip.nameWithoutExtension.substringAfterLast("__")
            val frames = read(clip).ifEmpty { continue }
            val c = IntArray(header.size - 2)
            for (frame in frames) {
                val e = detector.detect(frame)
                c[0]++
                when (e.angle) {
                    CameraAngle.Side -> c[1]++
                    CameraAngle.Front -> c[2]++
                    CameraAngle.Rear -> c[3]++
                    else -> c[4]++
                }
                if (e.angle == CameraAngle.Side && e.confidence >= 0.70f && e.fullBodyVisible) c[5]++
                if (view == "side") reason(frame, e)?.let { c[it]++ }
            }
            rows += listOf(clip.nameWithoutExtension, view) + c.map { it.toString() }
            val t = totals.getOrPut(view) { IntArray(c.size + 1) }
            c.forEachIndexed { i, v -> t[i] += v }
            if (c[5] > 0) t[c.size]++ // clips where the live gate would ever accept a side frame
        }
        File(dir, "results.csv").writeText(rows.joinToString("\n") { it.joinToString(",") })
        totals.forEach { (view, t) ->
            val f = t[0].toFloat()
            fun pct(i: Int) = "%.0f%%".format(100 * t[i] / f)
            println("VIEW $view: frames ${t[0]} | side ${pct(1)} front ${pct(2)} rear ${pct(3)} unknown ${pct(4)} | " +
                "live-gate side accepted ${pct(5)} | clips with any accepted side frame ${t[t.size - 1]}")
            if (view == "side") println("  side refusals: torso unreliable ${pct(6)}, torso too small ${pct(7)}, " +
                "shoulders too square ${pct(8)}, hips too square ${pct(9)}, no near side ${pct(10)}, " +
                "Side but confidence < 0.70 ${pct(11)}, Side+confident but not full body ${pct(12)}")
        }
    }

    /** The first rule that stops a frame being an accepted side view, as a column index into the counts. */
    private fun reason(frame: PoseFrame, e: OrientationEstimate): Int? {
        val torso = listOf(BodyPoint.LEFT_SHOULDER, BodyPoint.RIGHT_SHOULDER, BodyPoint.LEFT_HIP, BodyPoint.RIGHT_HIP)
        if (torso.any { !AngleCuePolicy.isReliable(frame, it, 0.60f) }) return 6
        val hw = abs(frame.world(BodyPoint.LEFT_HIP).x - frame.world(BodyPoint.RIGHT_HIP).x)
        val hd = abs(frame.world(BodyPoint.LEFT_HIP).z - frame.world(BodyPoint.RIGHT_HIP).z)
        val span = sqrt(hw * hw + hd * hd)
        val torsoHeight = abs(Angles.shoulderCentreImage(frame).y - Angles.hipCentreImage(frame).y)
        if (span < 0.025f || torsoHeight < 0.07f) return 7
        if (e.angle != CameraAngle.Side) {
            if (Angles.facingCamera(frame) > 0.45f) return 8
            if (hw / span > 0.55f) return 9
            return 10
        }
        if (e.confidence < 0.70f) return 11
        if (!e.fullBodyVisible) return 12
        return null
    }

    private fun read(file: File): List<PoseFrame> = file.readLines().filter { it.isNotBlank() }.map { line ->
        val v = line.split(',')
        fun points(offset: Int) = List(PoseFrame.EXPECTED_LANDMARK_COUNT) { i ->
            val b = offset + i * 5
            Landmark(v[b].toFloat(), v[b + 1].toFloat(), v[b + 2].toFloat(), v[b + 3].toFloat(), v[b + 4].toFloat())
        }
        PoseFrame(v[0].toLong(), points(3), points(3 + PoseFrame.EXPECTED_LANDMARK_COUNT * 5), v[1].toInt(), v[2].toInt(), false)
    }
}
