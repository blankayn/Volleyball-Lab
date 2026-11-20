package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.Landmark
import com.volleyballlab.player.pose.model.PoseFrame
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A point on the reference figure, in the figure's own frame: x toward the
 * athlete's own left, y down, z the way they face, in metres from the middle of
 * the hips.
 */
internal data class Vec3(val x: Float, val y: Float, val z: Float)

/**
 * Where the athlete is standing in the picture.
 *
 * @param centreX pixel column midway between their feet.
 * @param floorY pixel row their feet are on.
 * @param pixelsPerMetre how many pixels one metre of them covers at that distance.
 */
data class FigurePlacement(val centreX: Float, val floorY: Float, val pixelsPerMetre: Float) {
    fun blend(to: FigurePlacement, t: Float) = FigurePlacement(
        centreX + (to.centreX - centreX) * t,
        floorY + (to.floorY - floorY) * t,
        pixelsPerMetre + (to.pixelsPerMetre - pixelsPerMetre) * t,
    )
}

/**
 * The reference figure, ready to draw.
 *
 * @param points normalised image coordinates, in the same space as
 *   [PoseFrame.image], so the overlay maps and mirrors them exactly as it does
 *   the tracked skeleton.
 * @param headRadius as a fraction of the image height.
 */
data class GuideFigure(
    val moment: ReferenceMoment,
    val facing: Facing,
    val points: Map<BodyPoint, Landmark>,
    val headRadius: Float,
) {
    companion object {
        /** The segments drawn. Fewer than the tracked skeleton: no hands, no face. */
        val Bones: List<Pair<BodyPoint, BodyPoint>> = listOf(
            BodyPoint.LEFT_SHOULDER to BodyPoint.RIGHT_SHOULDER,
            BodyPoint.LEFT_SHOULDER to BodyPoint.LEFT_HIP,
            BodyPoint.RIGHT_SHOULDER to BodyPoint.RIGHT_HIP,
            BodyPoint.LEFT_HIP to BodyPoint.RIGHT_HIP,
            BodyPoint.LEFT_SHOULDER to BodyPoint.LEFT_ELBOW,
            BodyPoint.LEFT_ELBOW to BodyPoint.LEFT_WRIST,
            BodyPoint.RIGHT_SHOULDER to BodyPoint.RIGHT_ELBOW,
            BodyPoint.RIGHT_ELBOW to BodyPoint.RIGHT_WRIST,
            BodyPoint.LEFT_HIP to BodyPoint.LEFT_KNEE,
            BodyPoint.LEFT_KNEE to BodyPoint.LEFT_ANKLE,
            BodyPoint.RIGHT_HIP to BodyPoint.RIGHT_KNEE,
            BodyPoint.RIGHT_KNEE to BodyPoint.RIGHT_ANKLE,
            BodyPoint.LEFT_ANKLE to BodyPoint.LEFT_FOOT_INDEX,
            BodyPoint.RIGHT_ANKLE to BodyPoint.RIGHT_FOOT_INDEX,
        )
    }
}

/**
 * Builds a [ReferencePose] into a figure and stands it where the athlete is.
 *
 * The figure is a flat drawing of a three-dimensional pose, and it is honest
 * about that: whatever points toward or away from the lens is simply
 * foreshortened. Facing the camera, a knee bend shows only as the hips sinking
 * and a forward lean only as a shorter trunk. Side-on, both read plainly, which
 * is why the side view is where a figure like this earns its place.
 */
object ReferenceFigure {

    /** How far the nose sits above the shoulder line, in metres. */
    fun noseAboveShoulders(body: BodyProportions): Float = body.torso * NOSE_ABOVE_SHOULDERS

