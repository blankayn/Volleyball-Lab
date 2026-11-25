package com.volleyballlab.player.data.skill

import com.volleyballlab.player.domain.model.Drill
import com.volleyballlab.player.domain.model.DrillLevel
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.repository.DrillRepository

/**
 * The starter drill catalogue, compiled into the app.
 *
 * Three drills per skill, ordered easiest first, with repetitions performed from
 * a fixed position. Jump variants take off and land in the same spot. Each rep
 * returns to a ready posture, rather than holding a single pose continuously.
 * Existing IDs stay stable so saved sessions and assignments retain their links.
 *
 * This is a stand-in for coach-authored content, not a design decision to ship a
 * fixed list. It exists so the training flow is exercisable end to end while
 * conflict 2 in the capstone document - Firestore versus the relational data
 * dictionary - is still unresolved. See [DrillRepository].
 */
class StaticDrillRepository : DrillRepository {

    override suspend fun drillsFor(skill: Skill): List<Drill> =
        catalogue.filter { it.skill == skill }

    override suspend fun byId(id: String): Drill? = catalogue.firstOrNull { it.id == id }

    private companion object {
        val catalogue: List<Drill> = listOf(
            // --- Serving -----------------------------------------------------
            Drill(
                id = "serve-standing-float",
                skill = Skill.Serving,
                name = "Serving Stance",
                description = "Set balanced feet, lift the guiding arm, and reach upward with the other arm. Reset your posture after each controlled movement.",
                targetReps = 10,
                estimatedMinutes = 5,
                level = DrillLevel.Beginner,
            ),
            Drill(
                id = "serve-jump-float",
                skill = Skill.Serving,
                name = "Serving Reach",
                description = "Practice a small vertical jump with an overhead reach. Focus on arm alignment and return to a balanced stance in the same spot.",
                targetReps = 10,
                estimatedMinutes = 8,
                level = DrillLevel.Intermediate,
            ),
            Drill(
                id = "serve-topspin-jump",
                skill = Skill.Serving,
                name = "Serving Body Alignment",
                description = "Practice an overhead arm swing from an in-place jump. Keep the trunk controlled and reset your feet and shoulders between repetitions.",
                targetReps = 8,
                estimatedMinutes = 10,
                level = DrillLevel.Advanced,
            ),

            // --- Receiving ---------------------------------------------------
            Drill(
                id = "receive-wall-platform",
                skill = Skill.Receiving,
                name = "Passing Platform",
                description = "Bend your knees comfortably, join your forearms, and keep your shoulders level. Return upright between posture repetitions.",
                targetReps = 15,
                estimatedMinutes = 5,
                level = DrillLevel.Beginner,
            ),
            Drill(
                id = "receive-short-court",
                skill = Skill.Receiving,
                name = "Passing Ready Stance",
                description = "Set balanced feet and lean slightly from your hips. Form a steady forearm platform, then return to your ready stance.",
                targetReps = 20,
                estimatedMinutes = 8,
                level = DrillLevel.Beginner,
            ),
            Drill(
                id = "receive-serve-to-target",
                skill = Skill.Receiving,
                name = "Passing Body Alignment",
                description = "Align your shoulders and forearms over a balanced base. Practice a small leg extension while keeping the platform steady.",
                targetReps = 15,
                estimatedMinutes = 10,
                level = DrillLevel.Intermediate,
            ),

            // --- Setting -----------------------------------------------------
            Drill(
                id = "set-wall-reps",
                skill = Skill.Setting,
                name = "Setting Stance",
                description = "Place balanced hands above your forehead with bent elbows and soft knees. Extend under control, then reset your posture.",
                targetReps = 20,
                estimatedMinutes = 5,
                level = DrillLevel.Beginner,
            ),
            Drill(
                id = "set-toss-to-target",
                skill = Skill.Setting,
                name = "Setting Extension",
                description = "Start with your hands above your forehead. Practice controlled leg and arm extension while keeping both feet grounded.",
                targetReps = 15,
                estimatedMinutes = 8,
                level = DrillLevel.Beginner,
            ),
            Drill(
                id = "set-back-set",
                skill = Skill.Setting,
                name = "Setting Overhead Alignment",
                description = "Keep your hands balanced above your forehead and your trunk upright. Extend both arms evenly, then return to your starting stance.",
                targetReps = 12,
                estimatedMinutes = 10,
                level = DrillLevel.Advanced,
            ),

            // --- Spiking -----------------------------------------------------
            Drill(
                id = "spike-standing-swing",
                skill = Skill.Spiking,
                name = "Standing Spike",
                description = "Keep both feet on the floor; no jump. Lift your guiding arm, draw your hitting elbow back, swing overhead under control, then reset your stance.",
                targetReps = 12,
                estimatedMinutes = 5,
                level = DrillLevel.Beginner,
            ),
            Drill(
                id = "spike-three-step",
                skill = Skill.Spiking,
                name = "Spike Load and Swing",
                description = "Bend your knees to load the swing, then straighten up and reach overhead. Keep both feet planted and reset between reps.",
                targetReps = 12,
                estimatedMinutes = 10,
                level = DrillLevel.Intermediate,
            ),
            Drill(
                id = "spike-four-step-lines",
                skill = Skill.Spiking,
                name = "Spiking Body Alignment",
                description = "Bend your knees and swing overhead with controlled trunk rotation. Keep both feet planted and finish in balance.",
                targetReps = 10,
                estimatedMinutes = 12,
                level = DrillLevel.Advanced,
            ),

            // --- Blocking ----------------------------------------------------
            Drill(
                id = "block-ready-jumps",
                skill = Skill.Blocking,
                name = "Blocking Stance",
                description = "Begin with balanced feet and both hands raised. Practice an in-place jump, extend both arms, and return with softly bent knees.",
                targetReps = 15,
                estimatedMinutes = 5,
                level = DrillLevel.Beginner,
            ),
            Drill(
                id = "block-lateral-shuffle",
                skill = Skill.Blocking,
                name = "Blocking Arm Alignment",
                description = "Keep your shoulders square and reach evenly with both arms during an in-place jump. Reset your hand spacing and stance each time.",
                targetReps = 12,
                estimatedMinutes = 8,
                level = DrillLevel.Intermediate,
            ),
            Drill(
                id = "block-read-setter",
                skill = Skill.Blocking,
                name = "Blocking Body Alignment",
                description = "Align your feet, knees, shoulders, and hands during an in-place jump. Reach slightly forward with both hands and reset under control.",
                targetReps = 10,
                estimatedMinutes = 10,
                level = DrillLevel.Advanced,
            ),
        )
    }
}
