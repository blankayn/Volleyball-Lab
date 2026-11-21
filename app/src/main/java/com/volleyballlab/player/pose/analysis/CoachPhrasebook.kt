package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.pose.model.BodyPoint

/** Local instruction content. None of these setup lines implies a measured result. */
object CoachPhrasebook {
    private val numberWords = listOf(
        "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
        "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen",
        "Nineteen", "Twenty", "Twenty-one", "Twenty-two", "Twenty-three", "Twenty-four", "Twenty-five",
        "Twenty-six", "Twenty-seven", "Twenty-eight", "Twenty-nine", "Thirty",
    )

    /** Spoken number, capitalised. Digits above thirty, where a word stops being quicker to hear. */
    fun numberWord(n: Int): String = numberWords.getOrNull(n - 1) ?: n.toString()

    /** The short spoken count. The card keeps its own "Rep N of T" text. */
    fun repCount(stageRep: Int, target: Int?, totalReps: Int): String = when {
        target == null -> "${numberWord(totalReps)}."
        stageRep == 1 -> "One."
        target % 2 == 0 && stageRep == target / 2 -> "Halfway."
        stageRep == target - 1 -> "One to go."
        else -> "${numberWord(stageRep)}."
    }

    /**
     * The reaction after a count when the director has nothing to say.
     * Deterministic rotation: [index] picks the variant and [previous] is skipped.
     */
    fun repAck(
        verdict: LiveRepVerdict,
        faultId: CueId? = null,
        index: Int = 0,
        previous: String? = null,
        firstOfStage: Boolean = false,
        stayWithFocus: Boolean = false,
    ): String {
        val variants = when (verdict) {
            LiveRepVerdict.Good -> listOf("Clean.", "That's the shape.", "Good, same again.",
                "That one was in range.", "Yes. Hold that.")
            LiveRepVerdict.Adjust -> {
                if (firstOfStage && faultId != null) return "Your ${focusName(faultId)} needs work."
                listOf("Not quite.", "That one was off.", "Reset and go again.") +
                    if (stayWithFocus && faultId != null) listOf("Stay with your ${focusName(faultId)}.") else emptyList()
            }
            LiveRepVerdict.Partial -> listOf("Only part of that one was in view.",
                "Counted. Only part of your form was visible.")
            LiveRepVerdict.Unrated -> listOf("Counted. I couldn't judge that one from here.",
                "I couldn't judge that one. Keep going.")
        }
        val start = Math.floorMod(index, variants.size)
        return variants.indices.map { variants[(start + it) % variants.size] }.first { it != previous || variants.size == 1 }
    }

    fun repTurn(count: String, reaction: String?): String =
        if (reaction.isNullOrBlank()) count else "$count $reaction"

    private val openings = mapOf(
        Skill.Receiving to listOf("Bend your knees slightly and bring your forearms together.",
            "Soft knees, forearms together, shoulders level.",
            "Set a steady base and join your forearms."),
        Skill.Setting to listOf("Bring your hands above your forehead with elbows bent.",
            "Hands up above your forehead, elbows soft.",
            "Shape your hands above your forehead first."),
        Skill.Serving to listOf("Balance your feet and raise your guiding arm steadily.",
            "Steady feet, then lift your guiding arm.",
            "Find your balance before you lift your arm."),
        Skill.Spiking to listOf("Start balanced, bend your knees, and prepare your raised elbow.",
            "Balanced start, knees bent, hitting elbow high.",
            "Load your knees and lift your hitting elbow."),
        Skill.Blocking to listOf("Set your feet and bring both hands up ready.",
            "Feet set, hands up in front of you.",
            "Start low and ready with both hands up."),
    )

    fun opening(skill: Skill, variant: Int = 0): String =
        openings.getValue(skill).let { it[Math.floorMod(variant, it.size)] }

