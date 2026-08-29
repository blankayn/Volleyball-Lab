package com.volleyballlab.player.pose.model

/**
 * A single tracked landmark.
 *
 * The meaning of [x], [y] and [z] depends on which list of a [PoseFrame] this
 * came from. See [PoseFrame.image] and [PoseFrame.world] - conflating the two is
 * the single easiest way to get subtly wrong angles.
 *
 * @param visibility MediaPipe's estimate that the point is present and not
 *   occluded, in `0f..1f`. Landmarks are still emitted for occluded joints, with
 *   coordinates that are plausible guesses, so this value is the only signal that
 *   a joint is inferred rather than seen.
 * @param presence confidence that the point exists in the frame at all.
 */
data class Landmark(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float,
    val presence: Float,
) {
    /** Whether this landmark is trustworthy enough to score a cue from. */
    fun isReliable(threshold: Float = DEFAULT_VISIBILITY_THRESHOLD): Boolean =
        visibility >= threshold

    companion object {
        /**
         * Chosen empirically rather than theoretically. Below about 0.5 MediaPipe
         * is essentially extrapolating a joint's position from the rest of the
         * skeleton, and angles computed from extrapolated joints drift enough to
         * flip a cue from pass to fail.
         */
        const val DEFAULT_VISIBILITY_THRESHOLD = 0.5f
    }
}

/**
 * One inference result: the athlete's pose at a moment in time.
 *
 * ## Two coordinate spaces, and why both are kept
 *
 * MediaPipe returns two landmark lists per frame and they are not
 * interchangeable:
 *
 * - [image] is normalised to `0f..1f` across the frame, with the origin at the
 *   top-left and `y` increasing downward. It is **the only correct input for
 *   drawing the overlay**, because it maps directly onto the preview surface.
 *   It is a poor input for angles: it is a perspective projection, so the same
 *   elbow bend measures differently depending on how far the athlete stands from
 *   the lens and where in the frame they are.
 *
 * - [world] is in metres, with the origin at the midpoint of the hips and axes
 *   aligned to the body rather than the camera. Depth is real, not projected.
 *   It is **the only correct input for joint angles**, and it is what every
 *   analyzer in this package consumes. It is useless for drawing, because it has
 *   no relationship to screen position.
 *
 * Using [image] for angles is the classic mistake in this kind of app. It
 * produces numbers that look reasonable and vary with the athlete's distance
 * from the camera, which then shows up as unexplained score drift between
 * sessions. Keeping both lists on the frame and naming them for their purpose is
 * what makes that mistake hard to make.
 *
 * @param mirrored true when the source was the front camera, whose feed is shown
 *   mirrored so the athlete sees themselves as in a mirror. Affects rendering
 *   only. Anatomical left stays anatomical left in both lists.
 */
data class PoseFrame(
    val timestampMs: Long,
    val image: List<Landmark>,
    val world: List<Landmark>,
    val imageWidth: Int,
    val imageHeight: Int,
    val mirrored: Boolean,
) {
    init {
        require(image.size == EXPECTED_LANDMARK_COUNT) {
            "Expected $EXPECTED_LANDMARK_COUNT image landmarks, got ${image.size}"
        }
        require(world.size == EXPECTED_LANDMARK_COUNT) {
            "Expected $EXPECTED_LANDMARK_COUNT world landmarks, got ${world.size}"
        }
    }

    /** Image-space landmark for [point]. Use for drawing. */
    fun image(point: BodyPoint): Landmark = image[point.ordinal]

    /** Metric world-space landmark for [point]. Use for angles. */
    fun world(point: BodyPoint): Landmark = world[point.ordinal]

    /** Mean visibility across the points any skill needs. A rough tracking-health signal. */
    fun torsoAndLimbVisibility(): Float =
        BodyPoint.TorsoAndLimbs.map { image(it).visibility }.average().toFloat()

    companion object {
        const val EXPECTED_LANDMARK_COUNT = 33
    }
}
