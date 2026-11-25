package com.volleyballlab.player.data.ai

import com.google.firebase.functions.FirebaseFunctions
import com.volleyballlab.player.BuildConfig
import com.volleyballlab.player.data.cloud.awaitCloud
import com.volleyballlab.player.domain.model.*
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withTimeout

/** Everything one assessment is made from. */
data class AssessmentRequest(
    val answers: AssessmentAnswers,
    val measured: List<MeasuredSkill>,
    val checks: List<CheckTrend>,
    val drills: List<Drill>,
    val finished: Map<String, Int> = emptyMap(),
    val coachRight: Int = 0,
    val coachWrong: Int = 0,
    val previousFocus: List<Skill> = emptyList(),
)

sealed interface AnalystResult {
    data class Ready(val analysis: AthleteAnalysis) : AnalystResult
    /** Nothing was made up. [limited] when today's AI requests are used up. */
    data class Unavailable(val limited: Boolean) : AnalystResult
}

fun interface AthleteAnalyst { suspend fun analyse(request: AssessmentRequest): AnalystResult }

/** Answers and measured results go out without a name, account, video or team. */
class CloudAthleteAnalyst : AthleteAnalyst {
    override suspend fun analyse(request: AssessmentRequest): AnalystResult = withTimeout(30_000) {
        val endpoint = URI(BuildConfig.SESSION_AI_URL.takeIf { it.isNotBlank() } ?: error("AI unavailable")).resolve("/assessAthlete").toURL()
        val callable = FirebaseFunctions.getInstance("asia-southeast1").getHttpsCallableFromUrl(endpoint)
        callable.setTimeout(30, TimeUnit.SECONDS)
        AssessmentWire.decode(callable.call(AssessmentWire.fields(request)).awaitCloud().data, request)
    }
}

internal object AssessmentWire {
    fun fields(request: AssessmentRequest): Map<String, Any?> = mapOf(
        "version" to 1,
        "profile" to mapOf(
            "experience" to request.answers.experience.name, "position" to request.answers.position?.name,
            "preferred" to request.answers.preferred.map { it.name }, "goal" to request.answers.goal.name,
            "goalNote" to note(request.answers.goalNote), "days" to request.answers.days, "minutes" to request.answers.minutes,
            "weakPoints" to request.answers.weakPoints.map { it.name }, "weakNote" to note(request.answers.weakNote),
        ),
        // Only the skills this athlete chose to be assessed on.
        "statements" to request.answers.assessed.flatMap(SkillCheck::forSkill).associate { it.id to request.answers.statements.getValue(it.id).name },
        "details" to request.answers.assessed.flatMap(SkillCheck::forSkill).associate { it.id to request.answers.detailsOf(it).map { detail -> detail.id } }.filterValues { it.isNotEmpty() },
        "measured" to request.measured.map { mapOf("skill" to it.skill.name, "score" to it.score, "sessions" to if (it.score == null) 0 else it.sessions.coerceIn(1, 999)) },
        "checks" to request.checks.filter { it.trend != CueTrend.Learning }.map { mapOf("cueId" to it.cueId.name, "trend" to it.trend.name) },
        "drills" to request.drills.map { it.id },
        "finished" to request.finished.map { (id, count) -> mapOf("drillId" to id, "count" to count.coerceIn(1, 999)) },
        "marks" to mapOf("right" to request.coachRight.coerceIn(0, 999), "wrong" to request.coachWrong.coerceIn(0, 999)),
        "previousFocus" to request.previousFocus.map { it.name },
    )

    private fun note(value: String) = value.filter { it == '\n' || !it.isISOControl() }.trim().take(AssessmentAnswers.MAX_NOTE)

