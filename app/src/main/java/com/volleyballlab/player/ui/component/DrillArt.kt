package com.volleyballlab.player.ui.component

import androidx.annotation.DrawableRes
import com.volleyballlab.player.R

/**
 * The project's own illustration of each drill's position, in the same flat style
 * and skill colour as the [skillVisual] icons. Generated for this project with
 * ChatGPT image generation; prompts and provenance are in `docs/drill-art.md`.
 *
 * Returns null for a drill that has no illustration yet, so callers can fall back
 * to the vector pose icon or the skill icon.
 */
object DrillArt {
    private val art = mapOf(
        "serve-standing-float" to R.drawable.drill_art_serve_standing_float,
        "serve-jump-float" to R.drawable.drill_art_serve_jump_float,
        "serve-topspin-jump" to R.drawable.drill_art_serve_topspin_jump,
        "receive-wall-platform" to R.drawable.drill_art_receive_wall_platform,
        "receive-short-court" to R.drawable.drill_art_receive_short_court,
        "receive-serve-to-target" to R.drawable.drill_art_receive_serve_to_target,
        "set-wall-reps" to R.drawable.drill_art_set_wall_reps,
        "set-toss-to-target" to R.drawable.drill_art_set_toss_to_target,
        "set-back-set" to R.drawable.drill_art_set_back_set,
        "spike-standing-swing" to R.drawable.drill_art_spike_standing_swing,
        "spike-three-step" to R.drawable.drill_art_spike_three_step,
        "spike-four-step-lines" to R.drawable.drill_art_spike_four_step_lines,
        "block-ready-jumps" to R.drawable.drill_art_block_ready_jumps,
        "block-lateral-shuffle" to R.drawable.drill_art_block_lateral_shuffle,
        "block-read-setter" to R.drawable.drill_art_block_read_setter,
    )

    @DrawableRes
    fun forDrill(drillId: String): Int? = art[drillId]
}
