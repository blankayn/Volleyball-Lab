package com.volleyballlab.player.domain.model

import androidx.annotation.StringRes
import com.volleyballlab.player.R

enum class DrillLevel(@param:StringRes val displayName: Int) {
    Beginner(R.string.drill_level_beginner),
    Intermediate(R.string.drill_level_intermediate),
    Advanced(R.string.drill_level_advanced),
}

/**
 * A prescribed set of repetitions for one skill.
 *
 * ## Why the text here is not a string resource
 *
 * [name] and [description] are plain strings while everything in [Skill] and
 * [FormCue] uses `@StringRes`. That is a deliberate line, not an oversight:
 *
 * - Skill names and cue labels are **app chrome**. They ship with the binary,
 *   there is a fixed set of them, and they must be localisable.
 * - Drills are **content**. Once the Firebase layer lands, a coach will author
 *   them and they will arrive as strings over the wire. Modelling them as
 *   resource ids now would mean rewriting this type the moment that happens.
 *
 * @param targetReps what the drill asks for. The session is not truncated at this
 *   number - the athlete may do more or fewer, and the results screen reports
 *   what was actually detected against what was prescribed.
 */
data class Drill(
    val id: String,
    val skill: Skill,
    val name: String,
    val description: String,
    val targetReps: Int,
    val estimatedMinutes: Int,
    val level: DrillLevel,
)
