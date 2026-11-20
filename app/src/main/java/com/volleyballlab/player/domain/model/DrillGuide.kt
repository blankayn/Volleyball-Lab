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
            "Clear practice space and a stable camera position",
            "Keep your whole body visible. Move slowly within a comfortable range and reset between repetitions.",
            listOf(
                "Set balanced feet in one spot and keep your trunk comfortably upright.",
                "Lift your guiding arm and draw the other elbow back without arching your back.",
                "Reach upward with an open hand and extend your elbow comfortably.",
                "Lower your arms under control and return to a balanced stance.",
            ),
        ),
        "serve-jump-float" to Setup(
            "Clear practice space and a stable camera position",
            "Keep your whole body visible. Move slowly within a comfortable range and reset between repetitions.",
            listOf(
                "Set balanced feet and lift your guiding arm.",
                "Bend your knees and prepare the raised elbow.",
                "Make a small vertical jump and reach upward with an open hand.",
                "Return to the same spot with softened knees and reset your arms.",
            ),
        ),
        "serve-topspin-jump" to Setup(
            "Clear practice space and a stable camera position",
            "Keep your whole body visible. Move slowly within a comfortable range and reset between repetitions.",
            listOf(
                "Start with balanced feet and an upright trunk.",
                "Bend your knees before a small vertical jump in place.",
                "Extend your raised arm overhead while keeping your trunk controlled.",
                "Lower the arm and return to the same spot with softly bent knees.",
            ),
        ),
        "receive-wall-platform" to Setup(
            "Clear practice space and a stable camera position",
            "Keep your whole body visible. Move slowly within a comfortable range and reset between repetitions.",
            listOf(
                "Set your feet in one spot with knees comfortably bent.",
                "Lean slightly from your hips and join your hands to form a forearm platform.",
                "Extend your arms in front of your body and keep your shoulders level.",
                "Use a small, controlled leg extension, then relax your arms and return to ready.",
            ),
        ),
        "receive-short-court" to Setup(
            "Clear practice space and a stable camera position",
            "Keep your whole body visible. Move slowly within a comfortable range and reset between repetitions.",
            listOf(
                "Set your feet in a balanced ready stance.",
                "Bend your knees comfortably and lean slightly from the hips.",
                "Join your forearms in front of your body with level shoulders.",
                "Relax your arms and return upright before the next repetition.",
            ),
        ),
        "receive-serve-to-target" to Setup(
            "Clear practice space and a stable camera position",
            "Keep your whole body visible. Move slowly within a comfortable range and reset between repetitions.",
            listOf(
                "Start with balanced feet and softly bent knees.",
                "Form a steady forearm platform in front of your body.",
                "Keep your shoulders level during a small leg extension.",
                "Relax your arms and reset the same stance.",
            ),
        ),
        "set-wall-reps" to Setup(
            "Clear practice space and a stable camera position",
            "Keep your whole body visible. Move slowly within a comfortable range and reset between repetitions.",
            listOf(
                "Set balanced feet and keep your trunk upright.",
                "Bring open hands above the forehead and soften your knees and elbows.",
                "Keep your hands even and extend your arms upward within a comfortable range.",
                "Return your hands and knees to the starting position under control.",
            ),
        ),
        "set-toss-to-target" to Setup(
            "Clear practice space and a stable camera position",
            "Keep your whole body visible. Move slowly within a comfortable range and reset between repetitions.",
            listOf(
                "Keep both feet grounded in a balanced stance.",
                "Raise your hands above your forehead and bend your knees and elbows comfortably.",
                "Extend your legs and arms upward under control.",
                "Lower your hands and soften your knees to reset.",
            ),
        ),
        "set-back-set" to Setup(
            "Clear practice space and a stable camera position",
            "Keep your whole body visible. Move slowly within a comfortable range and reset between repetitions.",
            listOf(
                "Start upright with balanced feet and hands above your forehead.",
                "Keep both hands even and elbows comfortably bent.",
                "Extend both arms overhead without arching your lower back.",
                "Return to the same upright stance and reset your hands.",
            ),
        ),
        "spike-standing-swing" to Setup(
            "Clear practice space and a stable camera position",
            "Keep your whole body visible. Move slowly within a comfortable range and reset between repetitions.",
            listOf(
                "Keep both feet grounded in a balanced stance.",
                "Draw one elbow back and lift the guiding arm.",
                "Reach overhead with a slow arm swing and controlled trunk rotation.",
                "Lower your arm and reset your shoulders and stance.",
            ),
        ),
        "spike-three-step" to Setup(
            "Clear practice space and a stable camera position",
            "Keep your whole body visible. Move slowly within a comfortable range and reset between repetitions.",
            listOf(
                "Start with balanced feet in one spot and a comfortably upright trunk.",
                "Draw one elbow back and lift the guiding arm. Bend your knees before an in-place jump.",
                "Extend the raised arm above the shoulder while keeping your trunk controlled.",
                "Lower the arm and regain balance. If jumping, return to the same spot with softened knees.",
            ),
        ),
        "spike-four-step-lines" to Setup(
            "Clear practice space and a stable camera position",
            "Keep your whole body visible. Move slowly within a comfortable range and reset between repetitions.",
            listOf(
                "Begin with balanced feet and square shoulders.",
                "Bend your knees and hips before a small vertical jump.",
                "Extend your raised arm with a slow swing and controlled trunk rotation.",
                "Return to the same spot with softly bent knees and reset your stance.",
            ),
        ),
        "block-ready-jumps" to Setup(
            "Clear practice space and a stable camera position",
            "Keep your whole body visible. Move slowly within a comfortable range and reset between repetitions.",
            listOf(
                "Start with balanced feet, square shoulders, and both hands raised.",
                "Bend your knees before a small vertical jump from the same spot.",
                "Reach upward with both arms and position your hands slightly in front of your shoulders.",
                "Return to the same spot with softened knees, then reset your arms and stance.",
            ),
        ),
        "block-lateral-shuffle" to Setup(
            "Clear practice space and a stable camera position",
            "Keep your whole body visible. Move slowly within a comfortable range and reset between repetitions.",
            listOf(
                "Set balanced feet with your shoulders square and both hands raised.",
                "Bend your knees before a small vertical jump in place.",
                "Extend both arms evenly and keep your hands comfortably spaced.",
                "Return to the same spot with softened knees and reset your hand position.",
            ),
        ),
        "block-read-setter" to Setup(
            "Clear practice space and a stable camera position",
            "Keep your whole body visible. Move slowly within a comfortable range and reset between repetitions.",
            listOf(
                "Set balanced feet, square shoulders, and raised hands.",
                "Bend your knees while keeping your trunk controlled.",
                "Make a small vertical jump and reach both hands overhead and slightly forward.",
                "Return to the same spot with softened knees and reset your whole-body alignment.",
            ),
        ),
    )

    fun forDrill(drill: Drill): DrillGuide? = setups[drill.id]?.let { setup ->
        DrillGuide(setup.equipment, setup.preparation,
            setup.actions.mapIndexed { index, action -> TechniqueStep("Step ${index + 1}", action) },
            SkillLearningCatalog.forSkill(drill.skill).mistakes)
    }
}
