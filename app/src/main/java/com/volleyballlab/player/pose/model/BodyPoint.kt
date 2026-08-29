package com.volleyballlab.player.pose.model

/**
 * The 33 body landmarks produced by BlazePose, which is the topology MediaPipe's
 * Pose Landmarker emits.
 *
 * The ordinal of each entry **is** the index MediaPipe returns, so
 * `BodyPoint.LEFT_ELBOW.ordinal == 13`. Do not reorder or insert entries; a
 * reorder silently remaps every joint in the app and produces plausible-looking
 * nonsense rather than a crash.
 *
 * ## Left and right are from the athlete's point of view
 *
 * [LEFT_WRIST] is the athlete's left wrist. On a front-facing camera it appears
 * on the right of the screen. Mirroring is a rendering concern and is handled by
 * [PoseFrame.mirrored] at draw time - never by swapping these names, which would
 * make a right-handed serve analyse as a left-handed one.
 */
enum class BodyPoint {
    NOSE,
    LEFT_EYE_INNER,
    LEFT_EYE,
    LEFT_EYE_OUTER,
    RIGHT_EYE_INNER,
    RIGHT_EYE,
    RIGHT_EYE_OUTER,
    LEFT_EAR,
    RIGHT_EAR,
    MOUTH_LEFT,
    MOUTH_RIGHT,
    LEFT_SHOULDER,
    RIGHT_SHOULDER,
    LEFT_ELBOW,
    RIGHT_ELBOW,
    LEFT_WRIST,
    RIGHT_WRIST,
    LEFT_PINKY,
    RIGHT_PINKY,
    LEFT_INDEX,
    RIGHT_INDEX,
    LEFT_THUMB,
    RIGHT_THUMB,
    LEFT_HIP,
    RIGHT_HIP,
    LEFT_KNEE,
    RIGHT_KNEE,
    LEFT_ANKLE,
    RIGHT_ANKLE,
    LEFT_HEEL,
    RIGHT_HEEL,
    LEFT_FOOT_INDEX,
    RIGHT_FOOT_INDEX;

    companion object {
        /** Cached because `values()` allocates a fresh array on every call. */
        val All: List<BodyPoint> = entries

        /**
         * The points that must be tracked for any volleyball skill to be scored.
         *
         * Face points are excluded on purpose. None of the five MVP skills depend
         * on head landmarks, and requiring them would reject perfectly usable
         * captures of an athlete who happens to be looking away from the camera.
         */
        val TorsoAndLimbs: List<BodyPoint> = listOf(
            LEFT_SHOULDER, RIGHT_SHOULDER,
            LEFT_ELBOW, RIGHT_ELBOW,
            LEFT_WRIST, RIGHT_WRIST,
            LEFT_HIP, RIGHT_HIP,
            LEFT_KNEE, RIGHT_KNEE,
            LEFT_ANKLE, RIGHT_ANKLE,
        )
    }
}

/**
 * Bone segments used to draw the skeleton overlay.
 *
 * This is a rendering concern only - the analysis code works from joint angles,
 * not from this list. Face connections are omitted because at overlay stroke
 * width they collapse into an unreadable blob over the athlete's face.
 */
val SkeletonBones: List<Pair<BodyPoint, BodyPoint>> = listOf(
    // Shoulder girdle and torso
    BodyPoint.LEFT_SHOULDER to BodyPoint.RIGHT_SHOULDER,
    BodyPoint.LEFT_SHOULDER to BodyPoint.LEFT_HIP,
    BodyPoint.RIGHT_SHOULDER to BodyPoint.RIGHT_HIP,
    BodyPoint.LEFT_HIP to BodyPoint.RIGHT_HIP,

    // Arms
    BodyPoint.LEFT_SHOULDER to BodyPoint.LEFT_ELBOW,
    BodyPoint.LEFT_ELBOW to BodyPoint.LEFT_WRIST,
    BodyPoint.RIGHT_SHOULDER to BodyPoint.RIGHT_ELBOW,
    BodyPoint.RIGHT_ELBOW to BodyPoint.RIGHT_WRIST,

    // Hands, enough to read platform and hand shape
    BodyPoint.LEFT_WRIST to BodyPoint.LEFT_INDEX,
    BodyPoint.RIGHT_WRIST to BodyPoint.RIGHT_INDEX,

    // Legs
    BodyPoint.LEFT_HIP to BodyPoint.LEFT_KNEE,
    BodyPoint.LEFT_KNEE to BodyPoint.LEFT_ANKLE,
    BodyPoint.RIGHT_HIP to BodyPoint.RIGHT_KNEE,
    BodyPoint.RIGHT_KNEE to BodyPoint.RIGHT_ANKLE,

    // Feet, which matter for approach and landing
    BodyPoint.LEFT_ANKLE to BodyPoint.LEFT_FOOT_INDEX,
    BodyPoint.RIGHT_ANKLE to BodyPoint.RIGHT_FOOT_INDEX,
)