    /** The worker already checked this. It is checked again so nothing unsupported can reach the athlete. */
    fun decode(value: Any?, request: AssessmentRequest): AnalystResult {
        val row = requireNotNull(value as? Map<*, *>)
        if (row["status"] == "unavailable") return AnalystResult.Unavailable(row["reason"] == "limit")
        require(row["status"] == "ready" && row["source"] == "groq")
        val data = requireNotNull(row["analysis"] as? Map<*, *>)
        val facts = AssessmentFacts.of(request.answers, request.measured, request.checks)
        val strengths = points(data["strengths"], facts, { it > 0 }, { it < 0 })
        val weaknesses = points(data["weaknesses"], facts, { it < 0 }, { it > 0 })
        val improvements = points(data["improvements"], facts, { it <= 0 }, { false })
        require(strengths.isNotEmpty() || weaknesses.isNotEmpty() || improvements.isNotEmpty())
        val offered = request.drills.associateBy { it.id }
        val practices = (data["practices"] as? List<*>).orEmpty().mapNotNull { practice(it, offered, request.answers.minutes) }
            .take(minOf(MAX_PRACTICES, request.answers.days))
        require(practices.isNotEmpty())
        val focus = (data["focus"] as? List<*>).orEmpty().mapNotNull(::skill).distinct().take(2)
        require(focus.isNotEmpty())
        val goals = (data["weeklyGoals"] as? List<*>).orEmpty().mapNotNull { item ->
            (item as? Map<*, *>)?.let { goal -> skill(goal["skill"])?.let { skill -> text(goal["text"], 140)?.let { PlanGoal(skill, it) } } }
        }.take(3)
        return AnalystResult.Ready(AthleteAnalysis(requireNotNull(text(data["summary"], 420)), strengths, weaknesses, improvements, focus, practices, goals))
    }

    private fun points(value: Any?, facts: Map<String, AssessmentFact>, supports: (Int) -> Boolean, forbids: (Int) -> Boolean): List<AssessmentPoint> =
        (value as? List<*>).orEmpty().mapNotNull { item ->
            val entry = item as? Map<*, *> ?: return@mapNotNull null
            val skill = skill(entry["skill"]) ?: return@mapNotNull null
            val text = text(entry["text"], 200) ?: return@mapNotNull null
            val ids = (entry["evidence"] as? List<*>).orEmpty().filterIsInstance<String>().distinct().take(4)
            val used = ids.map { facts[it] ?: return@mapNotNull null }
            if (used.none { it.skill == skill } || used.any { it.skill != null && it.skill != skill }) return@mapNotNull null
            if (used.none { supports(it.lean) } || used.any { forbids(it.lean) }) return@mapNotNull null
            AssessmentPoint(skill, text, ids)
        }.distinctBy { it.skill }.take(3)

    private fun practice(value: Any?, offered: Map<String, Drill>, minutes: Int): PlannedPractice? {
        val entry = value as? Map<*, *> ?: return null
        val title = text(entry["title"], 60) ?: return null
        var left = minutes
        val drills = (entry["drills"] as? List<*>).orEmpty().mapNotNull { item ->
            val row = item as? Map<*, *> ?: return@mapNotNull null
            val drill = offered[row["drillId"]] ?: return@mapNotNull null
            val sets = (row["sets"] as? Number)?.toInt()?.takeIf { it in 1..6 } ?: return@mapNotNull null
            val reps = (row["reps"] as? Number)?.toInt()?.takeIf { it in 3..30 } ?: return@mapNotNull null
            val why = text(row["why"], 160) ?: return@mapNotNull null
            val needed = PlanRules.drillMinutes(drill, sets, reps)
            if (needed > left) return@mapNotNull null
            left -= needed
            PlannedDrill(drill.id, sets, reps, why)
        }.distinctBy { it.drillId }
        return PlannedPractice(title, drills).takeIf { drills.isNotEmpty() }
    }

    private fun skill(value: Any?): Skill? = Skill.All.firstOrNull { it.name == value }

    /** Numbers come from the app, so AI text with one is refused, as is anything that looks like markup or a link. */
    private fun text(value: Any?, max: Int): String? = (value as? String)?.trim()?.replace(Regex("\\s+"), " ")
        ?.takeIf { it.isNotEmpty() && it.length <= max && it.none { c -> c.isDigit() || c.isISOControl() || c in "<>[]{}@" } && !it.contains("http", ignoreCase = true) }

    private const val MAX_PRACTICES = 3
}
