package com.volleyballlab.player.domain.model

/** What the player should see about an assignment at a glance. */
enum class HomeAssignmentState { New, InProgress, Overdue, Sent }

/**
 * An open coach assignment on the dashboard, with how far it has got.
 *
 * @param repsRecorded counted reps in the sessions recorded for it, never above the target.
 */
data class HomeAssignment(val assignment: CoachAssignment, val state: HomeAssignmentState, val repsRecorded: Int) {
    val progress: Float get() = if (assignment.totalReps <= 0) 0f else repsRecorded / assignment.totalReps.toFloat()
    val started: Boolean get() = repsRecorded > 0 || assignment.status == AssignmentStatus.InProgress

    companion object {
        /** Overdue first, then the nearest due date, then the newest; work waiting for the coach goes last. */
        fun of(
            assignments: List<CoachAssignment>,
            sessions: List<SessionResult>,
            submissions: List<SessionSubmission>,
            nowEpochMs: Long,
        ): List<HomeAssignment> = assignments.filter { it.isOpen }.map { assignment ->
            val recorded = sessions.filter { it.assignmentId == assignment.id }
            val ids = recorded.mapTo(mutableSetOf()) { it.id }
            val waiting = submissions.any { it.isPending && it.sessionId in ids && it.coachUid == assignment.coachUid }
            HomeAssignment(assignment, when {
                waiting -> HomeAssignmentState.Sent
                assignment.isOverdue(nowEpochMs) -> HomeAssignmentState.Overdue
                recorded.isNotEmpty() || assignment.status == AssignmentStatus.InProgress -> HomeAssignmentState.InProgress
                else -> HomeAssignmentState.New
            }, recorded.sumOf { it.repCount }.coerceAtMost(assignment.totalReps.coerceAtLeast(0)))
        }.sortedWith(compareBy<HomeAssignment> { it.state == HomeAssignmentState.Sent }
            .thenBy { it.state != HomeAssignmentState.Overdue }
            .thenBy { it.assignment.dueAtEpochMs ?: Long.MAX_VALUE }
            .thenByDescending { it.assignment.createdAtEpochMs })
    }
}
