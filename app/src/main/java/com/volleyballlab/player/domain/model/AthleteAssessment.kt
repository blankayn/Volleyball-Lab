package com.volleyballlab.player.domain.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Names are saved and shared with ai-worker/src/athlete-assessment.mjs; never rename one. */
@Serializable
enum class MainGoal(val label: String) {
    LearnBasics("Learn the basics"),
    BeConsistent("Be more consistent"),
    MakeTheTeam("Make a school or club team"),
    CompeteBetter("Perform better in games"),
    StayActive("Stay active and enjoy playing"),
}

@Serializable
enum class WeakPoint(val label: String) {
    GettingReady("Getting ready in time"),
    KneeBend("Bending my knees"),
    ArmPosition("Arm and hand position"),
    Balance("Staying balanced"),
    MovingToBall("Moving to the ball"),
    Timing("Timing"),
    Direction("Sending the ball where I want"),
    Pressure("Playing well under pressure"),
}

/** What the athlete said about themselves. Nothing here is measured. */
@Serializable
data class AssessmentAnswers(
    val experience: ExperienceLevel,
    val position: CourtPosition? = null,
    val preferred: List<Skill> = emptyList(),
    val goal: MainGoal,
    val goalNote: String = "",
    val days: Int = 3,
    val minutes: Int = 30,
    val weakPoints: List<WeakPoint> = emptyList(),
    val weakNote: String = "",
    val statements: Map<String, CanDo> = emptyMap(),
    val savedAtEpochMs: Long = 0L,
    /** What happens, for the statements that are not there yet, by statement id. */
    val details: Map<String, List<String>> = emptyMap(),
) {
    /** The skills this assessment asked about. The others are unknown. */
    val assessed: List<Skill> get() = Skill.All.filter { SkillCheck.isAnswered(it, statements) }
    val isComplete: Boolean get() = days in 1..7 && minutes in MINUTES && preferred.isNotEmpty() && assessed.containsAll(preferred)

    fun detailsOf(statement: SkillStatement): List<StatementDetail> =
        if (!SkillCheck.asksDetails(statements[statement.id])) emptyList()
        else details[statement.id].orEmpty().mapNotNull { id -> statement.details.firstOrNull { it.id == id } }

    companion object {
        val MINUTES = listOf(15, 30, 45, 60, 90)
        const val MAX_NOTE = 160
    }
}

/** One thing the AI said, and the facts it came from. */
@Serializable
data class AssessmentPoint(val skill: Skill, val text: String, val evidence: List<String>)

@Serializable
data class PlannedDrill(val drillId: String, val sets: Int, val reps: Int, val why: String)

@Serializable
data class PlannedPractice(val title: String, val drills: List<PlannedDrill>)

@Serializable
data class PlanGoal(val skill: Skill, val text: String)

/** The AI's words. Every number shown beside them comes from the app's own data. */
@Serializable
data class AthleteAnalysis(
    val summary: String,
    val strengths: List<AssessmentPoint> = emptyList(),
    val weaknesses: List<AssessmentPoint> = emptyList(),
    val improvements: List<AssessmentPoint> = emptyList(),
    val focus: List<Skill> = emptyList(),
    val practices: List<PlannedPractice> = emptyList(),
    val weeklyGoals: List<PlanGoal> = emptyList(),
) {
    val drillIds: Set<String> get() = practices.flatMap { it.drills }.map { it.drillId }.toSet()
}

/** What the camera had measured when a plan was made. [score] is null before any scored session. */
@Serializable
data class MeasuredSkill(val skill: Skill, val score: Int?, val sessions: Int)

/** How a check was going when a plan was made, see [AdaptiveCoaching]. */
@Serializable
data class CheckTrend(val cueId: CueId, val trend: CueTrend)

/**
 * One assessment and weekly plan. [measured] and [checks] are the facts it was made from,
 * kept so every line can be traced, and [changes] says what moved since the last one.
 */
@Serializable
data class AthletePlan(
    val id: String,
    val createdAtEpochMs: Long,
    val analysis: AthleteAnalysis,
    val measured: List<MeasuredSkill> = emptyList(),
    val checks: List<CheckTrend> = emptyList(),
    val changes: List<String> = emptyList(),
)

/** The saved assessment of one athlete. */
data class AthleteState(val answers: AssessmentAnswers? = null, val plan: AthletePlan? = null) {
    /** Answered, but the AI has not written a plan for those answers yet. */
    val isWaiting: Boolean get() = answers != null && (plan == null || plan.createdAtEpochMs < answers.savedAtEpochMs)
}

/** A fact the AI may refer to. [lean] is 1 for the skill, -1 against it, 0 neither. */
data class AssessmentFact(val id: String, val skill: Skill?, val lean: Int, val cueId: CueId? = null, val statement: SkillStatement? = null,
    val answer: CanDo? = null, val measured: MeasuredSkill? = null, val trend: CueTrend? = null, val weakPoint: WeakPoint? = null,
    val details: List<StatementDetail> = emptyList())

