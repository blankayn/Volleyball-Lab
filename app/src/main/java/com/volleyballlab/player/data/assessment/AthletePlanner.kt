package com.volleyballlab.player.data.assessment

import com.volleyballlab.player.data.ai.AnalystResult
import com.volleyballlab.player.data.ai.AssessmentRequest
import com.volleyballlab.player.data.ai.AthleteAnalyst
import com.volleyballlab.player.domain.model.AdaptiveCoaching
import com.volleyballlab.player.domain.model.AdaptiveMemory
import com.volleyballlab.player.domain.model.AssessmentAnswers
import com.volleyballlab.player.domain.model.AthletePlan
import com.volleyballlab.player.domain.model.CheckTrend
import com.volleyballlab.player.domain.model.CueTrend
import com.volleyballlab.player.domain.model.Drill
import com.volleyballlab.player.domain.model.DrillPracticeRecord
import com.volleyballlab.player.domain.model.PlanRules
import com.volleyballlab.player.domain.model.RepMark
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

enum class PlannerStatus { Idle, Working, Unavailable, Limited }

/** Where the planner reads an athlete's performance from. */
interface PlannerSources {
    val uid: String?
    suspend fun sessions(): List<SessionResult>
    suspend fun memory(): AdaptiveMemory
    suspend fun marks(): List<RepMark>
    suspend fun workouts(): List<DrillPracticeRecord>
    /** Drills this player can start. */
    suspend fun openDrills(): List<Drill>
    /** The plan's first focus, for the screens that show one skill. */
    suspend fun onPlanned(uid: String, focus: List<Skill>)
}

/**
 * Writes an athlete's plan, first from their answers and later from what was measured.
 *
 * Nothing is made up while the AI is away: the answers stay saved and this is tried again
 * the next time the app opens, or when the athlete asks.
 */
class AthletePlanner(
    private val repository: AthleteAssessmentRepository,
    private val analyst: AthleteAnalyst,
    private val sources: PlannerSources,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val running = Mutex()
    private val _status = MutableStateFlow(PlannerStatus.Idle)
    val status: StateFlow<PlannerStatus> = _status.asStateFlow()
    private var lastTry: Pair<String, Long>? = null

    /** Called when the app opens. Quiet, and not more than once in [RETRY_MS] after a failure. */
    suspend fun refreshIfDue() {
        val uid = sources.uid ?: return
        if (lastTry?.let { it.first == uid && now() - it.second < RETRY_MS } == true) return
        val state = withTimeoutOrNull(LOOKUP_MS) { repository.state.filterNotNull().first() } ?: return
        if (state.isWaiting || PlanRules.isDue(state, sources.sessions(), now())) write()
    }

    /** Writes a plan now. Returns false when there is none to show yet; [status] says why. */
    suspend fun write(answers: AssessmentAnswers? = null): Boolean {
        val uid = sources.uid ?: return false
        if (!running.tryLock()) return false
        _status.value = PlannerStatus.Working
        var result = PlannerStatus.Unavailable
        try {
            lastTry = uid to now()
            val state = withTimeoutOrNull(LOOKUP_MS) { repository.state.filterNotNull().first() }
            val given = answers ?: state?.answers ?: return false
            val sessions = sources.sessions()
            val open = sources.openDrills()
            val measured = PlanRules.measured(sessions)
            val marks = sources.marks()
            val memory = sources.memory()
            val checks = Skill.All.flatMap { AdaptiveCoaching.learn(it, sessions, memory, marks) }
                .filter { it.trend != CueTrend.Learning }.map { CheckTrend(it.cue.id, it.trend) }
            val drills = PlanRules.drillsFor(given, measured, open)
            if (drills.isEmpty()) return false
            val previous = state?.plan
            val since = previous?.createdAtEpochMs ?: 0L
            val finished = (sessions.filter { it.startedAtEpochMs >= since && it.repCount > 0 }.map { it.drillId } +
                sources.workouts().filter { it.finishedAtEpochMs >= since }.map { it.drillId })
                .filter { id -> open.any { it.id == id } }.groupingBy { it }.eachCount()
            when (val answer = analyst.analyse(AssessmentRequest(given, measured, checks, drills, finished,
                marks.count { it.right }, marks.count { !it.right }, previous?.analysis?.focus.orEmpty()))) {
                is AnalystResult.Unavailable -> result = if (answer.limited) PlannerStatus.Limited else PlannerStatus.Unavailable
                is AnalystResult.Ready -> {
                    if (sources.uid != uid) return false
                    val plan = AthletePlan(UUID.randomUUID().toString(), now(), answer.analysis, measured, checks,
                        PlanRules.changes(previous, answer.analysis, measured, open.associateBy { it.id }))
                    repository.savePlan(uid, given, plan)
                    sources.onPlanned(uid, answer.analysis.focus)
                    lastTry = null
                    result = PlannerStatus.Idle
                    return true
                }
            }
            return false
        } catch (_: TimeoutCancellationException) {
            return false
        } catch (cancelled: CancellationException) {
            result = PlannerStatus.Idle
            throw cancelled
        } catch (_: Exception) {
            return false
        } finally {
            _status.value = result
            running.unlock()
        }
    }

    private companion object {
        const val RETRY_MS = 6L * 60 * 60 * 1000
        const val LOOKUP_MS = 6_000L
    }
}
