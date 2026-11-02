package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.RepMotionEvidence
import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame

/**
 * Complete-window observations from trajectories. No quality grade is inferred
 * from a lag or stance width: that needs skill/phase-specific validated labels.
 */
object MovementEvidence {
    private val required = listOf(BodyPoint.NOSE, BodyPoint.LEFT_SHOULDER, BodyPoint.RIGHT_SHOULDER,
        BodyPoint.LEFT_ELBOW, BodyPoint.RIGHT_ELBOW, BodyPoint.LEFT_WRIST, BodyPoint.RIGHT_WRIST,
        BodyPoint.LEFT_HIP, BodyPoint.RIGHT_HIP, BodyPoint.LEFT_KNEE, BodyPoint.RIGHT_KNEE,
        BodyPoint.LEFT_ANKLE, BodyPoint.RIGHT_ANKLE)

    fun observe(window: RepWindow): RepMotionEvidence? {
        val frames = window.frames
        if (frames.size < 10 || frames.zipWithNext().any { (a, b) -> b.timestampMs - a.timestampMs !in 1L..500L }) return null
        val phases = RepPhase.entries.map { window.frameFor(it)?.timestampMs ?: return null }
        if (phases.zipWithNext().any { (a, b) -> b < a } || phases.first() < window.startMs || phases.last() > window.endMs) return null
        val reliable = frames.filter(::reliable)
        val fraction = reliable.size.toFloat() / frames.size
        if (fraction < .8f) return null
        val knees = reliable.mapNotNull { frame -> AnalyzerSupport.meanKneeAngle(frame)?.takeIf(Float::isFinite)?.let { frame to it } }
        val wrists = reliable.map { it to AnalyzerSupport.meanWristRiseAboveShoulders(it) }.filter { it.second.isFinite() }
        val kneeVariation = knees.maxOfOrNull { it.second }?.minus(knees.minOf { it.second }) ?: 0f
        val wristVariation = wrists.maxOfOrNull { it.second }?.minus(wrists.minOf { it.second }) ?: 0f
        val lag = if (kneeVariation >= 5f && wristVariation >= .03f) {
            wrists.maxBy { it.second }.first.timestampMs - knees.minBy { it.second }.first.timestampMs
        } else null
        val preparation = window.frameFor(RepPhase.Preparation)?.takeIf(::reliable)
        val width = preparation?.let { frame ->
            val shoulders = Angles.distance(frame.world(BodyPoint.LEFT_SHOULDER), frame.world(BodyPoint.RIGHT_SHOULDER))
            if (!shoulders.isFinite() || shoulders <= .05f) null else
                (Angles.distance(frame.world(BodyPoint.LEFT_ANKLE), frame.world(BodyPoint.RIGHT_ANKLE)) / shoulders)
                    .takeIf { it.isFinite() && it in .01f..10f }
        }
        return if (lag == null && width == null) null else RepMotionEvidence(lag, width, fraction)
    }

    private fun reliable(frame: PoseFrame): Boolean = required.all { point ->
        val image = frame.image(point)
        AngleCuePolicy.isReliable(frame, point) && image.x in 0f..1f && image.y in 0f..1f
    }
}