    fun action(id: CueId, measured: Float): String {
        val cue = requireNotNull(FormCueCatalog.byId(id))
        val below = measured < cue.target.start
        return when (id) {
            CueId.PassKneeFlexion, CueId.SetKneeFlexion, CueId.SpikeApproachLoad, CueId.BlockKneeLoad ->
                if (below) "Rise slightly from your crouch" else "Bend your knees a little more"
            CueId.SetElbowFlexion -> if (below) "Open your elbows a little" else "Bend your elbows a little more"
            CueId.BlockHandSeparation -> if (below) "Move your hands slightly apart" else "Bring your hands slightly closer"
            CueId.PassTrunkAngle, CueId.ServeTrunkLean, CueId.SpikeTrunkDrive ->
                if (below) "Lean your trunk slightly forward" else "Bring your trunk slightly more upright"
            CueId.ServeTossArmLift -> if (below) "Lift your guiding arm a little higher" else "Lower your guiding arm slightly"
            CueId.SetHandsAboveForehead -> if (below) "Bring your hands above your forehead" else "Bring your hands closer above your forehead"
            CueId.BlockPenetration -> if (below) "Reach your hands a little farther forward" else "Bring your hands slightly back toward you"
            CueId.PassShouldersLevel -> "Keep your shoulders level above your forearm platform"
            CueId.PassPlatformStraight -> "Straighten both elbows and join your forearms"
            CueId.ServeReachHeight -> "Lift your hitting elbow and reach upward"
            CueId.SpikeElbowHeight -> "Keep your hitting elbow at shoulder height or higher"
            CueId.ServeContactArmExtension, CueId.SpikeArmExtension -> "Extend your raised arm above your shoulder"
            CueId.SetExtensionFollowThrough -> "Extend your arms through the finish"
            CueId.BlockArmExtension -> "Reach upward with both arms straighter"
            CueId.SpikeJumpHeight -> "Practice a balanced knee bend and overhead reach"
        }
    }

    fun focusName(id: CueId): String = when (id) {
        CueId.PassKneeFlexion, CueId.SetKneeFlexion, CueId.SpikeApproachLoad, CueId.BlockKneeLoad -> "knee bend"
        CueId.PassPlatformStraight -> "forearm platform"
        CueId.PassShouldersLevel -> "shoulder position"
        CueId.PassTrunkAngle, CueId.ServeTrunkLean, CueId.SpikeTrunkDrive -> "trunk position"
        CueId.BlockHandSeparation -> "hand spacing"
        CueId.SetHandsAboveForehead -> "hand height"
        CueId.ServeTossArmLift -> "guiding arm"
        CueId.SetElbowFlexion -> "elbow bend"
        CueId.SetExtensionFollowThrough -> "arm finish"
        CueId.ServeReachHeight -> "overhead reach"
        CueId.SpikeElbowHeight -> "elbow height"
        CueId.BlockPenetration -> "forward reach"
        CueId.ServeContactArmExtension, CueId.SpikeArmExtension, CueId.BlockArmExtension -> "arm extension"
        CueId.SpikeJumpHeight -> "knee bend and reach"
    }

    /** What was seen, then what to do. Direction only, never a number. */
    private fun observation(id: CueId, below: Boolean, side: Side?): String {
        val arm = side?.let { "${it.name.lowercase()} " }.orEmpty()
        return when (id) {
            CueId.PassKneeFlexion, CueId.SetKneeFlexion, CueId.SpikeApproachLoad, CueId.BlockKneeLoad ->
                if (below) "You're sitting a bit deep. Rise slightly."
                else "Your knees are staying a bit straight. Bend a little more."
            CueId.PassPlatformStraight -> "Your elbows are bending in your platform. Straighten them and join your forearms."
            CueId.PassShouldersLevel -> "Your shoulders are tilting. Keep them level above your forearms."
            CueId.PassTrunkAngle, CueId.ServeTrunkLean, CueId.SpikeTrunkDrive ->
                if (below) "You're standing a bit tall through your trunk. Lean slightly forward."
                else "You're leaning a bit far forward. Come slightly more upright."
            CueId.SetElbowFlexion ->
                if (below) "Your elbows are folding in tight. Open them a little."
                else "Your elbows are staying a bit straight. Bend them a little more."
            CueId.SetHandsAboveForehead ->
                if (below) "Your hands are sitting a bit low. Bring them above your forehead."
                else "Your hands are a bit far from your forehead. Bring them closer in."
            CueId.BlockHandSeparation ->
                if (below) "Your hands are a bit close together. Move them slightly apart."
                else "Your hands are drifting wide. Bring them slightly closer."
            CueId.ServeTossArmLift ->
                if (below) "Your ${arm}guiding arm is staying a bit low. Lift it a little higher."
                else "Your ${arm}guiding arm is going a bit high. Lower it slightly."
            CueId.BlockPenetration ->
                if (below) "Your hands are staying back a bit. Reach a little farther forward."
                else "Your hands are reaching a bit far forward. Bring them slightly back."
            CueId.ServeReachHeight -> "Your ${arm}hitting elbow is staying a bit low. Lift it and reach upward."
            CueId.SpikeElbowHeight -> "Your ${arm}hitting elbow is dropping as you draw back. Keep it up at shoulder height."
            CueId.ServeContactArmExtension, CueId.SpikeArmExtension ->
                "Your ${arm}arm is staying a bit bent overhead. Extend it above your shoulder."
            CueId.SetExtensionFollowThrough -> "Your arms are stopping short at the finish. Extend them through."
            CueId.BlockArmExtension -> "Your arms are staying a bit bent. Reach upward with both arms straighter."
            CueId.SpikeJumpHeight -> "Work on your knee bend and reach. Dip smoothly, then reach overhead."
        }
    }

