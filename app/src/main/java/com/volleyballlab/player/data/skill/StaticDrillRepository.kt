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
                name = "Standing float serve",
                description = "Serve from a fixed, grounded stance. " +
                    "Practice a repeatable toss and firm, flat contact, then reset in place.",
                targetReps = 10,
                estimatedMinutes = 5,
                level = DrillLevel.Beginner,
            ),
            Drill(
                id = "serve-jump-float",
                skill = Skill.Serving,
                name = "In-place jump float serve",
                description = "Use a controlled toss and small vertical jump from the same spot. " +
                    "Keep the contact flat and land back in your starting position.",
                targetReps = 10,
                estimatedMinutes = 8,
                level = DrillLevel.Intermediate,
            ),
            Drill(
                id = "serve-topspin-jump",
                skill = Skill.Serving,
                name = "In-place topspin jump serve",
                description = "Time a vertical jump to a controlled toss from a fixed stance. " +
                    "Reach high, contact over the ball, and land in the same spot.",
                targetReps = 8,
                estimatedMinutes = 10,
                level = DrillLevel.Advanced,
            ),

            // --- Receiving ---------------------------------------------------
            Drill(
                id = "receive-wall-platform",
                skill = Skill.Receiving,
                name = "Wall platform passes",
                description = "Pass gentle wall rebounds from a fixed stance. " +
                    "Use a stable platform and reset upright between contacts.",
                targetReps = 15,
                estimatedMinutes = 5,
                level = DrillLevel.Beginner,
            ),
            Drill(
                id = "receive-short-court",
                skill = Skill.Receiving,
                name = "Stationary partner passing",
                description = "Receive controlled partner feeds delivered within your reach. " +
                    "Keep your feet in place and direct a still, angled platform toward the target.",
                targetReps = 20,
                estimatedMinutes = 8,
                level = DrillLevel.Beginner,
            ),
            Drill(
                id = "receive-serve-to-target",
                skill = Skill.Receiving,
                name = "Fixed-stance serve receive",
                description = "Receive gentle serves directed into your ready position. " +
                    "Pass toward a fixed target and pause when a ball is outside your reach.",
                targetReps = 15,
                estimatedMinutes = 10,
                level = DrillLevel.Intermediate,
            ),

            // --- Setting -----------------------------------------------------
            Drill(
                id = "set-wall-reps",
                skill = Skill.Setting,
                name = "Wall setting reps",
                description = "Set gentle wall rebounds from the same spot. " +
                    "Prepare balanced hands above the forehead and extend under control.",
                targetReps = 20,
                estimatedMinutes = 5,
                level = DrillLevel.Beginner,
            ),
            Drill(
                id = "set-toss-to-target",
                skill = Skill.Setting,
                name = "Stationary set to target",
                description = "Use a controlled self-toss or partner feed above your forehead. " +
                    "Keep a fixed stance and coordinate leg and arm extension toward the target.",
                targetReps = 15,
                estimatedMinutes = 8,
                level = DrillLevel.Beginner,
            ),
            Drill(
                id = "set-back-set",
                skill = Skill.Setting,
                name = "Controlled stationary back set",
                description = "Set toward a rear target from a balanced, fixed stance. " +
                    "Use an easy feed above the forehead and finish with coordinated extension.",
                targetReps = 12,
                estimatedMinutes = 10,
                level = DrillLevel.Advanced,
            ),

            // --- Spiking -----------------------------------------------------
            Drill(
                id = "spike-standing-swing",
                skill = Skill.Spiking,
                name = "Standing arm swing",
                description = "Keep both feet grounded and practice a controlled arm swing. " +
                    "Use a feed within reach and reset your stance after each hit.",
                targetReps = 12,
                estimatedMinutes = 5,
                level = DrillLevel.Beginner,
            ),
            Drill(
                id = "spike-three-step",
                skill = Skill.Spiking,
                name = "In-place spike timing",
                description = "Time a vertical jump to a controlled partner feed. " +
                    "Load from the same spot, contact high, and land back in your starting position.",
                targetReps = 12,
                estimatedMinutes = 10,
                level = DrillLevel.Intermediate,
            ),
            Drill(
                id = "spike-four-step-lines",
                skill = Skill.Spiking,
                name = "In-place line and cross",
                description = "Alternate line and cross-court targets with a controlled swing. " +
                    "Use a vertical jump from the same spot for each attempt.",
                targetReps = 10,
                estimatedMinutes = 12,
                level = DrillLevel.Advanced,
            ),

            // --- Blocking ----------------------------------------------------
            Drill(
                id = "block-ready-jumps",
                skill = Skill.Blocking,
                name = "Ready-position block jumps",
                description = "Jump vertically from a fixed ready position and land in the same spot. " +
                    "Extend both arms and present the hands slightly forward.",
                targetReps = 15,
                estimatedMinutes = 5,
                level = DrillLevel.Beginner,
            ),
            Drill(
                id = "block-lateral-shuffle",
                skill = Skill.Blocking,
                name = "In-place block alignment",
                description = "Keep a square, balanced stance while practicing block alignment. " +
                    "Jump vertically, reach with both arms, and reset in the same spot.",
                targetReps = 12,
                estimatedMinutes = 8,
                level = DrillLevel.Intermediate,
            ),
            Drill(
                id = "block-read-setter",
                skill = Skill.Blocking,
                name = "Stationary read blocking",
                description = "Watch a partner's controlled feed into your fixed blocking area. " +
                    "Time a vertical jump and present both hands without changing position.",
                targetReps = 10,
                estimatedMinutes = 10,
                level = DrillLevel.Advanced,
            ),
        )
    }
}
