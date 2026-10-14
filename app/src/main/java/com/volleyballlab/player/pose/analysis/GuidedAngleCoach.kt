package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame
import kotlin.math.abs
import kotlin.math.sqrt

data class OrientationEstimate(
    val angle: CameraAngle = CameraAngle.Unknown,
    val confidence: Float = 0f,
    val fullBodyVisible: Boolean = false,
    /** Anatomical side nearest the lens, never swapped for a mirrored preview. */
    val exposedSide: Side? = null,
)

/** Conservative view estimate. Missing face landmarks alone never prove a rear view. */
class CameraOrientationDetector {
    fun detect(frame: PoseFrame): OrientationEstimate {
        val shoulders = listOf(BodyPoint.LEFT_SHOULDER, BodyPoint.RIGHT_SHOULDER)
        val hips = listOf(BodyPoint.LEFT_HIP, BodyPoint.RIGHT_HIP)
        val torso = shoulders + hips
        val fullBody = fullBodyVisible(frame)
        if (torso.any { !reliable(frame, it, 0.60f) }) {
            return OrientationEstimate(fullBodyVisible = fullBody)
        }
        val square = Angles.facingCamera(frame)
        val hipWidth = abs(frame.world(BodyPoint.LEFT_HIP).x - frame.world(BodyPoint.RIGHT_HIP).x)
        val hipDepth = abs(frame.world(BodyPoint.LEFT_HIP).z - frame.world(BodyPoint.RIGHT_HIP).z)
        val hipSpan = sqrt(hipWidth * hipWidth + hipDepth * hipDepth)
        if (hipSpan < 0.025f) return OrientationEstimate(fullBodyVisible = fullBody)
        val hipSquare = hipWidth / hipSpan
        val shoulderSign = frame.image(BodyPoint.LEFT_SHOULDER).x - frame.image(BodyPoint.RIGHT_SHOULDER).x
        val hipSign = frame.image(BodyPoint.LEFT_HIP).x - frame.image(BodyPoint.RIGHT_HIP).x
        val torsoHeight = abs(Angles.shoulderCentreImage(frame).y - Angles.hipCentreImage(frame).y)
        if (torsoHeight < 0.07f) return OrientationEstimate(fullBodyVisible = fullBody)
        val nearSide = when {
            frame.world(BodyPoint.LEFT_SHOULDER).z + 0.04f < frame.world(BodyPoint.RIGHT_SHOULDER).z -> Side.Left
            frame.world(BodyPoint.RIGHT_SHOULDER).z + 0.04f < frame.world(BodyPoint.LEFT_SHOULDER).z -> Side.Right
            else -> null
        }
        val torsoConfidence = torso.minOf { minOf(frame.image(it).visibility, frame.image(it).presence) }
        // Shoulder and hip widths agree on anatomical handedness in the source
        // image. These signs are unaffected by display mirroring.
        val broadTorso = abs(shoulderSign) > torsoHeight * 0.16f && abs(hipSign) > torsoHeight * 0.08f
        val faceVisible = reliable(frame, BodyPoint.NOSE, 0.65f) &&
            (reliable(frame, BodyPoint.LEFT_EYE, 0.55f) || reliable(frame, BodyPoint.RIGHT_EYE, 0.55f))
        val faceHidden = frame.image(BodyPoint.NOSE).visibility < 0.45f &&
            frame.image(BodyPoint.LEFT_EYE).visibility < 0.45f && frame.image(BodyPoint.RIGHT_EYE).visibility < 0.45f
        val angle = when {
            square <= 0.45f && hipSquare <= 0.55f && nearSide != null -> CameraAngle.Side
            square >= 0.78f && hipSquare >= 0.65f && broadTorso && shoulderSign > 0 && hipSign > 0 && faceVisible -> CameraAngle.Front
            square >= 0.78f && hipSquare >= 0.65f && broadTorso && shoulderSign < 0 && hipSign < 0 && faceHidden -> CameraAngle.Rear
            else -> CameraAngle.Unknown
        }
        val confidence = when (angle) {
            CameraAngle.Front, CameraAngle.Rear -> torsoConfidence * minOf(square, hipSquare)
            CameraAngle.Side -> torsoConfidence * minOf(1f - square, 1f - hipSquare)
            CameraAngle.Unknown -> 0f
        }
        return OrientationEstimate(angle, confidence, fullBody, nearSide)
    }