    /** The fewest words that still name the action, for the struggle rung. */
    private fun shortAction(id: CueId, below: Boolean): String = when (id) {
        CueId.PassKneeFlexion, CueId.SetKneeFlexion, CueId.SpikeApproachLoad, CueId.BlockKneeLoad ->
            if (below) "stand a little taller" else "sit a little lower"
        CueId.PassPlatformStraight -> "long, straight arms"
        CueId.PassShouldersLevel -> "level shoulders"
        CueId.PassTrunkAngle, CueId.ServeTrunkLean, CueId.SpikeTrunkDrive ->
            if (below) "chest slightly forward" else "chest slightly up"
        CueId.SetElbowFlexion -> if (below) "elbows a little wider" else "elbows a little softer"
        CueId.SetHandsAboveForehead -> if (below) "hands above your forehead" else "hands closer in"
        CueId.BlockHandSeparation -> if (below) "hands a little wider" else "hands a little closer"
        CueId.ServeTossArmLift -> if (below) "guiding arm higher" else "guiding arm lower"
        CueId.BlockPenetration -> if (below) "hands forward" else "hands back"
        CueId.ServeReachHeight -> "elbow up, then reach"
        CueId.SpikeElbowHeight -> "elbow up, then swing"
        CueId.ServeContactArmExtension, CueId.SpikeArmExtension -> "long arm overhead"
        CueId.SetExtensionFollowThrough -> "finish long"
        CueId.BlockArmExtension -> "long arms up"
        CueId.SpikeJumpHeight -> "dip, then reach"
    }

    /** One image per cue family, in the direction of the fix. */
    private fun feel(id: CueId, below: Boolean): String = when (id) {
        CueId.PassKneeFlexion, CueId.SetKneeFlexion, CueId.SpikeApproachLoad, CueId.BlockKneeLoad ->
            if (below) "Feel light on your feet, like you could hop straight up."
            else "Feel like you're sitting back onto a tall stool."
        CueId.PassPlatformStraight -> "Feel your arms lock into one long, flat board."
        CueId.PassShouldersLevel -> "Feel both shoulders resting on the same shelf."
        CueId.PassTrunkAngle, CueId.ServeTrunkLean, CueId.SpikeTrunkDrive ->
            if (below) "Feel your chest come forward over your toes."
            else "Feel a string lifting the top of your head."
        CueId.SetElbowFlexion ->
            if (below) "Feel a wide window open between your arms."
            else "Feel your elbows soften, like holding a big bowl overhead."
        CueId.SetHandsAboveForehead ->
            if (below) "Feel like you're looking up through a window made by your hands."
            else "Feel your thumbs hover just over your eyebrows."
        CueId.BlockHandSeparation ->
            if (below) "Feel your hands frame a space just wider than your head."
            else "Feel your thumbs drift in toward each other."
        CueId.ServeTossArmLift ->
            if (below) "Feel your guiding hand reach up toward the ceiling."
            else "Feel your guiding arm stay relaxed and a touch lower."
        CueId.BlockPenetration ->
            if (below) "Feel like you're reaching over a fence in front of you."
            else "Feel your arms stack up over your shoulders."
        CueId.ServeReachHeight -> "Feel your elbow climb past your ear before you reach."
        CueId.SpikeElbowHeight -> "Feel your elbow pulled back high, like drawing a bow."
        CueId.ServeContactArmExtension, CueId.SpikeArmExtension -> "Feel long from your hip to your fingertips."
        CueId.SetExtensionFollowThrough -> "Feel your arms finish long, like pushing the ceiling away."
        CueId.BlockArmExtension -> "Feel both arms grow long, pressing up to the ceiling."
        CueId.SpikeJumpHeight -> "Feel a smooth dip, then a long reach."
    }

