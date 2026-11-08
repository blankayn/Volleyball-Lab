package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.Landmark
import com.volleyballlab.player.pose.model.PoseFrame
import kotlin.math.*

data class PoseGuideLimb(val anchor: Landmark, val joint: Landmark, val target: Landmark)

/**
 * Illustrates the current cue's reference joint angle using the athlete's limb
 * lengths. This is a visual practice guide, never an extra score or a learned
 * ideal athlete. Depth-dominated projections and unsupported cues show no guide.
 */
object PoseGuide {
    fun limbs(frame: PoseFrame, posture: LivePostureUi, skill: Skill, angle: CameraAngle): List<PoseGuideLimb> {
        if (posture.trackingLost || posture.modelWarmingUp || posture.modelUncertain || posture.phase == null) return emptyList()
        val status = posture.statuses.filter { it.isMeasurable && it.cue.phase == posture.phase &&
            FormCueCatalog.forSkill(skill).any { cue -> cue.id == it.cue.id } }
            .filter { (it.score ?: 1f) < .8f }.minByOrNull { it.score!! } ?: return emptyList()
        val cue = status.cue
        if (!AngleCuePolicy.isSupported(cue.id, angle, frame, status.side)) return emptyList()
        val arm = cue.id in setOf(CueId.ServeContactArmExtension, CueId.SpikeArmExtension,
            CueId.PassPlatformStraight, CueId.SetElbowFlexion, CueId.SetExtensionFollowThrough, CueId.BlockArmExtension)
        val leg = cue.id in setOf(CueId.PassKneeFlexion, CueId.SetKneeFlexion, CueId.SpikeApproachLoad, CueId.BlockKneeLoad)
        if (!arm && !leg) return emptyList()
        // A lateral knee guide would move the foot sideways in a front/rear view.
        if (leg && angle != CameraAngle.Side) return emptyList()
        val sides = status.side?.let(::listOf) ?: Side.entries
        return sides.mapNotNull { side ->
            val points = if (arm) listOf(AnalyzerSupport.shoulder(side), AnalyzerSupport.elbow(side), AnalyzerSupport.wrist(side))
                else listOf(if (side == Side.Left) BodyPoint.LEFT_ANKLE else BodyPoint.RIGHT_ANKLE,
                    if (side == Side.Left) BodyPoint.LEFT_KNEE else BodyPoint.RIGHT_KNEE, AnalyzerSupport.hip(side))
            if (points.any { !AngleCuePolicy.isReliable(frame, it, .65f) }) return@mapNotNull null
            val measured = Angles.at(frame, points[0], points[1], points[2]) ?: return@mapNotNull null
            if (measured in cue.target) return@mapNotNull null
            target(frame, points, measured, (cue.target.start + cue.target.endInclusive) / 2f)
        }
    }

    private fun target(frame: PoseFrame, points: List<BodyPoint>, worldAngle: Float, targetAngle: Float): PoseGuideLimb? {
        val a = frame.image(points[0]); val b = frame.image(points[1]); val c = frame.image(points[2])
        val width = frame.imageWidth.toFloat(); val height = frame.imageHeight.toFloat()
        if (width <= 0 || height <= 0) return null
        val ax = (a.x - b.x) * width; val ay = (a.y - b.y) * height
        val cx = (c.x - b.x) * width; val cy = (c.y - b.y) * height
        val first = hypot(ax, ay); val second = hypot(cx, cy)
        if (minOf(first, second) < minOf(width, height) * .035f) return null
        val projectedAngle = Math.toDegrees(acos(((ax * cx + ay * cy) / (first * second)).coerceIn(-1f, 1f)).toDouble()).toFloat()
        // Do not imply that a 3D reference is exact in a foreshortened 2D view.
        if (abs(projectedAngle - worldAngle) > 20f || abs(projectedAngle - targetAngle) < 5f) return null
        val cross = ax * cy - ay * cx
        if (abs(cross) < 1f && targetAngle < 150f) return null // bend direction is ambiguous
        val direction = atan2(ay, ax) + Math.toRadians(targetAngle.toDouble()).toFloat() * if (cross < 0) -1 else 1
        val target = c.copy(x = b.x + cos(direction) * second / width, y = b.y + sin(direction) * second / height)
        if (target.x !in 0f..1f || target.y !in 0f..1f) return null
        return PoseGuideLimb(a, b, target)
    }
}
