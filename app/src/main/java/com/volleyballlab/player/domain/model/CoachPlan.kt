package com.volleyballlab.player.domain.model

/**
 * What a coach gets without paying. Everyday coaching stays free: reviewing sessions,
 * written feedback, drills and chat. The limits are the same on the website, and
 * firestore.rules enforces the student limit, feedback photos and rep marks.
 */
object CoachPlan {
    const val FREE_STUDENTS = 3

    fun canAccept(plan: UserPlan, activeStudents: Int): Boolean = plan.isPro || activeStudents < FREE_STUDENTS

    /**
     * The players holding a place after a change, for `coachSlots/{coach}`.
     *
     * @param held who holds a place now, or null before the first one was recorded.
     * @param active who is on the team after the change.
     * @param accepting true when this change accepts someone. Only then are players who
     *   joined before places were recorded added, so ending a connection never needs a
     *   free place.
     */
    fun slots(held: List<String>?, active: Set<String>, accepting: Boolean): List<String>? {
        if (held == null) return if (accepting) active.toList() else null
        val leaving = held.filter { it !in active }.take(MAX_LEAVING).toSet()
        val kept = held.filter { it !in leaving }
        return (if (accepting) kept + active else kept).distinct()
    }

    /** firestore.rules checks this many departures in one write. */
    private const val MAX_LEAVING = 5
}
