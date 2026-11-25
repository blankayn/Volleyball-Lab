package com.volleyballlab.player.domain.model

enum class NextStepReason {
    /** One check in this skill needs work. */
    WorkOn,
    /** This skill is going well, so a different drill of it. */
    MoveUp,
    /** Nothing stands out: the same again. */
    Repeat,
    /** Still low after several sessions: give another skill a turn. */
    OtherSkill,
}

/** The one thing to do after a session. [drill] is null when the player should stay on the drill they just did. */
data class NextStep(val reason: NextStepReason, val skill: Skill, val cue: FormCue? = null, val drill: Drill? = null)

object NextStepAdvisor {
    /** The same line the app already uses below "intermediate", see [SkillLevel]. */
    const val LOW = 60
    const val GOOD = 80
    /** Sessions in a row that must be low before another skill is suggested. */
    const val LOW_SESSIONS = 3
    /** A gain this big over those sessions means it is working: stay. */
    const val GAIN = 10

    /**
     * @param history the player's sessions; later ones than [session] are ignored, so an
     *   old result keeps its own suggestion.
     * @param evidence all five skills, see [SkillEvidence].
     * @param open drills the player can start, of any skill.
     */
    fun suggest(
        session: SessionResult,
        history: List<SessionResult>,
        assessment: CoachAssessment,
        learned: List<LearnedCue>,
        evidence: List<SkillEvidence>,
        open: List<Drill>,
    ): NextStep? {
        if (session.repCount == 0) return null
        val upTo = (history.filter { it.id != session.id } + session).filter { it.startedAtEpochMs <= session.startedAtEpochMs }
        val counts = upTo.groupingBy { it.drillId }.eachCount()
        val easiestLeastDone = compareBy<Drill>({ counts[it.id] ?: 0 }, { it.level.ordinal })

        if (stillLow(session, upTo)) {
            val other = otherSkill(session.skill, upTo, evidence)
            return NextStep(NextStepReason.OtherSkill, other, drill = open.filter { it.skill == other }
                .minWithOrNull(compareBy<Drill>({ it.level.ordinal }, { counts[it.id] ?: 0 })))
        }
        val here = open.filter { it.skill == session.skill }
        val cue = assessment.corrections.firstOrNull()?.evidence?.cue ?: AdaptiveCoaching.needs(learned).firstOrNull()?.cue
        if (cue != null) {
            val fits = here.filter { cue.id in DrillFocus.cues(it.id) }
            // Staying on a drill that already trains it needs no change of drill.
            val drill = if (fits.any { it.id == session.drillId }) null else fits.minWithOrNull(easiestLeastDone)
            return NextStep(NextStepReason.WorkOn, session.skill, cue, drill)
        }
        if ((assessment.overallScore ?: 0) >= GOOD) here.filter { it.id != session.drillId }.minWithOrNull(easiestLeastDone)
            ?.let { return NextStep(NextStepReason.MoveUp, session.skill, drill = it) }
        return NextStep(NextStepReason.Repeat, session.skill)
    }

    private fun stillLow(session: SessionResult, upTo: List<SessionResult>): Boolean {
        val scores = upTo.filter { it.skill == session.skill }.sortedBy { it.startedAtEpochMs }
            .mapNotNull { CoachAssessmentEngine.assess(it).overallScore }.takeLast(LOW_SESSIONS)
        return scores.size == LOW_SESSIONS && scores.all { it < LOW } && scores.last() - scores.first() < GAIN &&
            CoachAssessmentEngine.assess(session).overallScore != null
    }

    /** The other skill with most to gain, then an untried one, then the least practised. */
    private fun otherSkill(skill: Skill, upTo: List<SessionResult>, evidence: List<SkillEvidence>): Skill =
        NextSkills.suggest(evidence.filter { it.skill != skill }).items.firstOrNull()?.skill
            ?: Skill.All.filter { it != skill }.minWith(compareBy<Skill> { other -> upTo.count { it.skill == other } }
                .thenBy { Math.floorMod(it.ordinal - upTo.size, Skill.All.size) })
}
