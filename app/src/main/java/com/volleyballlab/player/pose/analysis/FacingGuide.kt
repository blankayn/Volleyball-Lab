package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.Skill

/**
 * Which way the athlete should point their body, in their own terms.
 *
 * "Left" and "right" are the athlete's left and right, starting from facing the
 * camera. That is the only frame of reference that survives the setup: the
 * front camera shows a mirrored preview and the back camera does not, so "face
 * the left of the screen" would be the wrong way round on one of them, and an
 * athlete standing three metres back cannot read the screen anyway.
 */
enum class Facing {
    /** Chest toward the lens. */
    Forward,

    /** A quarter turn to the athlete's left, which puts their right shoulder nearest the lens. */
    Left,

    /** A quarter turn to the athlete's right, which puts their left shoulder nearest the lens. */
    Right,

    /** Back toward the lens. */
    Away,
}

/**
 * The stance one view of one skill is performed in.
 *
 * @param nearSide the shoulder that ends up nearest the lens in a side view.
 * @param enforced whether [nearSide] is required, or only the suggested way to
 *   turn. It is required for the one-armed skills, because the hitting arm has
 *   to be the visible one. A two-handed skill reads the same from either
 *   profile, so turning the other way is accepted rather than argued with.
 */
data class Stance(
    val angle: CameraAngle,
    val facing: Facing,
    val nearSide: Side? = null,
    val enforced: Boolean = false,
) {
    /** The side the capture gate must see nearest the lens, or null for either. */
    val requiredNearSide: Side? get() = nearSide.takeIf { enforced }
}

/** Says, for the selected skill, which way to face: forward, left, right or away. */
object FacingGuide {

    /**
     * @param hittingSide the arm the athlete hits with, observed from their own
     *   reps or taken from their profile. Null when neither is known yet.
     */
    fun stance(skill: Skill, angle: CameraAngle, hittingSide: Side? = null): Stance = when (angle) {
        CameraAngle.Side -> {
            val oneArmed = skill == Skill.Serving || skill == Skill.Spiking
            // With nothing known, suggest the turn that suits a right-hander. It
            // is never enforced on a guess.
            val near = hittingSide ?: Side.Right
            Stance(angle, if (near == Side.Right) Facing.Left else Facing.Right, near,
                enforced = oneArmed && hittingSide != null)
        }
        CameraAngle.Rear -> Stance(angle, Facing.Away)
        CameraAngle.Front, CameraAngle.Unknown -> Stance(CameraAngle.Front, Facing.Forward)
    }

    /** The short label for the live screen. */
    fun label(facing: Facing): String = when (facing) {
        Facing.Forward -> "Face forward"
        Facing.Left -> "Face left"
        Facing.Right -> "Face right"
        Facing.Away -> "Face away"
    }

    /** The instruction that sends the athlete into [stance], spoken at a view change. */
    fun instruction(skill: Skill, stance: Stance): String = when (stance.facing) {
        Facing.Forward -> "Face forward, toward the camera, with your whole body in view."
        Facing.Away -> "Now turn your back to the camera. Keep your whole body in view."
        Facing.Left, Facing.Right -> when {
            // The hand is a guess, so say what decides the direction.
            handUnknown(skill, stance) ->
                "Turn side-on so your hitting arm is nearest the camera: " +
                    "to your left if you hit right-handed, to your right if you hit left-handed."
            stance.enforced -> "${turn(stance)} That keeps your hitting arm in view."
            else -> turn(stance)
        }
    }

    /**
     * What to say when the athlete is standing the wrong way for [stance] during
     * a guided view, given what the camera currently sees. Null when they are not.
     */
    fun correction(skill: Skill, stance: Stance, observed: OrientationEstimate): String? =
        correctionCue(skill, stance, observed)?.full

