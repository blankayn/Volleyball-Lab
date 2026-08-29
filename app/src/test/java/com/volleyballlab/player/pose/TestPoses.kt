package com.volleyballlab.player.pose

import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.Landmark
import com.volleyballlab.player.pose.model.PoseFrame

/**
 * Builders for constructing exact poses in tests.
 *
 * [SyntheticPoseDetector] is fine for driving whole pipelines, but it produces a
 * plausible figure rather than a controlled one. When the assertion is "a
 * straight arm measures 180 degrees", the input has to be a straight arm and
 * nothing else, which is what these are for.
 */
object TestPoses {

    /** A landmark that is fully visible, so nothing is rejected as untracked. */
    fun landmark(x: Float = 0f, y: Float = 0f, z: Float = 0f, visibility: Float = 1f) =
        Landmark(x = x, y = y, z = z, visibility = visibility, presence = visibility)

    /**
     * A frame with every landmark at the origin, then the given points overridden.
     *
     * Useful for angle tests, where only three landmarks matter. Note that
     * untouched landmarks are all coincident, so any angle computed from them is
     * correctly `null` - which is itself worth testing.
     */
    fun frame(
        timestampMs: Long = 0L,
        world: Map<BodyPoint, Landmark> = emptyMap(),
        image: Map<BodyPoint, Landmark> = emptyMap(),
        imageWidth: Int = 720,
        imageHeight: Int = 1280,
        mirrored: Boolean = false,
    ): PoseFrame {
        val worldPoints = MutableList(PoseFrame.EXPECTED_LANDMARK_COUNT) { landmark() }
        val imagePoints = MutableList(PoseFrame.EXPECTED_LANDMARK_COUNT) { landmark() }
        world.forEach { (point, value) -> worldPoints[point.ordinal] = value }
        image.forEach { (point, value) -> imagePoints[point.ordinal] = value }
        return PoseFrame(
            timestampMs = timestampMs,
            image = imagePoints,
            world = worldPoints,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            mirrored = mirrored,
        )
    }

    /**
     * A well-framed standing athlete, as the framing gate should accept.
     *
     * Image coordinates put the body between y=0.20 and y=0.80 (60% coverage,
     * inside the 45-95% band) and horizontally centred, with every tracked
     * landmark fully visible.
     */
    fun wellFramedStanding(timestampMs: Long = 0L): PoseFrame {
        val image = mutableMapOf<BodyPoint, Landmark>()
        fun put(point: BodyPoint, x: Float, y: Float) {
            image[point] = landmark(x = x, y = y)
        }

        put(BodyPoint.NOSE, 0.50f, 0.16f)
        put(BodyPoint.LEFT_SHOULDER, 0.44f, 0.24f)
        put(BodyPoint.RIGHT_SHOULDER, 0.56f, 0.24f)
        put(BodyPoint.LEFT_ELBOW, 0.42f, 0.36f)
        put(BodyPoint.RIGHT_ELBOW, 0.58f, 0.36f)
        put(BodyPoint.LEFT_WRIST, 0.41f, 0.47f)
        put(BodyPoint.RIGHT_WRIST, 0.59f, 0.47f)
        put(BodyPoint.LEFT_HIP, 0.46f, 0.50f)
        put(BodyPoint.RIGHT_HIP, 0.54f, 0.50f)
        put(BodyPoint.LEFT_KNEE, 0.46f, 0.65f)
        put(BodyPoint.RIGHT_KNEE, 0.54f, 0.65f)
        put(BodyPoint.LEFT_ANKLE, 0.46f, 0.80f)
        put(BodyPoint.RIGHT_ANKLE, 0.54f, 0.80f)

        // World-space skeleton, hip-centred and in metres, matching the image pose.
        val world = mutableMapOf<BodyPoint, Landmark>()
        fun putWorld(point: BodyPoint, x: Float, y: Float, z: Float = 0f) {
            world[point] = landmark(x = x, y = y, z = z)
        }
        putWorld(BodyPoint.NOSE, 0f, -0.62f, -0.06f)
        putWorld(BodyPoint.LEFT_SHOULDER, -0.18f, -0.45f)
        putWorld(BodyPoint.RIGHT_SHOULDER, 0.18f, -0.45f)
        putWorld(BodyPoint.LEFT_ELBOW, -0.20f, -0.17f)
        putWorld(BodyPoint.RIGHT_ELBOW, 0.20f, -0.17f)
        putWorld(BodyPoint.LEFT_WRIST, -0.22f, 0.09f)
        putWorld(BodyPoint.RIGHT_WRIST, 0.22f, 0.09f)
        putWorld(BodyPoint.LEFT_HIP, -0.09f, 0f)
        putWorld(BodyPoint.RIGHT_HIP, 0.09f, 0f)
        putWorld(BodyPoint.LEFT_KNEE, -0.10f, 0.45f)
        putWorld(BodyPoint.RIGHT_KNEE, 0.10f, 0.45f)
        putWorld(BodyPoint.LEFT_ANKLE, -0.10f, 0.88f)
        putWorld(BodyPoint.RIGHT_ANKLE, 0.10f, 0.88f)

        return frame(timestampMs = timestampMs, world = world, image = image)
    }

    /** [wellFramedStanding] with every tracked landmark scaled about the frame centre. */
    fun scaledAboutCentre(source: PoseFrame, factor: Float): PoseFrame {
        val scaledImage = source.image.map { landmark ->
            landmark.copy(
                x = 0.5f + (landmark.x - 0.5f) * factor,
                y = 0.5f + (landmark.y - 0.5f) * factor,
            )
        }
        return source.copy(image = scaledImage)
    }

    /** [wellFramedStanding] shifted horizontally, for the off-centre check. */
    fun shiftedHorizontally(source: PoseFrame, dx: Float): PoseFrame =
        source.copy(image = source.image.map { it.copy(x = it.x + dx) })

    /** [wellFramedStanding] with tracked landmarks dropped below a visibility floor. */
    fun withVisibility(source: PoseFrame, visibility: Float): PoseFrame =
        source.copy(
            image = source.image.map { it.copy(visibility = visibility, presence = visibility) },
        )
}
