package com.volleyballlab.player.pose

import com.volleyballlab.player.pose.analysis.Facing
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.Landmark
import com.volleyballlab.player.pose.model.PoseFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A posable stick figure for movement tests.
 *
 * [TestPoses] builds one exact pose and [SyntheticPoseDetector] plays one fixed
 * routine. Neither can ask "what if the athlete bends their knees with their
 * arms at their sides", which is the question the movement checks exist to
 * answer. This figure is posed by joint angle, then rendered into image and
 * world landmarks that agree with each other from any of the four views.
 *
 * Proportions are a rough adult. Nothing here is a claim about real technique;
 * the figure exists so a test can say exactly which limbs moved.
 *
 * @param leftRaise how far the left upper arm is lifted forward and up from
 *   hanging, in degrees. 0 hangs at the side, 90 points at the horizon, 180 is
 *   straight overhead.
 * @param leftElbow interior elbow angle in degrees. 180 is a straight arm, and
 *   the forearm folds upward as it closes.
 * @param join 0 leaves the hands where the arms put them, 1 brings both wrists
 *   together on the midline, as in a forearm platform.
 * @param knee knee flexion in degrees. 0 is a straight leg. The feet stay on
 *   the floor, so the hips sink in the image as this grows.
 */
internal data class Rig(
    val leftRaise: Float = 0f,
    val leftElbow: Float = 170f,
    val rightRaise: Float = 0f,
    val rightElbow: Float = 170f,
    val join: Float = 0f,
    val knee: Float = 0f,
) {
    /** Both arms posed alike. */
    fun arms(raise: Float, elbow: Float) =
        copy(leftRaise = raise, leftElbow = elbow, rightRaise = raise, rightElbow = elbow)

    fun blend(to: Rig, t: Float) = Rig(
        mix(leftRaise, to.leftRaise, t), mix(leftElbow, to.leftElbow, t),
        mix(rightRaise, to.rightRaise, t), mix(rightElbow, to.rightElbow, t),
        mix(join, to.join, t), mix(knee, to.knee, t),
    )

    /**
     * @param view which way the figure faces, in its own left and right.
     * @param jitter adds per-landmark noise of roughly the size a real tracker
     *   shows on a still athlete. Null renders the exact pose.
     * @param hidden landmarks reported as not visible, as an occluded limb is.
     */
    fun frame(
        timestampMs: Long,
        view: Facing = Facing.Forward,
        jitter: Random? = null,
        hidden: Set<BodyPoint> = emptySet(),
    ): PoseFrame {
        val body = mutableMapOf<BodyPoint, Vec>()
        body[BodyPoint.LEFT_HIP] = Vec(HIP_HALF, 0f, 0f)
        body[BodyPoint.RIGHT_HIP] = Vec(-HIP_HALF, 0f, 0f)
        body[BodyPoint.NOSE] = Vec(0f, -0.62f, 0.08f)
        body[BodyPoint.LEFT_EYE] = Vec(0.03f, -0.65f, 0.07f)
        body[BodyPoint.RIGHT_EYE] = Vec(-0.03f, -0.65f, 0.07f)

        arm(body, sign = 1f, leftRaise, leftElbow, BodyPoint.LEFT_SHOULDER, BodyPoint.LEFT_ELBOW, BodyPoint.LEFT_WRIST)
        arm(body, sign = -1f, rightRaise, rightElbow, BodyPoint.RIGHT_SHOULDER, BodyPoint.RIGHT_ELBOW, BodyPoint.RIGHT_WRIST)

        // The thigh swings forward and the shin back by half the flexion each,
        // which keeps the feet under the hips like a real squat.
        val half = radians(knee / 2f)
        listOf(1f to (BodyPoint.LEFT_KNEE to BodyPoint.LEFT_ANKLE), -1f to (BodyPoint.RIGHT_KNEE to BodyPoint.RIGHT_ANKLE))
            .forEach { (sign, joints) ->
                val kneePoint = Vec(sign * LEG_HALF, THIGH * cos(half), THIGH * sin(half))
                body[joints.first] = kneePoint
                body[joints.second] = Vec(kneePoint.x, kneePoint.y + SHIN * cos(half), kneePoint.z - SHIN * sin(half))
            }
        val hipDrop = (THIGH + SHIN) * (1f - cos(half))

        val world = MutableList(PoseFrame.EXPECTED_LANDMARK_COUNT) { TestPoses.landmark() }
        val image = MutableList(PoseFrame.EXPECTED_LANDMARK_COUNT) { TestPoses.landmark(x = 0.5f, y = ANCHOR_Y) }
        val faceHidden = view == Facing.Away
        body.forEach { (point, at) ->
            val noise = { size: Float -> jitter?.let { (it.nextFloat() - 0.5f) * 2f * size } ?: 0f }
            // Turn the figure, then look at it down the camera's z axis.
            val (x, z) = when (view) {
                Facing.Forward -> at.x to -at.z
                Facing.Right -> -at.z to -at.x
                Facing.Left -> at.z to at.x
                Facing.Away -> -at.x to at.z
            }
            val seen = if (point in hidden || faceHidden && point in FACE) 0.1f else 1f
            val wx = x + noise(WORLD_JITTER)
            val wy = at.y + noise(WORLD_JITTER)
            val wz = z + noise(WORLD_JITTER)
            world[point.ordinal] = Landmark(wx, wy, wz, seen, seen)
            image[point.ordinal] = Landmark(
                x = 0.5f + x * SCALE + noise(IMAGE_JITTER),
                y = ANCHOR_Y + (at.y + hipDrop) * SCALE + noise(IMAGE_JITTER),
                z = z, visibility = seen, presence = seen,
            )
        }
        return PoseFrame(timestampMs, image, world, imageWidth = 720, imageHeight = 1280, mirrored = false)
    }

    private fun arm(
        body: MutableMap<BodyPoint, Vec>, sign: Float, raise: Float, elbowAngle: Float,
        shoulderPoint: BodyPoint, elbowPoint: BodyPoint, wristPoint: BodyPoint,
    ) {
        val shoulder = Vec(sign * SHOULDER_HALF, -TORSO, 0f)
        val upper = radians(raise)
        val fore = radians(raise + 180f - elbowAngle)
        val elbow = Vec(shoulder.x, shoulder.y + UPPER_ARM * cos(upper), UPPER_ARM * sin(upper))
        val wrist = Vec(elbow.x, elbow.y + FOREARM * cos(fore), elbow.z + FOREARM * sin(fore))
        body[shoulderPoint] = shoulder
        body[elbowPoint] = elbow.copy(x = mix(elbow.x, sign * 0.11f, join))
        body[wristPoint] = wrist.copy(x = mix(wrist.x, sign * 0.03f, join))
    }

    /** Body frame: x toward the athlete's own left, y down, z the way they face. */
    private data class Vec(val x: Float, val y: Float, val z: Float)

    companion object {
        /** Standing tall with the arms hanging. */
        val Standing = Rig()

        private const val HIP_HALF = 0.09f
        private const val SHOULDER_HALF = 0.18f
        private const val LEG_HALF = 0.10f
        private const val TORSO = 0.45f
        private const val UPPER_ARM = 0.28f
        private const val FOREARM = 0.26f
        private const val THIGH = 0.45f
        private const val SHIN = 0.43f

        /** Image units per metre, and where the standing hip line sits. */
        private const val SCALE = 0.38f
        private const val ANCHOR_Y = 0.52f

        private const val IMAGE_JITTER = 0.004f
        private const val WORLD_JITTER = 0.008f
        private val FACE = setOf(BodyPoint.NOSE, BodyPoint.LEFT_EYE, BodyPoint.RIGHT_EYE)

        private fun mix(from: Float, to: Float, t: Float) = from + (to - from) * t
        private fun radians(degrees: Float) = (degrees * PI / 180.0).toFloat()

        /**
         * Frames that move smoothly through [keys], each a time offset in
         * milliseconds and the pose reached at it.
         */
        fun motion(
            startMs: Long,
            vararg keys: Pair<Long, Rig>,
            view: Facing = Facing.Forward,
            jitter: Random? = null,
            hidden: Set<BodyPoint> = emptySet(),
            stepMs: Long = 33L,
        ): List<PoseFrame> {
            val frames = mutableListOf<PoseFrame>()
            var time = keys.first().first
            while (time <= keys.last().first) {
                val next = keys.indexOfFirst { it.first >= time }.coerceAtLeast(1)
                val (fromMs, from) = keys[next - 1]
                val (toMs, to) = keys[next]
                val linear = if (toMs == fromMs) 1f else (time - fromMs).toFloat() / (toMs - fromMs)
                // Ease in and out, so the figure does not jerk at each key.
                val eased = 0.5f - 0.5f * cos(PI.toFloat() * linear.coerceIn(0f, 1f))
                frames += from.blend(to, eased).frame(startMs + time, view, jitter, hidden)
                time += stepMs
            }
            return frames
        }
    }
}