    /**
     * The correction ladder for one focus: what was seen and the direct cue, a
     * slower rehearsal, a reset sequence, a struggle line, then a feel cue.
     * A new athlete starts at the slow rehearsal.
     */
    fun instructions(
        id: CueId,
        measured: Float,
        side: Side? = null,
        experience: ExperienceLevel? = null,
    ): List<String> {
        val below = measured < requireNotNull(FormCueCatalog.byId(id)).target.start
        val (rehearse, reset) = when (id) {
            CueId.PassKneeFlexion, CueId.SetKneeFlexion, CueId.SpikeApproachLoad, CueId.BlockKneeLoad ->
                if (below) {
                    "Start from your crouch and slowly rise a little." to
                        "Reset your stance, then use a shallower knee bend."
                } else {
                    "From your ready stance, slowly bend your knees a little more." to
                        "Reset your stance, then use a slightly deeper knee bend."
                }
            CueId.PassPlatformStraight ->
                "Slowly extend both elbows while keeping your forearms together." to
                    "Reset your arms, join your forearms, then straighten your elbows."
            CueId.PassShouldersLevel ->
                "Set your forearm platform, then bring your shoulders level." to
                    "Reset your stance and level your shoulders before moving."
            CueId.PassTrunkAngle, CueId.ServeTrunkLean, CueId.SpikeTrunkDrive ->
                if (below) {
                    "Slowly hinge forward a little from your hips." to
                        "Reset your stance, then add a small forward lean."
                } else {
                    "Slowly bring your trunk back toward an upright position." to
                        "Reset your stance, then use a little less forward lean."
                }
            CueId.SetElbowFlexion -> if (below) {
                "Keep your hands raised and slowly open your elbows a little." to
                    "Reset your arms, then rehearse with a little less elbow bend."
            } else {
                "Keep your hands raised and slowly bend your elbows a little more." to
                    "Reset your arms, then rehearse with a little more elbow bend."
            }
            CueId.SetHandsAboveForehead -> if (below) {
                "Slowly lift your hands until they are above your forehead." to
                    "Reset your hands, then raise them above your forehead before extending."
            } else {
                "Slowly lower your hands a little toward your forehead." to
                    "Reset your hands just above your forehead before extending."
            }
            CueId.BlockHandSeparation -> if (below) {
                "Keep your arms raised and slowly widen the gap between your hands." to
                    "Reset your arms, then reach up with your hands a little farther apart."
            } else {
                "Keep your arms raised and slowly narrow the gap between your hands." to
                    "Reset your arms, then reach up with your hands a little closer together."
            }
            CueId.ServeTossArmLift -> if (below) {
                "Slowly raise your guiding arm a little farther before your swing." to
                    "Reset your stance, lift your guiding arm higher, then rehearse the swing."
            } else {
                "Slowly lower your guiding arm a little before your swing." to
                    "Reset your stance, keep your guiding arm lower, then rehearse the swing."
            }
            CueId.BlockPenetration -> if (below) {
                "With your arms raised, slowly reach your hands a little farther forward." to
                    "Reset your arms, then reach up and a little farther in front."
            } else {
                "With your arms raised, slowly bring your hands a little back toward you." to
                    "Reset your arms, then reach up with less forward reach."
            }
            CueId.ServeReachHeight ->
                "Slowly lift your raised elbow, then reach upward from your shoulder." to
                    "Reset your arm, raise your elbow, then rehearse the overhead reach."
            CueId.SpikeElbowHeight ->
                "Slowly draw your hitting arm back, keeping the elbow level with your shoulder or above." to
                    "Reset your arm, lift your elbow high and back, then swing."
            CueId.ServeContactArmExtension, CueId.SpikeArmExtension ->
                "Slowly straighten your raised elbow as you reach above your shoulder." to
                    "Reset your arm, lift your elbow, then extend through the overhead reach."
            CueId.SetExtensionFollowThrough ->
                "Start with bent elbows, then slowly extend both arms through the finish." to
                    "Reset your hands above your forehead, then rehearse the full arm finish."
            CueId.BlockArmExtension ->
                "Slowly extend both elbows as you reach upward." to
                    "Reset your arms, raise both hands, then straighten both elbows."
            CueId.SpikeJumpHeight ->
                "Slowly bend your knees, then rise into an overhead reach." to
                    "Reset your stance and rehearse the knee bend before reaching upward."
        }
        val seen = observation(id, below, side)
        val struggle = "This one takes a few tries. Just think: ${shortAction(id, below)}."
        val feel = feel(id, below)
        return if (experience == ExperienceLevel.New) listOf(rehearse, seen, reset, struggle, feel)
            else listOf(seen, rehearse, reset, struggle, feel)
    }