    private fun fullBodyVisible(frame: PoseFrame): Boolean {
        val torso = listOf(BodyPoint.LEFT_SHOULDER, BodyPoint.RIGHT_SHOULDER, BodyPoint.LEFT_HIP, BodyPoint.RIGHT_HIP)
        if (torso.any { !reliable(frame, it, 0.60f) }) return false
        // The far-side leg can be genuinely occluded in a side view. Require a
        // complete observed near-side leg and keep every observed limb in frame.
        val legVisible = Side.entries.any { side ->
            listOf(AnalyzerSupport.hip(side), AnalyzerSupport.knee(side), AnalyzerSupport.ankle(side))
                .all { reliable(frame, it, 0.60f) && inside(frame, it) }
        }
        val observed = BodyPoint.TorsoAndLimbs.filter { reliable(frame, it, 0.60f) }
        val top = Angles.shoulderCentreImage(frame).y -
            abs(Angles.shoulderCentreImage(frame).y - Angles.hipCentreImage(frame).y) * 0.35f
        return legVisible && top >= 0.025f && observed.all { inside(frame, it) }
    }

    private fun inside(frame: PoseFrame, point: BodyPoint): Boolean = frame.image(point).let {
        it.x.isFinite() && it.y.isFinite() && it.x in 0.025f..0.975f && it.y in 0.025f..0.975f
    }

    private fun reliable(frame: PoseFrame, point: BodyPoint, threshold: Float): Boolean =
        AngleCuePolicy.isReliable(frame, point, threshold)
}

/** A frame gap or a wrong/uncertain view restarts the complete one-second gate. */
class OrientationStabilityGate(
    private val stableMs: Long = 1_000L,
    private val minConfidence: Float = 0.70f,
    private val maxFrameGapMs: Long = 500L,
) {
    private var stableSince: Long? = null
    private var lastTimestamp: Long? = null
    private var lastRequested: CameraAngle? = null

    fun reset() { stableSince = null; lastTimestamp = null; lastRequested = null }

    fun update(
        estimate: OrientationEstimate,
        requestedAngle: CameraAngle,
        timestampMs: Long,
        requiredSide: Side? = null,
    ): Boolean {
        val previous = lastTimestamp
        if (lastRequested != requestedAngle || previous != null &&
            (timestampMs <= previous || timestampMs - previous > maxFrameGapMs)) stableSince = null
        lastRequested = requestedAngle
        lastTimestamp = timestampMs
        val accepted = requestedAngle != CameraAngle.Unknown && estimate.angle == requestedAngle &&
            estimate.confidence >= minConfidence && estimate.fullBodyVisible &&
            (requestedAngle != CameraAngle.Side || requiredSide == null || estimate.exposedSide == requiredSide)
        if (!accepted) { stableSince = null; return false }
        if (stableSince == null) stableSince = timestampMs
        return timestampMs - stableSince!! >= stableMs
    }
}

/** Shared by live and final scoring, so inferred hidden joints never earn praise. */
object AngleCuePolicy {
    private val facingDependent = setOf(
        CueId.ServeTrunkLean, CueId.PassTrunkAngle, CueId.SpikeTrunkDrive,
        CueId.BlockPenetration, CueId.SetHandsAboveForehead,
    )

    fun isSupported(cueId: CueId, angle: CameraAngle, frame: PoseFrame, resolvedSide: Side? = null): Boolean {
        if (angle == CameraAngle.Unknown) return false
        // Preparation's higher wrist is the toss arm, not the hitting arm.
        // Without observed contact there is no trustworthy opposite-side cue.
        if (cueId == CueId.ServeTossArmLift && resolvedSide == null) return false
        val observed = CameraOrientationDetector().detect(frame)
        if (observed.angle == CameraAngle.Unknown || observed.confidence < 0.55f) return false
        // Athletes naturally rotate during a serve/spike. Known alternate views
        // still support observed 3D limb angles, while facing-dependent cues
        // must stay within the stage's validated view.
        if (cueId in facingDependent && (angle == CameraAngle.Rear || observed.angle == CameraAngle.Rear ||
                observed.angle != angle || !isReliable(frame, BodyPoint.NOSE, 0.65f))) return false
        if (cueId == CueId.PassShouldersLevel && observed.angle == CameraAngle.Side) return false
        val points = affectedPoints(cueId, frame, resolvedSide)
        return points.all { isReliable(frame, it) }
    }

