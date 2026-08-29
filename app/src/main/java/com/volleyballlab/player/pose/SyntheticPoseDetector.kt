package com.volleyballlab.player.pose

import android.graphics.Bitmap
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.Landmark
import com.volleyballlab.player.pose.model.PoseFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A [PoseDetector] that ignores the camera and emits a scripted articulated
 * figure performing a repeating movement cycle.
 *
 * ## What it is for
 *
 * - **Emulator work.** The Android emulator has no usable camera feed and no GPU
 *   delegate, so without this the entire capture screen is undevelopable without
 *   a physical phone in hand.
 * - **Deterministic testing.** The rep segmenter and the analyzers can be driven
 *   with an exactly known movement, which makes "did it count four reps" a real
 *   assertion instead of a judgement call.
 * - **Demonstrations.** The flow can be walked end to end without an athlete,
 *   a ball, or a gym.
 *
 * ## What it is emphatically not
 *
 * It is not a model, and the scores it produces mean nothing about anybody's
 * technique. The figure's proportions are a rough adult average and its motion is
 * a smooth analytic curve, not human movement. Never quote a number that came out
 * of this in the evaluation chapter.
 *
 * The cycle runs preparation, loading, contact, follow-through and back, so every
 * [com.volleyballlab.player.domain.model.RepPhase] is exercised, and it is tuned
 * to stay inside the framing gate in
 * [com.volleyballlab.player.pose.quality.PoseQualityValidator] so the capture
 * screen actually reaches the recording state.
 */
