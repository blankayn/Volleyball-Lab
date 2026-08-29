package com.volleyballlab.player.data.skill

import com.volleyballlab.player.domain.model.Drill
import com.volleyballlab.player.domain.model.DrillLevel
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.repository.DrillRepository

/**
 * The starter drill catalogue, compiled into the app.
 *
 * Three drills per skill, ordered easiest first, chosen so each one produces a
 * movement the corresponding analyzer can actually segment. A drill that never
 * returns the athlete to a resting posture between reps will not count reps
 * correctly, so "hold a low platform for thirty seconds" is deliberately absent
 * even though it is a real passing exercise.
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
                name = "Standing float serve",
                description = "Serve from behind the end line with no approach. " +
                    "Work on a repeatable toss and a firm, flat contact.",
                targetReps = 10,
                estimatedMinutes = 5,
                level = DrillLevel.Beginner,
            ),
            Drill(
                id = "serve-jump-float",
                skill = Skill.Serving,
                name = "Jump float serve",
                description = "Add a one-step approach and a small jump. " +
                    "The contact should still be flat, with no wrist snap.",
                targetReps = 10,
                estimatedMinutes = 8,
                level = DrillLevel.Intermediate,
            ),
            Drill(
                id = "serve-topspin-jump",
                skill = Skill.Serving,
                name = "Topspin jump serve",
                description = "Full approach, high toss in front, and a hard " +
                    "snap over the top of the ball.",
                targetReps = 8,
                estimatedMinutes = 10,
                level = DrillLevel.Advanced,
            ),

            // --- Receiving ---------------------------------------------------
            Drill(
                id = "receive-wall-platform",
                skill = Skill.Receiving,
                name = "Wall platform passes",
                description = "Pass repeatedly against a wall from three metres. " +
                    "Reset to a tall ready position between every contact.",
                targetReps = 15,
                estimatedMinutes = 5,
                level = DrillLevel.Beginner,
            ),
            Drill(
                id = "receive-short-court",
                skill = Skill.Receiving,
                name = "Short-court passing",
                description = "Controlled passing with a partner inside the " +
                    "attack line. Emphasis on a still, angled platform.",
                targetReps = 20,
                estimatedMinutes = 8,
                level = DrillLevel.Beginner,
            ),
            Drill(
                id = "receive-serve-to-target",
                skill = Skill.Receiving,
                name = "Serve receive to target",
                description = "Receive live serves and pass to the setter's " +
                    "position. Read the serve early and move before it arrives.",
                targetReps = 15,
                estimatedMinutes = 10,
                level = DrillLevel.Intermediate,
            ),

            // --- Setting -----------------------------------------------------
            Drill(
                id = "set-wall-reps",
                skill = Skill.Setting,
                name = "Wall setting reps",
                description = "Set against a wall above head height. " +
                    "Hands form early, above the forehead, every time.",
                targetReps = 20,
                estimatedMinutes = 5,
                level = DrillLevel.Beginner,
            ),
            Drill(
                id = "set-toss-to-target",
                skill = Skill.Setting,
                name = "Set to target from a toss",
                description = "Receive a tossed ball and set to a fixed target. " +
                    "Drive the set with the legs, then finish extended.",
                targetReps = 15,
                estimatedMinutes = 8,
                level = DrillLevel.Beginner,
            ),
            Drill(
                id = "set-back-set",
                skill = Skill.Setting,
                name = "Back set under pressure",
                description = "Set behind you off a moving pass without " +
                    "telegraphing the direction.",
                targetReps = 12,
                estimatedMinutes = 10,
                level = DrillLevel.Advanced,
            ),

            // --- Spiking -----------------------------------------------------
            Drill(
                id = "spike-standing-swing",
                skill = Skill.Spiking,
                name = "Standing arm swing",
                description = "Hit from a standing position with no approach. " +
                    "Isolates the swing so the arm path can be corrected.",
                targetReps = 12,
                estimatedMinutes = 5,
                level = DrillLevel.Beginner,
            ),
            Drill(
                id = "spike-three-step",
                skill = Skill.Spiking,
                name = "Three-step approach",
                description = "Full three-step approach into a hit. " +
                    "Load deep on the last step and swing through the ball.",
                targetReps = 12,
                estimatedMinutes = 10,
                level = DrillLevel.Intermediate,
            ),
            Drill(
                id = "spike-four-step-lines",
                skill = Skill.Spiking,
                name = "Four-step, line and cross",
                description = "Alternate hitting line and cross-court from a " +
                    "four-step approach, keeping the same swing.",
                targetReps = 10,
                estimatedMinutes = 12,
                level = DrillLevel.Advanced,
            ),

            // --- Blocking ----------------------------------------------------
            Drill(
                id = "block-ready-jumps",
                skill = Skill.Blocking,
                name = "Ready-position block jumps",
                description = "Block jump from a still ready position at the net. " +
                    "Press the hands over, not up.",
                targetReps = 15,
                estimatedMinutes = 5,
                level = DrillLevel.Beginner,
            ),
            Drill(
                id = "block-lateral-shuffle",
                skill = Skill.Blocking,
                name = "Lateral shuffle block",
                description = "Shuffle two positions along the net and block. " +
                    "Square up before jumping, never while drifting.",
                targetReps = 12,
                estimatedMinutes = 8,
                level = DrillLevel.Intermediate,
            ),
            Drill(
                id = "block-read-setter",
                skill = Skill.Blocking,
                name = "Read blocking off the setter",
                description = "Watch the setter, commit late, and close the " +
                    "block on the hitter they choose.",
                targetReps = 10,
                estimatedMinutes = 10,
                level = DrillLevel.Advanced,
            ),
        )
    }
}