    fun improvement(id: CueId, inRange: Boolean): List<String> {
        val name = focusName(id)
        return if (inRange) listOf(
            "Your $name matched the reference on both attempts.",
            "There it is. Your $name was in range on both of those.",
            "Your $name stayed in the reference range. Keep that shape.",
            "That's your $name in range twice running. Hold onto it.",
            "Yes. Your $name was in the reference range both times.",
        ) else listOf(
            "Your $name is closer to the reference. Keep working on that.",
            "Your $name moved closer to the reference on both attempts.",
            "Closer. Your $name is heading the right way.",
            "That's closer on your $name. Keep going the same way.",
            "Your $name is getting closer. Stay with it.",
        )
    }

    fun unavailable(id: CueId?, angle: CameraAngle? = null): List<String> {
        val subject = id?.let { "Your ${focusName(it)} isn't clear enough to assess" }
            ?: "Some form cues aren't clear enough to assess"
        val points = id?.let { AngleCuePolicy.affectedPoints(it) }.orEmpty()
        val legs = setOf(BodyPoint.LEFT_KNEE, BodyPoint.RIGHT_KNEE, BodyPoint.LEFT_ANKLE, BodyPoint.RIGHT_ANKLE)
        val overhead = setOf(CueId.ServeContactArmExtension, CueId.ServeReachHeight, CueId.ServeTossArmLift,
            CueId.SpikeArmExtension, CueId.SetHandsAboveForehead, CueId.SetExtensionFollowThrough,
            CueId.BlockArmExtension, CueId.BlockHandSeparation, CueId.BlockPenetration)
        val fix = when {
            angle == CameraAngle.Side -> "Part of you is hidden from this side. I'll wait for a clearer view."
            points.any { it in legs } -> "I'm losing your legs. A step back should fix it."
            id in overhead -> "Your hands are leaving the top of the frame. A step back should fix it."
            else -> "Reps are recording. I'll wait for a clearer view before giving form advice."
        }
        return listOf("$subject. Reps are still recording.", fix)
    }

    // Session-arc turns. Each is tied to counted or measured evidence by CoachDirector.

    fun streak(count: Int, strongCue: CueId?): String {
        val run = "${numberWord(count)} in a row"
        return if (strongCue != null) "$run. Your ${focusName(strongCue)} is holding." else "$run in range."
    }

    fun regroup(cue: CueId?): String =
        if (cue != null) "Your ${focusName(cue)} was in range earlier and it's slipping. Take a breath, reset, then go."
        else "Your first few were stronger than these. Take a breath, reset, then go."