class SyntheticPoseDetector(
    private val cyclePeriodMs: Long = 2400L,
) : PoseDetector {

    private val _poses = MutableSharedFlow<PoseFrame>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val poses: SharedFlow<PoseFrame> = _poses.asSharedFlow()

    override fun submit(bitmap: Bitmap, timestampMs: Long, mirrored: Boolean) {
        _poses.tryEmit(frameAt(timestampMs, bitmap.width, bitmap.height, mirrored))
    }

    override fun close() = Unit

    /** Exposed directly so tests can generate poses without allocating a Bitmap. */
    fun frameAt(
        timestampMs: Long,
        imageWidth: Int = 720,
        imageHeight: Int = 1280,
        mirrored: Boolean = false,
    ): PoseFrame {
        val phase = (timestampMs % cyclePeriodMs).toFloat() / cyclePeriodMs

        // Three overlapping bell curves drive the whole figure. Offsetting their
        // peaks is what produces a recognisable sequence rather than everything
        // moving at once: knees load first, then the body rises, then the arms
        // reach their highest point at contact.
        val kneeLoad = bell(phase, peak = 0.32f, width = 0.26f)
        val jump = bell(phase, peak = 0.50f, width = 0.22f)
        val armLift = bell(phase, peak = 0.52f, width = 0.34f)

        val world = buildWorldPose(kneeLoad = kneeLoad, armLift = armLift)
        val image = project(world, jumpMetres = jump * MAX_JUMP_METRES)

        return PoseFrame(
            timestampMs = timestampMs,
            image = image,
            world = world,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            mirrored = mirrored,
        )
    }

    /**
     * Builds the figure in world space: metres, origin at the hip midpoint, y
     * increasing downward, matching MediaPipe's convention.
     */
    private fun buildWorldPose(kneeLoad: Float, armLift: Float): List<Landmark> {
        val points = MutableList(PoseFrame.EXPECTED_LANDMARK_COUNT) { ORIGIN }

        fun set(point: BodyPoint, x: Float, y: Float, z: Float) {
            points[point.ordinal] = Landmark(x, y, z, visibility = 1f, presence = 1f)
        }

        set(BodyPoint.LEFT_HIP, -HIP_HALF_WIDTH, 0f, 0f)
        set(BodyPoint.RIGHT_HIP, HIP_HALF_WIDTH, 0f, 0f)
        set(BodyPoint.LEFT_SHOULDER, -SHOULDER_HALF_WIDTH, -TORSO_LENGTH, 0f)
        set(BodyPoint.RIGHT_SHOULDER, SHOULDER_HALF_WIDTH, -TORSO_LENGTH, 0f)

        // Head cluster. Only positioned well enough to stay on screen and look
        // sane in the overlay; nothing scores off these.
        val headY = -TORSO_LENGTH - NECK_LENGTH
        set(BodyPoint.NOSE, 0f, headY, -0.06f)
        set(BodyPoint.LEFT_EYE_INNER, -0.02f, headY - 0.02f, -0.05f)
        set(BodyPoint.LEFT_EYE, -0.03f, headY - 0.02f, -0.05f)
        set(BodyPoint.LEFT_EYE_OUTER, -0.04f, headY - 0.02f, -0.04f)
        set(BodyPoint.RIGHT_EYE_INNER, 0.02f, headY - 0.02f, -0.05f)
        set(BodyPoint.RIGHT_EYE, 0.03f, headY - 0.02f, -0.05f)
        set(BodyPoint.RIGHT_EYE_OUTER, 0.04f, headY - 0.02f, -0.04f)
        set(BodyPoint.LEFT_EAR, -0.07f, headY - 0.01f, 0f)
        set(BodyPoint.RIGHT_EAR, 0.07f, headY - 0.01f, 0f)
        set(BodyPoint.MOUTH_LEFT, -0.03f, headY + 0.04f, -0.05f)
        set(BodyPoint.MOUTH_RIGHT, 0.03f, headY + 0.04f, -0.05f)

        // Arms. Elevation is measured from hanging straight down, so 0 is arms at
        // the sides and MAX_ARM_ELEVATION is near-overhead at contact. Capped
        // short of a true 180 so the wrists stay inside the frame at the top of
        // the jump - otherwise the framing gate would reject its own test figure.
        val elevation = armLift * MAX_ARM_ELEVATION
        // The elbow straightens as the arm rises, which is what a real swing does
        // and what gives the extension cues something to measure.
        val elbowBend = (1f - armLift) * MAX_ELBOW_BEND

        listOf(
            Triple(BodyPoint.LEFT_SHOULDER, BodyPoint.LEFT_ELBOW, -1f),
            Triple(BodyPoint.RIGHT_SHOULDER, BodyPoint.RIGHT_ELBOW, 1f),
        ).forEach { (shoulderPoint, elbowPoint, side) ->
            val shoulder = points[shoulderPoint.ordinal]
            val elbowX = shoulder.x + side * UPPER_ARM * sin(elevation)
            val elbowY = shoulder.y + UPPER_ARM * cos(elevation)
            set(elbowPoint, elbowX, elbowY, 0f)

            val forearmAngle = elevation - elbowBend
            val wristX = elbowX + side * FOREARM * sin(forearmAngle)
            val wristY = elbowY + FOREARM * cos(forearmAngle)
            val wristPoint = if (side < 0) BodyPoint.LEFT_WRIST else BodyPoint.RIGHT_WRIST
            set(wristPoint, wristX, wristY, -0.04f)

            // Hand points, offset slightly beyond the wrist along the forearm.
            val handX = wristX + side * HAND * sin(forearmAngle)
            val handY = wristY + HAND * cos(forearmAngle)
            if (side < 0) {
                set(BodyPoint.LEFT_INDEX, handX, handY, -0.05f)
                set(BodyPoint.LEFT_PINKY, handX - 0.03f, handY, -0.04f)
                set(BodyPoint.LEFT_THUMB, handX + 0.02f, handY - 0.01f, -0.05f)
            } else {
                set(BodyPoint.RIGHT_INDEX, handX, handY, -0.05f)
                set(BodyPoint.RIGHT_PINKY, handX + 0.03f, handY, -0.04f)
                set(BodyPoint.RIGHT_THUMB, handX - 0.02f, handY - 0.01f, -0.05f)
            }
        }

        // Legs. Loading pulls the knees up and forward relative to the hips, which
        // is how a squat looks in a hip-centred coordinate system.
        val kneeY = lerp(KNEE_Y_STANDING, KNEE_Y_LOADED, kneeLoad)
        val kneeZ = lerp(0f, KNEE_Z_LOADED, kneeLoad)
        val ankleY = lerp(ANKLE_Y_STANDING, ANKLE_Y_LOADED, kneeLoad)

        listOf(-1f to BodyPoint.LEFT_KNEE, 1f to BodyPoint.RIGHT_KNEE).forEach { (side, knee) ->
            set(knee, side * LEG_HALF_WIDTH * 1.15f, kneeY, kneeZ)
        }
        listOf(-1f to BodyPoint.LEFT_ANKLE, 1f to BodyPoint.RIGHT_ANKLE).forEach { (side, ankle) ->
            set(ankle, side * LEG_HALF_WIDTH, ankleY, 0f)
        }
        set(BodyPoint.LEFT_HEEL, -LEG_HALF_WIDTH, ankleY + 0.03f, 0.05f)
        set(BodyPoint.RIGHT_HEEL, LEG_HALF_WIDTH, ankleY + 0.03f, 0.05f)
        set(BodyPoint.LEFT_FOOT_INDEX, -LEG_HALF_WIDTH, ankleY + 0.05f, -0.11f)
        set(BodyPoint.RIGHT_FOOT_INDEX, LEG_HALF_WIDTH, ankleY + 0.05f, -0.11f)

        return points
    }

    /**
     * Projects world metres into normalised image coordinates.
     *
     * Orthographic rather than perspective, which is a simplification that does
     * not matter here: nothing consumes the synthetic image landmarks except the
     * overlay and the framing gate, and neither cares about foreshortening.
     *
     * [jumpMetres] is applied here and *only* here. It cannot be applied in world
     * space, because world coordinates are hip-centred by definition, so the hips
     * are pinned at the origin no matter how high the athlete gets. This mirrors
     * exactly why real jump height is measured in image space - see
     * [com.volleyballlab.player.pose.analysis.Angles.hipRiseMetres].
     */
    private fun project(world: List<Landmark>, jumpMetres: Float): List<Landmark> {
        val yOffset = ANCHOR_Y - jumpMetres * METRES_TO_IMAGE
        return world.map { landmark ->
            Landmark(
                x = 0.5f + landmark.x * METRES_TO_IMAGE,
                y = yOffset + landmark.y * METRES_TO_IMAGE,
                // Depth stays in metres. Image-space z is only ever compared
                // relatively, and no consumer treats it as normalised.
                z = landmark.z,
                visibility = 1f,
                presence = 1f,
            )
        }
    }

    /**
     * A smooth 0..1 bump peaking at [peak], zero outside a half-[width] window.
     *
     * A raised cosine rather than a triangle so velocity is continuous. The rep
     * segmenter differentiates its input signal, and a triangle's corner would
     * appear as an instantaneous velocity flip and read as a spurious rep boundary.
     */
    private fun bell(phase: Float, peak: Float, width: Float): Float {
        val distance = kotlin.math.abs(phase - peak)
        if (distance >= width) return 0f
        return (0.5f * (1f + cos(PI.toFloat() * distance / width)))
    }

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    private companion object {
        val ORIGIN = Landmark(0f, 0f, 0f, visibility = 1f, presence = 1f)

        // Rough adult proportions, in metres.
        const val HIP_HALF_WIDTH = 0.09f
        const val SHOULDER_HALF_WIDTH = 0.18f
        const val TORSO_LENGTH = 0.45f
        const val NECK_LENGTH = 0.17f
        const val UPPER_ARM = 0.28f
        const val FOREARM = 0.26f
        const val HAND = 0.08f
        const val LEG_HALF_WIDTH = 0.10f

        const val KNEE_Y_STANDING = 0.45f
        const val KNEE_Y_LOADED = 0.34f
        const val KNEE_Z_LOADED = -0.20f
        const val ANKLE_Y_STANDING = 0.88f
        const val ANKLE_Y_LOADED = 0.70f

        /** Radians. Short of vertical so the wrists stay in frame at jump apex. */
        val MAX_ARM_ELEVATION = (165.0 * PI / 180.0).toFloat()
        val MAX_ELBOW_BEND = (75.0 * PI / 180.0).toFloat()
        const val MAX_JUMP_METRES = 0.18f

        /**
         * Normalised image units per metre, with the hip line at [ANCHOR_Y].
         * Chosen so the figure spans roughly half the frame height: comfortably
         * above the framing gate's 0.45 floor and well under its 0.95 ceiling,
         * with headroom for the wrists at full reach.
         */
        const val METRES_TO_IMAGE = 0.38f
        const val ANCHOR_Y = 0.50f
    }
}