/** The same facts, ids and leanings as the worker, so its answer can be checked again here. */
object AssessmentFacts {
    fun of(answers: AssessmentAnswers, measured: List<MeasuredSkill>, checks: List<CheckTrend>): Map<String, AssessmentFact> = buildList {
        SkillCheck.statements.forEach { statement ->
            val answer = answers.statements[statement.id] ?: return@forEach
            // Not having tried something is neither for nor against.
            add(AssessmentFact("say:${statement.id}", statement.skill, when (answer) { CanDo.Yes -> 1; CanDo.NotYet -> -1; else -> 0 },
                statement = statement, answer = answer, details = answers.detailsOf(statement)))
        }
        measured.forEach { item ->
            val score = item.score ?: return@forEach
            add(AssessmentFact("score:${item.skill.name}", item.skill, when { score >= PlanRules.HIGH -> 1; score < PlanRules.LOW -> -1; else -> 0 }, measured = item))
        }
        checks.forEach { check ->
            val skill = Skill.All.firstOrNull { skill -> FormCueCatalog.poseCues(skill).any { it.id == check.cueId } } ?: return@forEach
            val lean = when (check.trend) { CueTrend.Solid -> 1; CueTrend.Slipping, CueTrend.WorkOn -> -1; else -> 0 }
            add(AssessmentFact("check:${check.cueId.name}", skill, lean, cueId = check.cueId, trend = check.trend))
        }
        answers.weakPoints.forEach { add(AssessmentFact("weak:${it.name}", null, -1, weakPoint = it)) }
    }.associateBy { it.id }
}

object PlanRules {
    /** The app's existing lines between levels, see [SkillLevel]. */
    const val LOW = 60
    const val HIGH = 80
    /** A new plan needs this many newly scored sessions and this much time. */
    const val NEW_SESSIONS = 3
    const val MIN_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** Measured sessions decide the difficulty; until there are some, the stated experience does. */
    fun levelFor(experience: ExperienceLevel, score: Int?): DrillLevel = when {
        score != null -> when { score < LOW -> DrillLevel.Beginner; score < HIGH -> DrillLevel.Intermediate; else -> DrillLevel.Advanced }
        experience == ExperienceLevel.New -> DrillLevel.Beginner
        experience == ExperienceLevel.Developing -> DrillLevel.Intermediate
        else -> DrillLevel.Advanced
    }

    /** Drills the player can open at their level for each assessed skill, or the closest level there is, easier first. */
    fun drillsFor(answers: AssessmentAnswers, measured: List<MeasuredSkill>, open: List<Drill>): List<Drill> = answers.assessed.ifEmpty { Skill.All }.flatMap { skill ->
        val here = open.filter { it.skill == skill }
        val level = levelFor(answers.experience, measured.firstOrNull { it.skill == skill }?.score)
        val closest = here.minWithOrNull(compareBy<Drill>({ kotlin.math.abs(it.level.ordinal - level.ordinal) }, { it.level.ordinal }))?.level
        here.filter { it.level == closest }
    }

    fun drillMinutes(drill: Drill, sets: Int, reps: Int): Int =
        (drill.estimatedMinutes.toFloat() * sets * reps / drill.targetReps).roundToInt().coerceAtLeast(1)

    fun practiceMinutes(practice: PlannedPractice, drills: Map<String, Drill>): Int =
        practice.drills.sumOf { planned -> drills[planned.drillId]?.let { drillMinutes(it, planned.sets, planned.reps) } ?: 0 }

    fun measured(sessions: List<SessionResult>): List<MeasuredSkill> = Skill.All.map { skill ->
        val scored = sessions.count { it.skill == skill && it.formScore() != null }
        MeasuredSkill(skill, SkillRecommendation.recentScoreFor(skill, sessions), scored)
    }

    /** True when there is enough new performance to write a new plan. */
    fun isDue(state: AthleteState, sessions: List<SessionResult>, nowEpochMs: Long): Boolean {
        val plan = state.plan ?: return false
        if (state.isWaiting || nowEpochMs - plan.createdAtEpochMs < MIN_AGE_MS) return false
        return sessions.count { it.startedAtEpochMs > plan.createdAtEpochMs && it.formScore() != null } >= NEW_SESSIONS
    }

    /** What moved between two plans, from the facts only. The AI does not write these. */
    fun changes(before: AthletePlan?, after: AthleteAnalysis, measured: List<MeasuredSkill>, drills: Map<String, Drill>): List<String> {
        if (before == null) return emptyList()
        val moved = measured.mapNotNull { now ->
            val then = before.measured.firstOrNull { it.skill == now.skill }?.score
            when {
                now.score == null || now.score == then -> null
                then == null -> "${now.skill.name}: first camera score ${now.score}."
                else -> "${now.skill.name}: camera score ${then} to ${now.score}."
            }
        }
        val focus = if (after.focus != before.analysis.focus) listOf("Focus is now ${after.focus.joinToString(" and ") { it.name }}.") else emptyList()
        val added = (after.drillIds - before.analysis.drillIds).mapNotNull { drills[it]?.name }
        val dropped = (before.analysis.drillIds - after.drillIds).mapNotNull { drills[it]?.name }
        return (moved + focus + listOfNotNull(
            added.takeIf { it.isNotEmpty() }?.let { "Added ${it.joinToString(", ")}." },
            dropped.takeIf { it.isNotEmpty() }?.let { "Finished with ${it.joinToString(", ")}." },
        )).take(MAX_CHANGES)
    }

    /** Planned drills already done since [sinceEpochMs], by camera session or manual workout. */
    fun done(plan: AthletePlan, sessions: List<SessionResult>, workouts: List<DrillPracticeRecord>, sinceEpochMs: Long): Set<String> {
        val from = maxOf(sinceEpochMs, plan.createdAtEpochMs)
        return plan.analysis.drillIds.filterTo(mutableSetOf()) { id ->
            sessions.any { it.drillId == id && it.startedAtEpochMs >= from && it.repCount > 0 } || workouts.any { it.drillId == id && it.finishedAtEpochMs >= from }
        }
    }

    private const val MAX_CHANGES = 6
}
