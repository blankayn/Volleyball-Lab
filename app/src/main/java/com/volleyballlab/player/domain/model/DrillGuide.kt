package com.volleyballlab.player.domain.model

data class DrillGuide(
    val equipment: String,
    val preparation: String,
    val steps: List<TechniqueStep>,
    val mistakes: List<String>,
    val defaultSets: Int = 3,
    val defaultRestSeconds: Int = 30,
)

/** In-place setup and sequences complement the existing skill technique guides. */
object DrillGuideCatalog {
    private data class Setup(val equipment: String, val preparation: String, val actions: List<String>)
    private val setups = mapOf(
        "serve-standing-float" to Setup(
            "Volleyball, fixed target, partner or spare balls within reach",
            "Choose a fixed serving stance. Keep spare balls within reach or have a partner return them.",
            listOf(
                "Set balanced feet and hold the ball in the non-hitting hand.",
                "Make a repeatable toss in front of the hitting shoulder while keeping both feet grounded.",
                "Reach up and contact with a firm, flat hand from the same stance.",
                "Finish the swing, regain balance, and accept a new ball before the next serve.",
            ),
        ),
        "serve-jump-float" to Setup(
            "Volleyball, fixed target, clear space for a vertical jump, partner or spare balls",
            "Mark one takeoff and landing spot. Keep the toss within reach above that position.",
            listOf(
                "Begin with balanced feet at the marked spot and prepare the hitting arm.",
                "Make a controlled toss, bend the knees, and jump vertically from that spot.",
                "Contact high with a firm hand and a flat float-serve contact.",
                "Land at the starting spot with softened knees, then reset with a new ball.",
            ),
        ),
        "serve-topspin-jump" to Setup(
            "Volleyball, fixed target, clear space for a vertical jump, partner or spare balls",
            "Use one takeoff and landing spot. Keep the toss above and slightly in front of the hitting shoulder.",
            listOf(
                "Start from a balanced fixed stance and make a controlled toss.",
                "Bend the knees and take off vertically while drawing the hitting arm back.",
                "Reach through the ball and follow over its top for the topspin action.",
                "Finish the swing, land in the same spot, and reset with a new ball.",
            ),
        ),
        "receive-wall-platform" to Setup(
            "Volleyball, unobstructed wall with a fixed target",
            "Choose a comfortable fixed stance and gentle feeds that rebound within reach. Pause if a rebound leaves your reach.",
            listOf(
                "Start in a balanced ready position facing the wall, with your feet in place.",
                "Join the hands and present a stable forearm platform for the rebound.",
                "Pass toward the wall target with a small, controlled leg extension.",
                "Reset upright between contacts. Pause for a new ball when needed.",
            ),
        ),
        "receive-short-court" to Setup(
            "Volleyball, feeding partner, fixed target",
            "Ask your partner to deliver easy feeds into your ready position and return balls between repetitions.",
            listOf(
                "Set a balanced stance facing the target and agree on an easy feeding pace.",
                "Keep your feet in place and join the forearms for a feed within reach.",
                "Angle a still platform toward the target and pass with controlled leg extension.",
                "Reset in the same stance before the next feed. Pause any feed outside your reach.",
            ),
        ),
        "receive-serve-to-target" to Setup(
            "Volleyball, partner serving controlled balls, fixed passing target",
            "Mark a fixed receiving position. Ask the server to aim gentle serves within your reach and reset wide feeds.",
            listOf(
                "Watch the server from a balanced ready stance with your feet in place.",
                "Form the forearm platform as the controlled serve enters your reach.",
                "Direct the platform toward the target without a large arm swing.",
                "Reset in place and let your partner return the ball. Count the attempt manually, regardless of target accuracy.",
            ),
        ),
        "set-wall-reps" to Setup(
            "Volleyball, unobstructed wall with a target above head height",
            "Choose a fixed stance and gentle wall feeds that return above your forehead. Pause if a rebound leaves your reach.",
            listOf(
                "Face the wall target with your feet in place and bring the hands above the forehead.",
                "Receive the returning ball with balanced hands and softened knees.",
                "Extend legs and arms together to set toward the wall target while keeping your feet grounded.",
                "Reset hand position and balance in the same spot before the next repetition.",
            ),
        ),
        "set-toss-to-target" to Setup(
            "Volleyball, controlled self-toss or feeding partner, fixed target",
            "Choose a balanced fixed stance. Place each toss above your forehead, within reach of that position.",
            listOf(
                "Keep your feet grounded and use a controlled self-toss or partner feed above the forehead.",
                "Face the target with balanced hands ready and the knees softened.",
                "Extend the legs and arms together to set toward the target from the same stance.",
                "Finish extended and reset in place. Pause a feed outside your reach and accept a new ball.",
            ),
        ),
        "set-back-set" to Setup(
            "Volleyball, feeding partner, fixed rear target",
            "Agree on a rear target and easy feeds above your forehead. Keep the same stance throughout the set.",
            listOf(
                "Receive a controlled feed above the forehead with your feet grounded in a balanced stance.",
                "Keep the hands balanced and soften the knees and elbows to cushion the ball.",
                "Use coordinated extension to deliver the set toward the rear target.",
                "Reset upright in the same spot while your partner returns the ball for the next feed.",
            ),
        ),
        "spike-standing-swing" to Setup(
            "Volleyball, controlled self-toss or feeding partner, fixed target, clear hitting area",
            "Keep both feet grounded. Use a feed in front of the hitting shoulder and keep new balls within reach.",
            listOf(
                "Face the hitting area in a balanced, fixed standing position.",
                "Draw the elbow back and prepare a controlled feed in front of the hitting shoulder.",
                "Reach high and swing through the ball while keeping both feet grounded.",
                "Follow through, reset the arm in place, and accept a new ball before the next attempt.",
            ),
        ),
        "spike-three-step" to Setup(
            "Volleyball, feeding partner, fixed target, clear space for a vertical jump",
            "Mark one takeoff and landing spot. Ask your partner to feed above and in front of the hitting shoulder.",
            listOf(
                "Begin at the marked spot and time your preparation to a controlled feed.",
                "Bend the knees and hips, then jump vertically from that spot.",
                "Reach high and swing through the ball in front of the hitting shoulder.",
                "Land in the same spot with softened knees and reset while your partner returns the ball.",
            ),
        ),
        "spike-four-step-lines" to Setup(
            "Volleyball, feeding partner, line and cross-court targets, clear space for a vertical jump",
            "Identify both targets and mark one takeoff and landing spot. Use controlled feeds within reach of that position.",
            listOf(
                "Start from the fixed stance and choose the line or cross-court target for the attempt.",
                "Load the knees and hips, then jump vertically as the controlled feed arrives.",
                "Direct a controlled swing toward the selected target, alternating directions between attempts.",
                "Land in the same spot and reset while your partner returns the ball. Target accuracy is manually observed.",
            ),
        ),
        "block-ready-jumps" to Setup(
            "Net or marked blocking position, clear space for a vertical jump",
            "Choose one fixed takeoff and landing spot facing the blocking area.",
            listOf(
                "Align the feet in a balanced stance and keep both hands ready.",
                "Bend the knees and jump vertically from the same spot.",
                "Reach with both arms and present the hands slightly forward over the blocking space.",
                "Land in the starting spot with softened knees and reset before the next jump.",
            ),
        ),
        "block-lateral-shuffle" to Setup(
            "Net or marked blocking position, clear space for a vertical jump",
            "Use one fixed stance. Focus on square shoulders and balanced hand alignment throughout the block.",
            listOf(
                "Start square to the blocking area with balanced feet and both hands ready.",
                "Bend the knees while keeping the shoulders square, then jump vertically from the same spot.",
                "Extend both arms with aligned hands to cover the fixed blocking area.",
                "Land in the same spot, regain a square stance, and reset your hands before repeating.",
            ),
        ),
        "block-read-setter" to Setup(
            "Feeding partner, volleyball, net or marked blocking position, clear space for a vertical jump",
            "Choose a fixed blocking position. Ask your partner to direct controlled feeds into the space above your hands.",
            listOf(
                "Watch your partner from a balanced ready stance at the fixed position.",
                "Read the feed timing and bend the knees while keeping your feet in place.",
                "Jump vertically and reach with both arms to cover the feed within your blocking area.",
                "Land in the same spot and reset while your partner returns the ball. Pause feeds outside your reach.",
            ),
        ),
    )

    fun forDrill(drill: Drill): DrillGuide? = setups[drill.id]?.let { setup ->
        DrillGuide(setup.equipment, setup.preparation,
            setup.actions.mapIndexed { index, action -> TechniqueStep("Step ${index + 1}", action) },
            SkillLearningCatalog.forSkill(drill.skill).mistakes)
    }
}
