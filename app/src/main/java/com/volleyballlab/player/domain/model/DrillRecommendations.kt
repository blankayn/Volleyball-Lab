package com.volleyballlab.player.domain.model

/** Why a drill was put in front of the athlete. The library shows this beside it. */
sealed interface DrillPickReason {
    /** The drill for their focus skill that suits the level they are at now. */
    data class Focus(val skill: Skill, val level: SkillLevel, val measured: Boolean) : DrillPickReason

    /** The next drill up in the focus skill, for when [after] starts to feel easy. */
    data class NextStep(val after: Drill) : DrillPickReason

    /** Another relevant skill the athlete has not trained at all yet. */
    data class Untrained(val skill: Skill) : DrillPickReason

    /** Another relevant skill, the one they have trained least so far. */
    data class LeastTrained(val skill: Skill) : DrillPickReason
}

data class DrillPick(val drill: Drill, val reason: DrillPickReason)

/**
 * Which library drills to suggest first.
 *
 * ## Built on the dashboard's focus, not a second opinion
 *
 * [SkillRecommendation] already decides the athlete's focus skill (their saved
 * goal, else their weakest self-rated skill) and their level at it (measured form
 * score once they have one, else their own rating). This reuses that decision, so
 * the library and Home never disagree about what to work on. When there is no
 * focus yet - the questionnaire was skipped - the least-trained skill stands in.
 *
 * ## Only drills they can open
 *
 * Every pick passes `canStart`. A Free account sees starters only; a suggestion
 * that opens a paywall is an advert, not a recommendation.
 *
 * ## What counts as training
 *
 * Both camera sessions and manual workouts. This only ranks which skill has had
 * the least attention; it never reads a score from a manual workout, which has none.
 */
object DrillRecommendations {
    const val MAX_PICKS = 3

    fun pick(
        drills: List<Drill>,
        canStart: (Drill) -> Boolean,
        focus: SkillRecommendation?,
        position: CourtPosition?,
        cameraSessions: List<SessionResult>,
        manualWorkouts: List<DrillPracticeRecord>,
    ): List<DrillPick> {
        val open = drills.filter(canStart)
        if (open.isEmpty()) return emptyList()
        val drillCounts = (cameraSessions.map { it.drillId } + manualWorkouts.map { it.drillId })
            .groupingBy { it }.eachCount()
        val skillCounts = (cameraSessions.map { it.skill } + manualWorkouts.map { it.skill })
            .groupingBy { it }.eachCount()
        val relevant = Skill.All.filter { position == null || it in position.relevantSkills }.ifEmpty { Skill.All }
        val ladder = compareBy<Drill>({ it.level.ordinal }, { drillCounts[it.id] ?: 0 })

        val picks = mutableListOf<DrillPick>()
        val focusSkill = focus?.skill ?: relevant.minBy { skillCounts[it] ?: 0 }
        val focusLevel = focus?.level ?: SkillLevel.Beginner
        val focusDrills = open.filter { it.skill == focusSkill }.sortedWith(ladder)
        // The highest open level at or below theirs (the lowest open level if every
        // drill is above it), and within that level the drill they have done least.
        val mainLevel = focusDrills.map { it.level.ordinal }.filter { it <= focusLevel.ordinal }.maxOrNull()
            ?: focusDrills.firstOrNull()?.level?.ordinal
        val main = focusDrills.firstOrNull { it.level.ordinal == mainLevel }
        if (main != null) {
            // Without a focus the lead skill was chosen for being least trained, so
            // it says that rather than claiming to be their focus.
            picks += DrillPick(main, when {
                focus != null -> DrillPickReason.Focus(focusSkill, focusLevel, focus.isMeasured)
                (skillCounts[focusSkill] ?: 0) == 0 -> DrillPickReason.Untrained(focusSkill)
                else -> DrillPickReason.LeastTrained(focusSkill)
            })
            focusDrills.firstOrNull { it.level.ordinal > main.level.ordinal }
                ?.let { picks += DrillPick(it, DrillPickReason.NextStep(main)) }
        }

        // Fill the rest from the other relevant skills, least trained first. Ties
        // keep the capstone's skill order, so the list is stable between visits.
        relevant.filter { it != focusSkill }
            .sortedBy { skillCounts[it] ?: 0 }
            .forEach { skill ->
                if (picks.size >= MAX_PICKS) return@forEach
                val drill = open.filter { it.skill == skill }.minWithOrNull(ladder) ?: return@forEach
                val reason = if ((skillCounts[skill] ?: 0) == 0) DrillPickReason.Untrained(skill)
                    else DrillPickReason.LeastTrained(skill)
                picks += DrillPick(drill, reason)
            }
        return picks.take(MAX_PICKS)
    }
}