    fun isReliable(frame: PoseFrame, point: BodyPoint, threshold: Float = 0.50f): Boolean {
        val image = frame.image(point)
        val world = frame.world(point)
        return image.visibility >= threshold && image.presence >= threshold &&
            world.visibility >= threshold && world.presence >= threshold &&
            listOf(image.x, image.y, image.z, world.x, world.y, world.z).all { it.isFinite() }
    }

    fun measuredSide(cueId: CueId, frame: PoseFrame): Side? {
        if (cueId !in setOf(CueId.ServeContactArmExtension, CueId.ServeReachHeight, CueId.SpikeArmExtension)) return null
        if (!isReliable(frame, BodyPoint.LEFT_WRIST) || !isReliable(frame, BodyPoint.RIGHT_WRIST)) return null
        if (abs(frame.image(BodyPoint.LEFT_WRIST).y - frame.image(BodyPoint.RIGHT_WRIST).y) < 0.03f) return null
        val hitting = AnalyzerSupport.hittingSide(frame)
        return hitting
    }

    /** Resolves toss side from contact; its measurement still uses preparation. */
    fun completedSide(cueId: CueId, window: RepWindow): Side? {
        if (cueId == CueId.ServeTossArmLift) {
            val contact = window.frameFor(RepPhase.Contact) ?: return null
            val hitting = measuredSide(CueId.ServeContactArmExtension, contact) ?: return null
            return if (hitting == Side.Left) Side.Right else Side.Left
        }
        val cue = com.volleyballlab.player.domain.model.FormCueCatalog.byId(cueId) ?: return null
        return window.frameFor(cue.phase)?.let { measuredSide(cueId, it) }
    }

    fun affectedPoints(cueId: CueId, frame: PoseFrame? = null, resolvedSide: Side? = null): Set<BodyPoint> {
        val side = resolvedSide ?: frame?.let { measuredSide(cueId, it) }
        fun arms(withHips: Boolean = false): Set<BodyPoint> = (side?.let { listOf(it) } ?: Side.entries).flatMap {
            listOf(AnalyzerSupport.shoulder(it), AnalyzerSupport.elbow(it), AnalyzerSupport.wrist(it)) +
                if (withHips) listOf(AnalyzerSupport.hip(it)) else emptyList()
        }.toSet()
        val legs = setOf(BodyPoint.LEFT_HIP, BodyPoint.RIGHT_HIP, BodyPoint.LEFT_KNEE, BodyPoint.RIGHT_KNEE, BodyPoint.LEFT_ANKLE, BodyPoint.RIGHT_ANKLE)
        val torso = setOf(BodyPoint.LEFT_SHOULDER, BodyPoint.RIGHT_SHOULDER, BodyPoint.LEFT_HIP, BodyPoint.RIGHT_HIP)
        return when (cueId) {
            CueId.ServeContactArmExtension, CueId.SpikeArmExtension,
            CueId.PassPlatformStraight, CueId.SetElbowFlexion,
            CueId.SetExtensionFollowThrough, CueId.BlockArmExtension -> arms()
            CueId.ServeReachHeight, CueId.ServeTossArmLift -> arms(withHips = true)
            CueId.PassKneeFlexion, CueId.SetKneeFlexion,
            CueId.SpikeApproachLoad, CueId.BlockKneeLoad -> legs
            CueId.ServeTrunkLean, CueId.PassTrunkAngle, CueId.SpikeTrunkDrive -> torso
            CueId.PassShouldersLevel -> setOf(BodyPoint.LEFT_SHOULDER, BodyPoint.RIGHT_SHOULDER)
            CueId.SetHandsAboveForehead -> setOf(BodyPoint.NOSE, BodyPoint.LEFT_WRIST, BodyPoint.RIGHT_WRIST)
            CueId.BlockHandSeparation -> setOf(BodyPoint.LEFT_WRIST, BodyPoint.RIGHT_WRIST)
            CueId.BlockPenetration -> setOf(BodyPoint.LEFT_SHOULDER, BodyPoint.RIGHT_SHOULDER, BodyPoint.LEFT_WRIST, BodyPoint.RIGHT_WRIST)
            CueId.SpikeJumpHeight -> torso + legs
        }
    }
}