    /** Every joint of [pose] on a body of these proportions. */
    internal fun joints(pose: ReferencePose, body: BodyProportions): Map<BodyPoint, Vec3> {
        val points = mutableMapOf<BodyPoint, Vec3>()
        points[BodyPoint.LEFT_HIP] = Vec3(body.hipHalf, 0f, 0f)
        points[BodyPoint.RIGHT_HIP] = Vec3(-body.hipHalf, 0f, 0f)

        // Legs, feet about shoulder width apart. The thigh takes more of the bend
        // than the shin, which sits the hips back behind the feet as a real knee
        // bend does; split evenly, a deep stance with a forward lean looks like
        // it is toppling over its toes.
        val flexion = radians(180f - pose.knee)
        val thighSwing = flexion * THIGH_SHARE_OF_BEND
        val shinSwing = flexion - thighSwing
        val spread = (body.shoulderHalf - body.hipHalf) / 2f
        val thigh = inPlane(body.thigh, spread)
        val shin = inPlane(body.shin, spread)
        listOf(
            1f to Triple(BodyPoint.LEFT_KNEE, BodyPoint.LEFT_ANKLE, BodyPoint.LEFT_FOOT_INDEX),
            -1f to Triple(BodyPoint.RIGHT_KNEE, BodyPoint.RIGHT_ANKLE, BodyPoint.RIGHT_FOOT_INDEX),
        ).forEach { (sign, leg) ->
            val knee = Vec3(sign * (body.hipHalf + spread), thigh * cos(thighSwing), thigh * sin(thighSwing))
            val ankle = Vec3(sign * (body.hipHalf + 2f * spread), knee.y + shin * cos(shinSwing), knee.z - shin * sin(shinSwing))
            points[leg.first] = knee
            points[leg.second] = ankle
            // Toes forward: in a side view this is what shows which way to face.
            points[leg.third] = Vec3(ankle.x, ankle.y + body.shin * 0.08f, ankle.z + body.shin * FOOT_LENGTH)
        }

        // Everything above the hips leans with the trunk.
        val lean = radians(pose.trunkLean)
        fun leaned(local: Vec3) = Vec3(
            local.x,
            local.y * cos(lean) + local.z * sin(lean),
            local.z * cos(lean) - local.y * sin(lean),
        )
        points[BodyPoint.NOSE] = leaned(Vec3(0f, -body.torso - noseAboveShoulders(body), body.torso * NOSE_FORWARD))

        listOf(
            Triple(1f, pose.left, Triple(BodyPoint.LEFT_SHOULDER, BodyPoint.LEFT_ELBOW, BodyPoint.LEFT_WRIST)),
            Triple(-1f, pose.right, Triple(BodyPoint.RIGHT_SHOULDER, BodyPoint.RIGHT_ELBOW, BodyPoint.RIGHT_WRIST)),
        ).forEach { (sign, arm, joints) ->
            val shoulder = Vec3(sign * body.shoulderHalf, -body.torso, 0f)
            val azimuth = pose.handGap?.let { gapAzimuth(arm, body, it) } ?: radians(arm.azimuth)
            fun along(length: Float, elevation: Float): Vec3 {
                val angle = radians(elevation)
                return Vec3(sign * length * sin(angle) * sin(azimuth), length * cos(angle), length * sin(angle) * cos(azimuth))
            }
            val upper = along(body.upperArm, arm.upperElevation)
            val fore = along(body.forearm, arm.foreElevation)
            val elbow = Vec3(shoulder.x + upper.x, shoulder.y + upper.y, shoulder.z + upper.z)
            points[joints.first] = leaned(shoulder)
            points[joints.second] = leaned(elbow)
            points[joints.third] = leaned(Vec3(elbow.x + fore.x, elbow.y + fore.y, elbow.z + fore.z))
        }
        return points
    }

    /**
     * Where the athlete stands and how large they are in [frame], or null when
     * the picture cannot say: no torso to measure, or no foot on the floor.
     *
     * The scale is the length of the athlete's whole standing chain in the
     * picture - shin, thigh and trunk - over the same chain in metres. Taking the
     * chain as one, rather than trusting any single segment, is what makes the
     * figure the athlete's height: the tracker's metric lengths are estimates and
     * can be a tenth out on any one bone, but the figure is built from those same
     * lengths, so whatever they get wrong cancels and its shoulders land on the
     * athlete's shoulders.
     */
    fun placement(frame: PoseFrame): FigurePlacement? {
        val width = frame.imageWidth.toFloat()
        val height = frame.imageHeight.toFloat()
        if (width <= 0f || height <= 0f) return null
        val torso = listOf(
            BodyPoint.LEFT_SHOULDER, BodyPoint.RIGHT_SHOULDER, BodyPoint.LEFT_HIP, BodyPoint.RIGHT_HIP,
        )
        if (torso.any { !AnalyzerSupport.observed(frame, it) }) return null
        val ankles = listOf(BodyPoint.LEFT_ANKLE, BodyPoint.RIGHT_ANKLE).filter { AnalyzerSupport.observed(frame, it) }
        if (ankles.isEmpty()) return null

        fun pixels(a: Landmark, b: Landmark) = hypot((a.x - b.x) * width, (a.y - b.y) * height)
        var chainPixels = pixels(Angles.shoulderCentreImage(frame), Angles.hipCentreImage(frame))
        var chainMetres = Angles.distance(Angles.shoulderCentre(frame), Angles.hipCentre(frame))
        // Either leg will do, and both are averaged when both were seen. With
        // neither, the trunk alone still gives a usable scale.
        val legs = Side.entries.filter { AnalyzerSupport.legObserved(frame, it) }.map { side ->
            val hip = AnalyzerSupport.hip(side)
            val knee = AnalyzerSupport.knee(side)
            val ankle = AnalyzerSupport.ankle(side)
            (pixels(frame.image(hip), frame.image(knee)) + pixels(frame.image(knee), frame.image(ankle))) to
                (Angles.distance(frame.world(hip), frame.world(knee)) + Angles.distance(frame.world(knee), frame.world(ankle)))
        }
        if (legs.isNotEmpty()) {
            chainPixels += legs.map { it.first }.average().toFloat()
            chainMetres += legs.map { it.second }.average().toFloat()
        }
        if (!chainMetres.isFinite() || chainMetres < MIN_CHAIN_METRES) return null
        val scale = chainPixels / chainMetres
        // From a person filling a twelfth of the frame to one far too close to fit.
        if (!scale.isFinite() || scale !in height * MIN_SCALE_OF_HEIGHT..height * MAX_SCALE_OF_HEIGHT) return null
        return FigurePlacement(
            centreX = ankles.map { frame.image(it).x }.average().toFloat() * width,
            floorY = ankles.maxOf { frame.image(it).y } * height,
            pixelsPerMetre = scale,
        )
    }

