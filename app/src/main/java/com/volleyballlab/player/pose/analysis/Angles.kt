package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.Landmark
import com.volleyballlab.player.pose.model.PoseFrame
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Geometry helpers shared by every skill analyzer.
 *
 * Everything here operates on **world-space** landmarks (metres, hip-centred).
 * See the class docs on [PoseFrame] for why image-space coordinates are not
 * acceptable input for angles.
 *
 * All returned angles are in degrees, because that is the unit volleyball
 * coaching literature uses and the unit the cue thresholds in this package are
 * written in. Converting once here is cheaper than converting at every threshold.
 */
object Angles {

    /**
     * Interior angle at vertex [b] formed by the segments b-a and b-c, in degrees.
     *
     * Returns a value in `0f..180f`. A fully straight limb is 180 degrees, not 0 -
     * this trips people up, because "elbow extension" reading 175 sounds like
     * almost nothing until you remember 180 is the ceiling.
     *
     * Returns `null` when any of the three points is degenerate (two landmarks at
     * the same position), which happens when tracking briefly collapses. Callers
     * must treat null as "unknown", never as zero: a missing angle scored as 0
     * would read as a catastrophically bad rep rather than as no data.
     */
    fun at(a: Landmark, b: Landmark, c: Landmark): Float? {
        val abx = a.x - b.x
        val aby = a.y - b.y
        val abz = a.z - b.z
        val cbx = c.x - b.x
        val cby = c.y - b.y
        val cbz = c.z - b.z

        val abLen = sqrt(abx * abx + aby * aby + abz * abz)
        val cbLen = sqrt(cbx * cbx + cby * cby + cbz * cbz)
        if (abLen < EPSILON || cbLen < EPSILON) return null

        val dot = abx * cbx + aby * cby + abz * cbz
        // Clamped because accumulated float error can push this a hair outside
        // [-1, 1], and acos of 1.0000001 is NaN.
        val cosine = (dot / (abLen * cbLen)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosine).toDouble()).toFloat()
    }

    /** Convenience overload taking [BodyPoint]s and reading world-space landmarks. */
    fun at(frame: PoseFrame, a: BodyPoint, b: BodyPoint, c: BodyPoint): Float? =
        at(frame.world(a), frame.world(b), frame.world(c))

    /**
     * Angle of the segment from [from] to [to] measured against vertical, in
     * degrees, ignoring depth.
     *
     * 0 means the segment points straight up. Positive values lean toward the
     * athlete's right. Used for trunk lean and for platform angle, where what
     * matters is the tilt relative to gravity rather than a joint's interior angle.
     */
    fun fromVertical(frame: PoseFrame, from: BodyPoint, to: BodyPoint): Float? =
        fromVerticalBetween(frame.world(from), frame.world(to))

    /**
     * As [fromVertical], but between two arbitrary landmarks.
     *
     * Needed because the most useful trunk measurement runs from the hip centre
     * to the shoulder centre, and neither of those is a real landmark - they are
     * midpoints, so there is no [BodyPoint] to name them with.
     */
    fun fromVerticalBetween(a: Landmark, b: Landmark): Float? {
        val dx = b.x - a.x
        // World-space y increases downward, matching image convention, so an
        // upward segment has a negative dy. Negating here makes "up" read as 0.
        val dy = -(b.y - a.y)
        if (abs(dx) < EPSILON && abs(dy) < EPSILON) return null
        return Math.toDegrees(atan2(dx.toDouble(), dy.toDouble())).toFloat()
    }

    /**
     * Sideways trunk lean from vertical, in degrees. 0 is upright, positive leans
     * toward the athlete's right.
     *
     * This is lean in the **frontal** plane. For leaning forward or back, which is
     * what most volleyball trunk cues actually mean, use [trunkForwardLean].
     */
    fun trunkLateralLean(frame: PoseFrame): Float? =
        fromVerticalBetween(hipCentre(frame), shoulderCentre(frame))

    /**
     * Forward trunk lean from vertical, in degrees. Positive leans toward the
     * camera, negative leans away.
     *
     * This is lean in the **sagittal** plane, and it is the one the coaching cues
     * mean. "Stay tall on the serve" is about not leaning back; "lean into the
     * ball on a pass" is about getting the chest forward. Neither has anything to
     * do with sideways lean, so both need depth rather than the x axis.
     *
     * MediaPipe's world z decreases toward the camera, hence the negation: it
     * makes leaning toward the lens read as positive, which is the direction a
     * coach would call "forward".
     */
    fun trunkForwardLean(frame: PoseFrame): Float? {
        val hip = hipCentre(frame)
        val shoulder = shoulderCentre(frame)
        val forward = -(shoulder.z - hip.z)
        val up = -(shoulder.y - hip.y)
        if (abs(forward) < EPSILON && abs(up) < EPSILON) return null
        return Math.toDegrees(atan2(forward.toDouble(), up.toDouble())).toFloat()
    }

    /**
     * How far the shoulder line is off horizontal, in degrees, always positive.
     *
     * Used by the forearm-pass cue for level shoulders: a tilted shoulder line
     * tilts the platform, and a tilted platform sends the ball sideways.
     */
    fun shoulderTilt(frame: PoseFrame): Float? {
        val left = frame.world(BodyPoint.LEFT_SHOULDER)
        val right = frame.world(BodyPoint.RIGHT_SHOULDER)
        val dx = right.x - left.x
        val dy = right.y - left.y
        if (abs(dx) < EPSILON && abs(dy) < EPSILON) return null
        return abs(Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat())
    }

    /** Midpoint of two landmarks. Visibility carries through as the weaker of the two. */
    fun midpoint(a: Landmark, b: Landmark): Landmark = Landmark(
        x = (a.x + b.x) / 2f,
        y = (a.y + b.y) / 2f,
        z = (a.z + b.z) / 2f,
        visibility = minOf(a.visibility, b.visibility),
        presence = minOf(a.presence, b.presence),
    )

    /** Centre of the hips, the origin world landmarks are expressed relative to. */
    fun hipCentre(frame: PoseFrame): Landmark =
        midpoint(frame.world(BodyPoint.LEFT_HIP), frame.world(BodyPoint.RIGHT_HIP))

    /** Centre of the shoulders. */
    fun shoulderCentre(frame: PoseFrame): Landmark =
        midpoint(frame.world(BodyPoint.LEFT_SHOULDER), frame.world(BodyPoint.RIGHT_SHOULDER))

    /** Hip centre in **image** space. This is the one that moves when the athlete jumps. */
    fun hipCentreImage(frame: PoseFrame): Landmark =
        midpoint(frame.image(BodyPoint.LEFT_HIP), frame.image(BodyPoint.RIGHT_HIP))

    /** Shoulder centre in **image** space. */
    fun shoulderCentreImage(frame: PoseFrame): Landmark =
        midpoint(frame.image(BodyPoint.LEFT_SHOULDER), frame.image(BodyPoint.RIGHT_SHOULDER))

    /**
     * Metres of real vertical travel per unit of normalised image y.
     *
     * ## Why jump height cannot come from world landmarks
     *
     * World landmarks are defined with their origin at the midpoint of the hips.
     * The hip centre is therefore at approximately (0, 0, 0) in every single
     * frame, whether the athlete is standing flat or at the top of a jump. Asking
     * world space how high the hips are is asking a question it cannot answer by
     * construction, and it will happily return ~0 forever.
     *
     * Image space does record the jump - the hips visibly rise in frame - but its
     * units are fractions of frame height, which vary with how far the athlete
     * stands from the lens. So neither space is sufficient alone.
     *
     * The fix is to use each for what it knows. World space gives the athlete's
     * torso length in true metres. Image space gives that same torso as a
     * fraction of frame height. Their ratio is a per-athlete, per-position scale
     * factor that converts image displacement into metres, and it recalibrates on
     * every frame, so it stays correct even if the athlete drifts nearer or
     * further from the camera mid-drill.
     *
     * Returns null if the torso is degenerate in either space, which happens when
     * tracking briefly collapses.
     */
    fun verticalScale(frame: PoseFrame): Float? {
        val torsoMetres = distance(shoulderCentre(frame), hipCentre(frame))
        val torsoImage = distance2D(shoulderCentreImage(frame), hipCentreImage(frame))
        if (torsoMetres < EPSILON || torsoImage < EPSILON) return null
        return torsoMetres / torsoImage
    }

    /**
     * How far the hips have risen above [baselineImageY], in metres.
     *
     * This is the jump signal for spiking and blocking. [baselineImageY] is the
     * athlete's standing hip height in normalised image units, maintained by
     * [RepSegmenter] from the preparation phase of each rep. Image y increases
     * downward, so rising hips means a decreasing y, hence the subtraction order.
     *
     * Returns null when [verticalScale] cannot be established.
     */
    fun hipRiseMetres(frame: PoseFrame, baselineImageY: Float): Float? {
        val scale = verticalScale(frame) ?: return null
        return (baselineImageY - hipCentreImage(frame).y) * scale
    }

    /** Straight-line 3D distance between two landmarks. */
    fun distance(a: Landmark, b: Landmark): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Straight-line distance ignoring depth, for image-space landmarks. */
    fun distance2D(a: Landmark, b: Landmark): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * How square the shoulders are to the camera, in `0f..1f`.
     *
     * 1 means fully facing the lens, 0 means fully side-on. Derived from how
     * foreshortened the shoulder line is in depth versus width. Several cues are
     * only meaningful from a front view, so analyzers use this to decide whether
     * to score a cue or report it as unmeasurable.
     */
    fun facingCamera(frame: PoseFrame): Float {
        val left = frame.world(BodyPoint.LEFT_SHOULDER)
        val right = frame.world(BodyPoint.RIGHT_SHOULDER)
        val width = abs(left.x - right.x)
        val depth = abs(left.z - right.z)
        val span = sqrt(width * width + depth * depth)
        if (span < EPSILON) return 0f
        return (width / span).coerceIn(0f, 1f)
    }

    private const val EPSILON = 1e-6f
}
