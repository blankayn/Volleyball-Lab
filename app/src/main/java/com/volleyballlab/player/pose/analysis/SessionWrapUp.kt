package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.CueId

/**
 * The spoken debrief at a view change and the closing of a session. Every
 * sentence restates a counted or recorded fact; nothing here measures anything.
 */
object SessionWrapUp {
    private fun count(n: Int): String = CoachPhrasebook.numberWord(n).lowercase()

    /**
     * One line about the view just finished.
     *
     * @param takeaway the direction-correct observation and action for a cue
     *   that repeatedly missed in this view, or null when none did.
     * @param heldCue a cue that stayed inside its target on every rep of the view.
     */
    fun debrief(
        angle: CameraAngle,
        reps: Int,
        validReps: Int,
        assessed: Boolean,
        takeaway: String? = null,
        heldCue: CueId? = null,
    ): String {
        val view = "${angle.name} view done"
        val line = when {
            !assessed -> "$view. Some of your form couldn't be assessed from here."
            validReps <= 0 -> "$view, ${count(reps)} ${if (reps == 1) "rep" else "reps"}."
            takeaway == null && heldCue != null && validReps == reps ->
                "$view, ${count(validReps)} of ${count(reps)} in range, and your ${CoachPhrasebook.focusName(heldCue)} held the whole way."
            else -> "$view, ${count(validReps)} of ${count(reps)} in range."
        }
        return if (takeaway == null) line else "$line $takeaway"
    }

    /** The closing turn. A win is claimed only for a cue a stage recorded as resolved. */
    fun closing(
        plan: LiveCoachingPlan,
        totalReps: Int,
        validReps: Int,
        bestStreak: Int,
        notes: List<StageNote>,
        endedEarly: Boolean,
    ): List<String> = buildList {
        val name = plan.athleteName.takeIf { it.isNotEmpty() }?.let { ", $it" }.orEmpty()
        val carry = notes.lastOrNull { it.openCue != null }?.openCue
        if (totalReps <= 0) {
            add("No reps counted this time$name.")
            if (endedEarly) add("We stopped early, so this one is saved as incomplete.")
            return@buildList
        }
        add("That's ${count(totalReps)} ${if (totalReps == 1) "rep" else "reps"}$name.")
        if (endedEarly) {
            // Honest and short: no praise that was not earned.
            add("We stopped early, so this one is saved as incomplete.")
            carry?.let { add("Next time, keep working the ${CoachPhrasebook.focusName(it)}.") }
            return@buildList
        }
        if (validReps > 0) {
            val were = if (validReps == 1) "was" else "were"
            add(if (bestStreak >= 2) "${CoachPhrasebook.numberWord(validReps)} $were in range, with a best run of ${count(bestStreak)}."
                else "${CoachPhrasebook.numberWord(validReps)} $were in range.")
        }
        val resolved = notes.flatMap { it.resolvedCues }.firstOrNull { it != carry }
        resolved?.let { add("Your ${CoachPhrasebook.focusName(it)} came good.") }
        carry?.let { add("Next time, keep working the ${CoachPhrasebook.focusName(it)}.") }
        add("Let's look at your review.")
    }
}