    const val BUDGET_SPENT = "Let that one settle. Just keep them coming."
    const val CARRIED_FOCUS_PREFIX = "Same thing from this side: "
    const val TRACKING_RESTORED = "Got you again, carry on."
    const val GO = "Go when you're ready."
    const val SAME_AGAIN = "Same again from this side."
    const val HOLD_STILL = "That's it, hold still."

    /** Prototype [PersonalElbowBaseline] cue: compared with the athlete's own best reps, never a fixed height. */
    const val ELBOW_BELOW_BEST = "Your elbow came through lower than on your best reps. Lift it high like before."

    /**
     * Said when a movement finished and was not the skill. It leads with the
     * fact that nothing was counted, then gives the one change that would have
     * made it a rep. No count, score or praise is attached to it.
     */
    fun notCounted(fault: MovementFault): String = NOT_COUNTED_PREFIX + when (fault) {
        MovementFault.ReachOverhead -> "Swing your hitting hand up above your head."
        MovementFault.UseBothHands -> "Bring both hands up together."
        MovementFault.HandsAboveForehead -> "Bring both hands up above your forehead."
        MovementFault.BendElbowsFirst -> "Bend your elbows and shape your hands above your forehead."
        MovementFault.ReachBothHandsHigh -> "Reach both hands up above your head."
        MovementFault.StraightenArms -> "Straighten your arms at the top of the block."
        MovementFault.JoinPlatform -> "Join your forearms out in front of you as you bend your knees."
        MovementFault.PlatformTooHigh -> "Keep your joined forearms below your shoulders."
        MovementFault.ArmsNotVisible -> "I lost sight of your arms. Keep your hands inside the picture."
        MovementFault.LegsNotVisible -> "I lost sight of your legs. Step back so your feet are in view."
    }

    /** [notCounted] once it has been heard: the same fix, in as few words as still name it. */
    fun notCountedShort(fault: MovementFault): String = NOT_COUNTED_PREFIX + when (fault) {
        MovementFault.ReachOverhead -> "Hand above your head."
        MovementFault.UseBothHands -> "Both hands up."
        MovementFault.HandsAboveForehead -> "Hands above your forehead."
        MovementFault.BendElbowsFirst -> "Bend your elbows."
        MovementFault.ReachBothHandsHigh -> "Both hands higher."
        MovementFault.StraightenArms -> "Straighter arms."
        MovementFault.JoinPlatform -> "Forearms together."
        MovementFault.PlatformTooHigh -> "Forearms lower."
        MovementFault.ArmsNotVisible -> "Keep your arms in view."
        MovementFault.LegsNotVisible -> "Step back."
    }

    fun notCountedCue(fault: MovementFault) = SpokenCue(notCounted(fault), notCountedShort(fault))

    const val NOT_COUNTED_PREFIX = "Not counted. "

    /**
     * For an athlete who is in view and ready but has produced no rep for a
     * while: what a rep of this skill is, rather than a vague invitation.
     */
    fun idle(skill: Skill): String = when (skill) {
        Skill.Serving -> "I'm waiting for a serve. Swing your hitting hand up above your head."
        Skill.Receiving -> "I'm waiting for a pass. Join your forearms in front and bend your knees."
        Skill.Setting -> "I'm waiting for a set. Bring both hands up above your forehead with your elbows bent."
        Skill.Spiking -> "I'm waiting for a spike. Load your knees, then swing your hitting hand up overhead."
        Skill.Blocking -> "I'm waiting for a block. Reach both hands up above your head."
    }

    /** [idle] once it has been heard: a reminder of the rep, not a lesson. */
    fun idleShort(skill: Skill): String = "Ready when you are: " + when (skill) {
        Skill.Serving -> "hand above your head."
        Skill.Receiving -> "forearms together, knees bent."
        Skill.Setting -> "hands above your forehead."
        Skill.Spiking -> "load, then reach high."
        Skill.Blocking -> "both hands up high."
    }

    fun idleCue(skill: Skill) = SpokenCue(idle(skill), idleShort(skill))

    fun watching(planFocus: CueId?, stageIndex: Int): String =
        if (planFocus != null && stageIndex % 2 == 0) "I'm watching your ${focusName(planFocus)}."
        else "Got it, keep them coming."
}
