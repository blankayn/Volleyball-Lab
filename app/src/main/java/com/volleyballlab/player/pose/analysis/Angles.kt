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
     * The direction the athlete is facing, as a horizontal unit vector in world
     * x-z. Null when it cannot be determined.
     *
     * ## Why this is needed at all
     *
     * World landmarks are hip-centred but their **axes are the camera's**, not the
     * body's. So camera -z means "toward the lens", which is only the athlete's
     * forward when they happen to be facing it. Every depth measurement that means
     * something anatomical - how far the chest leans, how far the hands reach past
     * the shoulders - has to be projected onto this rather than read off z.
     *
     * The vector is the horizontal normal to the shoulder line. That leaves two
     * candidates 180 degrees apart, and the nose picks between them: it sits in
     * front of the shoulder line on any pose a camera can see the face in.
     *
     * Returns null rather than guessing when the nose projects too close to the
     * shoulder line to resolve front from back. Measured across 2,849 real frames
     * the nose sits a median 0.162 m forward, and under 0.02 m on 0.21% of them -
     * so the guard costs almost nothing and prevents a silent 180-degree
     * inversion, which is the failure this whole helper exists to end.
     */
    fun forwardAxis(frame: PoseFrame): ForwardAxis? {
        val left = frame.world(BodyPoint.LEFT_SHOULDER)
        val right = frame.world(BodyPoint.RIGHT_SHOULDER)
        val acrossX = right.x - left.x
        val acrossZ = right.z - left.z
        val span = sqrt(acrossX * acrossX + acrossZ * acrossZ)
        if (span < EPSILON) return null

        // Rotate the shoulder line 90 degrees in the horizontal plane.
        val normalX = -acrossZ / span
        val normalZ = acrossX / span

        val centre = shoulderCentre(frame)
        val nose = frame.world(BodyPoint.NOSE)
        val towardNose = (nose.x - centre.x) * normalX + (nose.z - centre.z) * normalZ
        if (abs(towardNose) < MIN_FACING_EVIDENCE) return null

        return if (towardNose < 0f) {
            ForwardAxis(-normalX, -normalZ)
        } else {
            ForwardAxis(normalX, normalZ)
        }
    }

    /**
     * How far [to] sits ahead of [from] along the athlete's own forward
     * direction, in metres. Negative is behind.
     *
     * This is the anatomical answer to "how far forward", as distinct from the
     * camera-relative one that a raw z difference gives.
     */
    fun alongForward(frame: PoseFrame, from: Landmark, to: Landmark): Float? {
        val axis = forwardAxis(frame) ?: return null
        return (to.x - from.x) * axis.x + (to.z - from.z) * axis.z
    }

    /**
     * Forward trunk lean from vertical, in degrees. Positive leans the way the
     * athlete is facing, negative leans back.
     *
     * This is lean in the **sagittal** plane, and it is the one the coaching cues
     * mean. "Stay tall on the serve" is about not leaning back; "lean into the
     * ball on a pass" is about getting the chest forward. Neither has anything to
     * do with sideways lean, so both need depth rather than the x axis.
     *
     * Measured against the athlete's own [forwardAxis] rather than against camera
     * z. Those agree only while the athlete faces the lens: on 7,274 frames of
     * real footage the camera-relative version averaged +15.5 degrees facing the
     * lens and -20.8 facing away, so a rear-facing passer scored zero on
     * `PassTrunkAngle` - whose band is entirely positive - no matter how well they
     * were actually leaning.
     */
    fun trunkForwardLean(frame: PoseFrame): Float? {
        val hip = hipCentre(frame)
        val shoulder = shoulderCentre(frame)
        val forward = alongForward(frame, hip, shoulder) ?: return null
        val up = -(shoulder.y - hip.y)
        if (abs(forward) < EPSILON && abs(up) < EPSILON) return null
        return Math.toDegrees(atan2(forward.toDouble(), up.toDouble())).toFloat()
    }

    /**
     * How far the shoulder line is off horizontal, in degrees, in `0f..90f`.
     *
     * Used by the forearm-pass cue for level shoulders: a tilted shoulder line
     * tilts the platform, and a tilted platform sends the ball sideways.
     *
     * ## Why the fold at the end
     *
     * The shoulder line is a *direction*, but tilt is an *orientation* - a line
     * leaning ten degrees is equally tilted whichever end you measure from. The
     * distinction matters here because world x is a camera axis, not a body one,
     * so the vector left-shoulder to right-shoulder points one way when the
     * athlete faces the lens and the other way when they face away.
     *
     * Without the fold, level shoulders read ~0 from behind and ~180 from the
     * front, and the reading flips mid-rep as the athlete turns. `abs` alone does
     * not help: it folds the sign of `dy`, not of `dx`. Measured over 7,318
     * frames of real footage the unfolded value averaged 156.5 degrees and landed
     * inside this cue's 0..12 band on 1.98% of frames - so `PassShouldersLevel`
     * failed essentially every rep. Folded, the same frames average 13.8 with a
     * median of 4.4.
     *
     * This survived because no fixture in the repo poses an athlete facing away,
     * and `TestPoses.wellFramedStanding()` pairs a camera-facing nose with a
     * rear-view shoulder layout - a combination real MediaPipe emits in under 4%
     * of frames. See `AnglesTest.level shoulders read zero from the front too`.
     */
    fun shoulderTilt(frame: PoseFrame): Float? {
        val left = frame.world(BodyPoint.LEFT_SHOULDER)
        val right = frame.world(BodyPoint.RIGHT_SHOULDER)
        val dx = right.x - left.x
        val dy = right.y - left.y
        if (abs(dx) < EPSILON && abs(dy) < EPSILON) return null
        val directed = abs(Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat())
        return minOf(directed, 180f - directed)
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

    /**
     * Least nose-to-shoulder-line offset, in metres, that resolves front from
     * back. See [forwardAxis]; measured p0.1 of the real distribution is 0.011 m
     * and the median 0.162 m, so this rejects genuine ambiguity and little else.
     */
    private const val MIN_FACING_EVIDENCE = 0.02f
}

/** A horizontal unit vector in world x-z. See [Angles.forwardAxis]. */
data class ForwardAxis(val x: Float, val z: Float)