    /** [pose] on a body of these proportions, standing at [placement] and turned to [facing]. */
    fun layout(
        pose: ReferencePose,
        moment: ReferenceMoment,
        body: BodyProportions,
        placement: FigurePlacement,
        facing: Facing,
        imageWidth: Int,
        imageHeight: Int,
    ): GuideFigure {
        val joints = joints(pose, body)
        // Turn the figure, then look at it down the camera's axis. Depth is dropped.
        fun across(at: Vec3) = when (facing) {
            Facing.Forward -> at.x
            Facing.Right -> -at.z
            Facing.Left -> at.z
            Facing.Away -> -at.x
        }
        // The feet are the fixed point: they go where the athlete's feet are, and
        // the rest of the position is drawn up from there.
        val leftAnkle = joints.getValue(BodyPoint.LEFT_ANKLE)
        val rightAnkle = joints.getValue(BodyPoint.RIGHT_ANKLE)
        val floor = maxOf(leftAnkle.y, rightAnkle.y)
        val feet = (across(leftAnkle) + across(rightAnkle)) / 2f
        val points = joints.mapValues { (_, at) ->
            Landmark(
                x = (placement.centreX + (across(at) - feet) * placement.pixelsPerMetre) / imageWidth,
                y = (placement.floorY + (at.y - floor) * placement.pixelsPerMetre) / imageHeight,
                z = 0f, visibility = 1f, presence = 1f,
            )
        }
        return GuideFigure(moment, facing, points, body.torso * HEAD_RADIUS * placement.pixelsPerMetre / imageHeight)
    }

    /**
     * The azimuth that puts the wrists [gap] metres apart. Turning both segments
     * together about the trunk leaves the elbow angle and the arm's height as
     * they were; it only swings the hands toward or away from the midline.
     */
    private fun gapAzimuth(arm: ArmPose, body: BodyProportions, gap: Float): Float {
        val reach = body.upperArm * sin(radians(arm.upperElevation)) + body.forearm * sin(radians(arm.foreElevation))
        // Arms pointing straight up or down have no sideways reach to turn.
        if (reach < MIN_SIDEWAYS_REACH) return 0f
        val sine = ((gap / 2f - body.shoulderHalf) / reach).coerceIn(-MAX_TURN_SINE, MAX_TURN_SINE)
        return kotlin.math.asin(sine)
    }

    /** The length a segment covers in the forward-and-down plane once it also spans [sideways]. */
    private fun inPlane(length: Float, sideways: Float): Float =
        sqrt((length * length - sideways * sideways).coerceAtLeast(0f))

    private fun radians(degrees: Float) = Math.toRadians(degrees.toDouble()).toFloat()

    // Head and foot, as shares of the trunk and shin. Drawing only: no cue reads them.
    private const val NOSE_ABOVE_SHOULDERS = 0.38f
    private const val NOSE_FORWARD = 0.14f
    private const val HEAD_RADIUS = 0.21f
    private const val FOOT_LENGTH = 0.45f
    private const val THIGH_SHARE_OF_BEND = 0.6f

    private const val MIN_CHAIN_METRES = 0.25f
    private const val MIN_SCALE_OF_HEIGHT = 0.05f
    private const val MAX_SCALE_OF_HEIGHT = 3f
    private const val MIN_SIDEWAYS_REACH = 0.05f
    private const val MAX_TURN_SINE = 0.95f
}

/**
 * Holds the reference figure steady.
 *
 * The tracked skeleton shows the tracker's wobble as it is, because that is the
 * measurement. A target is different: one that shivers and breathes with every
 * frame cannot be stood in. So the athlete's size and position are averaged
 * over recent frames before the figure is drawn on them.
 *
 * Not thread-safe; driven from the same collector as the pose frames.
 */
class ReferenceFigureTracker(
    private val blend: Float = 0.3f,
    private val maxGapMs: Long = 500L,
) {
    private var body: BodyProportions? = null
    private var placement: FigurePlacement? = null
    private var lastMs: Long? = null

    fun reset() {
        body = null
        placement = null
        lastMs = null
    }

    /** The athlete's steadied proportions and position, or null when [frame] cannot place them. */
    fun update(frame: PoseFrame): Pair<BodyProportions, FigurePlacement>? {
        val gap = lastMs?.let { frame.timestampMs - it }
        if (gap != null && (gap <= 0L || gap > maxGapMs)) reset()
        val seenBody = BodyProportions.observed(frame) ?: return null
        val seenPlacement = ReferenceFigure.placement(frame) ?: return null
        lastMs = frame.timestampMs
        val steadyBody = body?.blend(seenBody, blend) ?: seenBody
        val steadyPlacement = placement?.blend(seenPlacement, blend) ?: seenPlacement
        body = steadyBody
        placement = steadyPlacement
        return steadyBody to steadyPlacement
    }
}
