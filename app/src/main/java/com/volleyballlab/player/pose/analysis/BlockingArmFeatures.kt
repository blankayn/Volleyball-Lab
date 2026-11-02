package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame

data class BlockingArmWindow(val observedArm: String, val values: List<FloatArray>, val reliableFraction: Float)

/** Same fixed-arm, timestamp-grid recipe as blocking_arm_time_v2. */
object BlockingArmFeatures {
    private val anchors = listOf(0, 11, 12, 23, 24)
    private val arms = linkedMapOf("left" to listOf(11, 13, 15, 23), "right" to listOf(12, 14, 16, 24))

    private fun reliable(frame: PoseFrame, arm: String): Boolean {
        val points = arms.getValue(arm)
        val torso = listOf(11, 12, 23, 24)
        if (torso.any { frame.image[it].visibility < .5f || frame.image[it].presence < .5f }) return false
        if ((anchors + listOf(points[1], points[2])).any {
            val point = frame.world[it]
            !point.x.isFinite() || !point.y.isFinite() || !point.z.isFinite()
        }) return false
        val length = Angles.distance(Angles.shoulderCentre(frame), Angles.hipCentre(frame))
        if (!length.isFinite() || length < .15f) return false
        return (anchors + listOf(points[1], points[2])).all { index ->
            val image = frame.image[index]
            image.visibility.isFinite() && image.visibility >= .5f &&
                image.presence.isFinite() && image.presence >= .5f && image.x in 0f..1f && image.y in 0f..1f
        }
    }

    internal fun measure(frame: PoseFrame, arm: String): FloatArray? {
        if (!reliable(frame, arm)) return null
        val (s, e, w, h) = arms.getValue(arm)
        val shoulder = frame.world[s]; val elbow = frame.world[e]; val wrist = frame.world[w]
        val values = floatArrayOf(
            Angles.at(shoulder, elbow, wrist) ?: return null,
            Angles.at(frame.world[h], shoulder, elbow) ?: return null,
            shoulder.y - wrist.y, frame.world[0].y - wrist.y,
            Angles.alongForward(frame, shoulder, wrist) ?: return null,
            frame.image[s].y - frame.image[w].y, 1f,
        )
        return values.takeIf { it.all(Float::isFinite) }
    }

    fun build(frames: List<PoseFrame>): BlockingArmWindow? {
        if (frames.size < 10 || frames.zipWithNext().any { (a, b) -> b.timestampMs - a.timestampMs !in 1L..500L }) return null
        if (frames.last().timestampMs - frames.first().timestampMs !in 350L..6000L) return null
        if (frames.first().timestampMs < 0L) return null
        val start = frames.first().timestampMs
        val duration = frames.last().timestampMs - start
        val selected = (0 until 32).map { index ->
            val target = index * duration
            // minBy preserves the earlier original frame on an exact tie.
            frames.minBy { kotlin.math.abs((it.timestampMs - start) * 31L - target) }
                .takeIf { kotlin.math.abs((it.timestampMs - start) * 31L - target) <= 50L * 31L }
        }
        val candidates = arms.keys.associateWith { arm -> selected.map { frame ->
            frame?.let { measure(it, arm) } ?: FloatArray(7) { if (it == 6) 0f else Float.NaN }
        } }
        val arm = arms.keys.maxBy { side -> candidates.getValue(side).count { it[6] == 1f } }
        val sampled = candidates.getValue(arm)
        val fraction = sampled.count { it[6] == 1f }.toFloat() / sampled.size
        if (fraction < .5f) return null
        return BlockingArmWindow(arm, sampled, fraction)
    }

    /** Evidence of an arm rise and return only; never claims a jump or ball contact. */
    fun hasArmCycle(window: BlockingArmWindow): Boolean {
        val visible = window.values.filter { it.size == 7 && it[6] == 1f && it[5].isFinite() }
        if (visible.size < 10) return false
        val peak = visible.indices.maxBy { visible[it][5] }
        return peak >= 2 && peak < visible.size - 2 &&
            visible[peak][5] - visible.first()[5] >= .03f && visible[peak][5] - visible.last()[5] >= .03f
    }
}
