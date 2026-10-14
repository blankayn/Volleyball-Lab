package com.volleyballlab.player.domain.model

data class TechniqueStep(val title: String, val action: String)
data class SkillLearning(val overview: String, val steps: List<TechniqueStep>, val mistakes: List<String>)

/** Instructional content complements the app's measurable cues, with no accuracy claims. */
object SkillLearningCatalog {
    fun forSkill(skill: Skill): SkillLearning = when (skill) {
        Skill.Serving -> SkillLearning("Practice the serve from one spot with a repeatable toss and a controlled reach through the ball.", listOf(
            TechniqueStep("Prepare", "Face your target with balanced feet in one spot. Hold the ball in your non-hitting hand."),
            TechniqueStep("Load", "Lift the ball with a consistent toss. Draw the hitting elbow back as your weight moves forward."),
            TechniqueStep("Contact", "Reach high and contact the ball with an open, firm hand. Keep the ball in front of the hitting shoulder."),
            TechniqueStep("Finish", "Let your arm continue toward the target, then regain balance.")),
            listOf("An inconsistent toss moves contact behind the head.", "A bent hitting elbow shortens the contact reach.", "Excessive backward lean disrupts balance."))
        Skill.Receiving -> SkillLearning("Practice forearm passing from a balanced stance with a stable platform and feeds delivered within reach.", listOf(
            TechniqueStep("Prepare", "Set your feet in one spot with knees comfortably bent. Use a controlled feed within reach."),
            TechniqueStep("Load", "Stay balanced and join your hands to form a flat forearm platform."),
            TechniqueStep("Contact", "Present straight arms in front of the body. Set the platform angle toward your target."),
            TechniqueStep("Finish", "Use a small, controlled leg extension. Recover to a balanced ready position.")),
            listOf("Bent elbows make the platform inconsistent.", "A large arm swing makes ball control harder.", "Uneven shoulders can change the platform direction."))
        Skill.Setting -> SkillLearning("Practice setting in one spot with a consistent contact position and coordinated leg and arm extension.", listOf(
            TechniqueStep("Prepare", "Face the intended target from a balanced stance. Use a controlled toss or feed above your forehead."),
            TechniqueStep("Load", "Bring open hands above the forehead and soften your knees and elbows."),
            TechniqueStep("Contact", "Meet the ball with spread fingers above the forehead. Keep the contact balanced."),
            TechniqueStep("Finish", "Extend legs and arms toward the target together, then recover.")),
            listOf("Hands too low move contact away from the forehead.", "Stiff knees reduce coordinated extension.", "Uneven hand contact can change the set direction."))
        Skill.Spiking -> SkillLearning("Practice the hitting arm swing from a fixed starting position. Jump drills use a takeoff and landing in the same spot.", listOf(
            TechniqueStep("Prepare", "Start with balanced feet in one spot. Use a controlled toss or partner feed in front of your hitting shoulder."),
            TechniqueStep("Load", "Draw the hitting elbow back. For an in-place jump, bend the knees and prepare to take off from the same spot."),
            TechniqueStep("Contact", "Reach high with the hitting arm and contact the ball in front of the hitting shoulder."),
            TechniqueStep("Finish", "Follow through and regain balance. If jumping, land in the same spot with knees flexed.")),
            listOf("A feed behind the hitting shoulder disrupts contact.", "A bent contact arm lowers the reach.", "Drifting away from the starting spot breaks balance."))
        Skill.Blocking -> SkillLearning("Practice blocking from one spot with a balanced vertical jump and hands presented above the net.", listOf(
            TechniqueStep("Prepare", "Start square to the blocking area with balanced feet and both hands ready."),
            TechniqueStep("Load", "Plant in balance and bend the knees before jumping."),
            TechniqueStep("Contact", "Reach with both arms and spread the fingers. Keep the hands positioned to close the attacking path."),
            TechniqueStep("Finish", "Land in the same spot under control and recover without drifting into the net.")),
            listOf("Bent arms reduce blocking height.", "Hands too far apart leave a gap.", "Drifting during takeoff makes controlled landing harder."))
    }
}