    /** [correction] with its short cue, for an athlete who has already heard it once. */
    fun correctionCue(skill: Skill, stance: Stance, observed: OrientationEstimate): SpokenCue? = when (stance.facing) {
        Facing.Forward -> when (observed.angle) {
            CameraAngle.Front -> null
            CameraAngle.Rear -> SpokenCue("Turn around and face forward, toward the camera.", "Turn around, face the camera.")
            CameraAngle.Side -> SpokenCue("Face forward again. Turn your chest toward the camera.", "Face the camera.")
            CameraAngle.Unknown -> SpokenCue("Square up and face forward, chest toward the camera.", "Face the camera.")
        }
        Facing.Away -> when (observed.angle) {
            CameraAngle.Rear -> null
            else -> SpokenCue("Turn your back to the camera again for this view.", "Back to the camera.")
        }
        Facing.Left, Facing.Right -> when {
            observed.angle != CameraAngle.Side ->
                if (handUnknown(skill, stance)) {
                    SpokenCue("Turn side-on again, with your hitting arm nearest the camera.", "Side-on, hitting arm to the camera.")
                } else SpokenCue("Turn side-on again. ${turn(stance)}", "Turn ${direction(stance)}.")
            // Only a shoulder that was seen on the wrong side is argued with.
            stance.requiredNearSide != null && observed.exposedSide != null &&
                observed.exposedSide != stance.requiredNearSide -> SpokenCue(
                "Turn around to face the other way, so your ${shoulder(stance)} shoulder points at the camera.",
                "Other way round.",
            )
            else -> null
        }
    }

    /**
     * Free practice asks for no particular view, but a body turned part-way
     * between two of them cannot be measured from any.
     */
    fun freePracticeCorrection(observed: OrientationEstimate): String? = freePracticeCue(observed)?.full

    fun freePracticeCue(observed: OrientationEstimate): SpokenCue? =
        if (observed.angle == CameraAngle.Unknown) {
            SpokenCue("Square up: face forward toward the camera, or turn fully side-on.", "Square up.")
        } else null

    /**
     * Which way the reference figure should face, given how the athlete is
     * actually standing.
     *
     * The figure faces the way the stance asks, so an athlete turned the wrong
     * way sees the right one. Where either side profile is accepted it follows
     * the one they chose, and in free practice ([stance] null) it follows
     * whatever view they are in: a figure facing the opposite way to an athlete
     * who is doing nothing wrong would read as a correction.
     */
    fun shown(stance: Stance?, observed: OrientationEstimate): Facing {
        val profile = when (observed.exposedSide.takeIf { observed.angle == CameraAngle.Side }) {
            Side.Right -> Facing.Left
            Side.Left -> Facing.Right
            null -> null
        }
        if (stance == null) return when (observed.angle) {
            CameraAngle.Side -> profile ?: Facing.Forward
            CameraAngle.Rear -> Facing.Away
            CameraAngle.Front, CameraAngle.Unknown -> Facing.Forward
        }
        val sideOn = stance.facing == Facing.Left || stance.facing == Facing.Right
        return if (sideOn && !stance.enforced && profile != null) profile else stance.facing
    }

    /** The arm nearest the lens when the figure is turned to [facing], or null when neither is. */
    fun nearSide(facing: Facing): Side? = when (facing) {
        Facing.Left -> Side.Right
        Facing.Right -> Side.Left
        Facing.Forward, Facing.Away -> null
    }

    /** One line for the opening brief: where to face for the first view. */
    fun opening(repsPerAngle: Int?): String =
        if (repsPerAngle == null) "Face forward toward the camera, or stand fully side-on."
        else "Face forward for the first ${CoachPhrasebook.numberWord(repsPerAngle).lowercase()} reps, then I'll turn you side-on."

    private fun handUnknown(skill: Skill, stance: Stance): Boolean =
        (skill == Skill.Serving || skill == Skill.Spiking) && !stance.enforced

    private fun direction(stance: Stance) = if (stance.facing == Facing.Left) "left" else "right"

    private fun turn(stance: Stance): String =
        "Turn to your ${direction(stance)}, so your ${shoulder(stance)} shoulder points at the camera."

    private fun shoulder(stance: Stance): String =
        (stance.nearSide ?: if (stance.facing == Facing.Left) Side.Right else Side.Left).name.lowercase()
}
