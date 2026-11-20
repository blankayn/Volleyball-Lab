package com.volleyballlab.player.feature.train.drill

import androidx.annotation.DrawableRes
import com.volleyballlab.player.R

@DrawableRes
internal fun drillPoseIcon(drillId: String): Int? = when (drillId) {
    "serve-standing-float" -> R.drawable.drill_pose_serving_stance
    "serve-jump-float" -> R.drawable.drill_pose_serving_reach
    "serve-topspin-jump" -> R.drawable.drill_pose_serving_body_alignment
    "receive-wall-platform" -> R.drawable.drill_pose_passing_platform
    "receive-short-court" -> R.drawable.drill_pose_passing_ready_stance
    "receive-serve-to-target" -> R.drawable.drill_pose_passing_body_alignment
    "set-wall-reps" -> R.drawable.drill_pose_setting_stance
    "set-toss-to-target" -> R.drawable.drill_pose_setting_extension
    "set-back-set" -> R.drawable.drill_pose_setting_overhead_alignment
    "spike-standing-swing" -> R.drawable.drill_pose_spiking_arm_position
    "spike-three-step" -> R.drawable.drill_pose_spiking_reach
    "spike-four-step-lines" -> R.drawable.drill_pose_spiking_body_alignment
    "block-ready-jumps" -> R.drawable.drill_pose_blocking_stance
    "block-lateral-shuffle" -> R.drawable.drill_pose_blocking_arm_alignment
    "block-read-setter" -> R.drawable.drill_pose_blocking_body_alignment
    else -> null
}
