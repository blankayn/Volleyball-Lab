package com.volleyballlab.player.domain.model

import androidx.annotation.StringRes
import com.volleyballlab.player.R

/** `Plan_Status` in Table 16. */
enum class AssignmentStatus(@param:StringRes val displayName: Int) {
    Pending(R.string.assignment_pending),
    InProgress(R.string.assignment_in_progress),
    Completed(R.string.assignment_completed),
}

/**
 * A drill a coach has set for a player. `TrainingPlan`, Table 16, plus the fields
 * Figure 28's assignment form actually collects.
 *
 * ## Why the drill is described inline rather than referenced by id
 *
 * Table 16 has `Plan_DrillId` pointing at the Drill table, and Figure 28 has the
 * coach typing a name, a summary and step-by-step instructions into the form. Those
 * two are only compatible if creating an assignment also creates a Drill row, which
 * the document never says happens. Inlining the text matches the screen the
 * document draws and keeps a coach's wording attached to the assignment they wrote
 * it for - if the same coach edits the drill next week, last week's player still
 * sees the instructions they were actually given.
 *
 * [libraryDrillId] is kept for assignments picked out of the built-in drill library
 * rather than written from scratch, which is the `Plan_DrillId` case.
 *
 * @param targetSets how many sets of [targetReps]. The storyboard asks for both;
 *   [Drill] only carries reps because a self-directed athlete does one set at a
 *   time and a prescribed plan does not.
 * @param dueAtEpochMs `Plan_DueDate`. Nullable: "when you can" is a legitimate
 *   instruction, and forcing a date would make coaches invent one.
 */
data class CoachAssignment(
    val id: String,
    val coachUid: String,
    val playerUid: String,
    val title: String,
    val skill: Skill,
    val level: DrillLevel,
    val summary: String,
    val instructions: String,
    val targetSets: Int,
    val targetReps: Int,
    val dueAtEpochMs: Long?,
    val status: AssignmentStatus,
    val createdAtEpochMs: Long,
    val libraryDrillId: String? = null,
) {
    val totalReps: Int get() = targetSets * targetReps

    val isOpen: Boolean get() = status != AssignmentStatus.Completed

    /** True once the due date has passed and the work is not done. */
    fun isOverdue(nowEpochMs: Long): Boolean =
        isOpen && dueAtEpochMs != null && dueAtEpochMs < nowEpochMs

    companion object {
        const val MAX_TITLE_LENGTH = 60
        const val MAX_SUMMARY_LENGTH = 200
        const val MAX_INSTRUCTIONS_LENGTH = 1200

        /** Sane bounds for the two steppers on the assignment form. */
        val SETS_RANGE = 1..10
        val REPS_RANGE = 1..50

        const val DEFAULT_SETS = 3
        const val DEFAULT_REPS = 10
    }
}
