package com.volleyballlab.player.domain.model

import kotlin.math.roundToInt

/** Reviewed sprite-sheet regions; illustrations are independent of camera analysis. */
enum class PostureSheet(val skill: Skill, val firstRowEnd: Int, val secondRowEnd: Int) {
    Serving(Skill.Serving, 478, 954),
    Receiving(Skill.Receiving, 477, 955),
    Setting(Skill.Setting, 496, 960),
    Spiking(Skill.Spiking, 478, 954),
    Blocking(Skill.Blocking, 483, 965);

    fun rowStart(row: Int): Int = when (row) { 0 -> 0; 1 -> firstRowEnd; else -> secondRowEnd }
    fun rowEnd(row: Int): Int = when (row) { 0 -> firstRowEnd; 1 -> secondRowEnd; else -> 1448 }
}

data class PostureSampleStep(val label: String, val cue: String)
data class PostureFrameRegion(val x: Int, val y: Int, val width: Int, val height: Int)

data class DrillPostureSample(val sheet: PostureSheet, val row: Int, val steps: List<PostureSampleStep>) {
    val supportsHandedness: Boolean get() = sheet.skill == Skill.Serving || sheet.skill == Skill.Spiking

    fun frameRegion(phase: Int, imageWidth: Int, imageHeight: Int): PostureFrameRegion {
        require(imageWidth >= 4 && imageHeight >= 3)
        val column = phase.coerceIn(0, 3)
        val left = imageWidth * column / 4
        val right = imageWidth * (column + 1) / 4
        val top = (sheet.rowStart(row) * imageHeight / 1448f).roundToInt()
        val bottom = (sheet.rowEnd(row) * imageHeight / 1448f).roundToInt()
        return PostureFrameRegion(left, top, right - left, bottom - top)
    }
}

object DrillPostureSamples {
    private fun steps(vararg values: Pair<String, String>) = values.map { PostureSampleStep(it.first, it.second) }
    private val serve = steps("Ready" to "Balance your feet before the toss.", "Toss" to "Prepare the hitting elbow while keeping the toss in front.", "Contact" to "Reach high with a firm, open hitting hand.", "Reset" to "Finish the swing and regain a balanced stance.")
    private val jumpServe = steps("Ready" to "Begin with balanced feet in one spot.", "Load" to "Bend the knees and prepare the hitting arm without stepping forward.", "Reach" to "Jump from the same spot and reach above the hitting shoulder.", "Land" to "Land in the starting spot with softened knees.")
    private val pass = steps("Ready" to "Keep a balanced base with soft knees.", "Platform" to "Join your hands and straighten the elbows.", "Contact" to "Keep the platform quiet and use controlled leg extension.", "Reset" to "Separate the hands and return to ready position.")
    private val passingPosition = steps("Ready" to "Start with balanced feet in one spot.", "Position" to "Use a feed within reach and join the hands without chasing the ball.", "Platform" to "Present a stable forearm platform toward the target.", "Reset" to "Recover your stance in the same spot before the next feed.")
    private val set = steps("Ready" to "Bring both hands above the forehead.", "Cushion" to "Soften the knees and keep the hands balanced.", "Extend" to "Extend the legs and both arms together.", "Reset" to "Regain balance and prepare both hands again.")
    private val backSet = steps("Ready" to "Keep the hands prepared above your forehead.", "Cushion" to "Receive with balanced hands and softened knees.", "Back set" to "Extend both arms together toward the rear target.", "Reset" to "Return to a balanced upright stance.")
    private val swing = steps("Ready" to "Keep both feet grounded in a balanced stance.", "Prepare" to "Draw the hitting elbow back and lift the guiding arm.", "Reach" to "Reach high while rotating the torso under control.", "Recover" to "Finish the arm swing and regain balance.")
    private val jumpHit = steps("Ready" to "Prepare with balanced feet in one spot.", "Load" to "Bend the knees and hips before an in-place takeoff.", "Reach" to "Reach high with the hitting arm in front of the shoulder.", "Land" to "Land in the starting spot with control and softened knees.")
    private val block = steps("Ready" to "Keep both hands ready and the feet balanced in one spot.", "Load" to "Bend the knees while keeping the shoulders square.", "Reach" to "Jump straight up and extend both arms overhead and slightly forward.", "Land" to "Land in the same spot on both feet and soften the knees.")

    private val samples = mapOf(
        "serve-standing-float" to DrillPostureSample(PostureSheet.Serving, 0, serve),
        "serve-jump-float" to DrillPostureSample(PostureSheet.Serving, 1, jumpServe),
        "serve-topspin-jump" to DrillPostureSample(PostureSheet.Serving, 2, jumpServe),
        "receive-wall-platform" to DrillPostureSample(PostureSheet.Receiving, 0, pass),
        "receive-short-court" to DrillPostureSample(PostureSheet.Receiving, 1, passingPosition),
        "receive-serve-to-target" to DrillPostureSample(PostureSheet.Receiving, 2, passingPosition),
        "set-wall-reps" to DrillPostureSample(PostureSheet.Setting, 0, set),
        "set-toss-to-target" to DrillPostureSample(PostureSheet.Setting, 1, set),
        "set-back-set" to DrillPostureSample(PostureSheet.Setting, 2, backSet),
        "spike-standing-swing" to DrillPostureSample(PostureSheet.Spiking, 0, swing),
        "spike-three-step" to DrillPostureSample(PostureSheet.Spiking, 1, jumpHit),
        "spike-four-step-lines" to DrillPostureSample(PostureSheet.Spiking, 2, jumpHit),
        "block-ready-jumps" to DrillPostureSample(PostureSheet.Blocking, 0, block),
        "block-lateral-shuffle" to DrillPostureSample(PostureSheet.Blocking, 0, block),
        "block-read-setter" to DrillPostureSample(PostureSheet.Blocking, 2, block),
    )

    fun forDrill(id: String): DrillPostureSample? = samples[id]

    fun starterFor(skill: Skill): String = when (skill) {
        Skill.Serving -> "serve-standing-float"
        Skill.Receiving -> "receive-wall-platform"
        Skill.Setting -> "set-wall-reps"
        Skill.Spiking -> "spike-standing-swing"
        Skill.Blocking -> "block-ready-jumps"
    }
}
