package com.volleyballlab.player.domain.model

data class TechniqueStep(val title: String, val action: String)
data class SkillLearning(val overview: String, val steps: List<TechniqueStep>, val mistakes: List<String>)

/** Body-position instruction complements measured posture cues. */
object SkillLearningCatalog {
    fun forSkill(skill: Skill): SkillLearning = when (skill) {
        Skill.Serving -> SkillLearning("Practice the serving posture through a controlled arm raise and reach from a balanced stance.", listOf(
            TechniqueStep("Prepare", "Set balanced feet in one spot and keep your trunk comfortably upright."),
            TechniqueStep("Load", "Lift your guiding arm and draw the other elbow back without arching your back."),
            TechniqueStep("Position", "Reach upward with an open hand and extend your elbow comfortably."),
            TechniqueStep("Reset", "Lower your arms under control and return to a balanced stance.")),
            listOf("An uneven stance makes balance harder.", "A bent reaching elbow shortens your reach.", "Excessive backward lean changes trunk alignment."))
        Skill.Receiving -> SkillLearning("Practice a stable passing platform with balanced feet, bent knees, and level shoulders.", listOf(
            TechniqueStep("Prepare", "Set your feet in one spot with knees comfortably bent."),
            TechniqueStep("Load", "Lean slightly from your hips and join your hands to form a forearm platform."),
            TechniqueStep("Position", "Extend your arms in front of your body and keep your shoulders level."),
            TechniqueStep("Reset", "Use a small, controlled leg extension, then relax your arms and return to ready.")),
            listOf("Bent elbows change the platform shape.", "A large arm swing makes the platform harder to steady.", "Uneven shoulders tilt the forearm platform."))
        Skill.Setting -> SkillLearning("Practice the setting stance with balanced hands above the forehead and controlled extension.", listOf(
            TechniqueStep("Prepare", "Set balanced feet and keep your trunk upright."),
            TechniqueStep("Load", "Bring open hands above the forehead and soften your knees and elbows."),
            TechniqueStep("Position", "Keep your hands even and extend your arms upward within a comfortable range."),
            TechniqueStep("Reset", "Return your hands and knees to the starting position under control.")),
            listOf("Low hands change the overhead starting position.", "Stiff knees limit the preparation movement.", "Uneven arms change the overhead shape."))
        Skill.Spiking -> SkillLearning("Practice the spiking arm position and body alignment from a fixed starting stance.", listOf(
            TechniqueStep("Prepare", "Start with balanced feet in one spot and a comfortably upright trunk."),
            TechniqueStep("Load", "Draw one elbow back and lift the guiding arm. Bend your knees before an in-place jump."),
            TechniqueStep("Position", "Extend the raised arm above the shoulder while keeping your trunk controlled."),
            TechniqueStep("Reset", "Lower the arm and regain balance. If jumping, return to the same spot with softened knees.")),
            listOf("Excessive backward lean changes shoulder alignment.", "A bent reaching elbow shortens your reach.", "Drifting from the starting spot makes balance harder."))
        Skill.Blocking -> SkillLearning("Practice a balanced blocking stance, overhead arm alignment, and controlled return.", listOf(
            TechniqueStep("Prepare", "Start with balanced feet, square shoulders, and both hands raised."),
            TechniqueStep("Load", "Bend your knees before a small vertical jump from the same spot."),
            TechniqueStep("Position", "Reach upward with both arms and position your hands slightly in front of your shoulders."),
            TechniqueStep("Reset", "Return to the same spot with softened knees, then reset your arms and stance.")),
            listOf("Bent elbows shorten the overhead reach.", "Uneven hand spacing changes the blocking shape.", "Drifting during takeoff makes balance harder."))
    }
}
