package com.volleyballlab.player.feature.home

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.R
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.domain.model.AthleteProfile
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.data.assessment.AthleteAssessmentRepository
import com.volleyballlab.player.data.assessment.AthletePlanner
import com.volleyballlab.player.data.assessment.PlannerStatus
import com.volleyballlab.player.domain.model.AthleteState
import com.volleyballlab.player.domain.model.Drill
import com.volleyballlab.player.domain.model.HomeAssignment
import com.volleyballlab.player.domain.model.PlanRules
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.repository.CoachRepository
import com.volleyballlab.player.domain.repository.DrillPracticeRepository
import com.volleyballlab.player.domain.repository.DrillRepository
import com.volleyballlab.player.domain.repository.ProfileRepository
import com.volleyballlab.player.domain.repository.SessionRepository
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The slice of training history the Daily progress row is reporting on.
 *
 * ## Why the week starts where the phone says it does
 *
 * `WeekFields.of(Locale.getDefault()).firstDayOfWeek` rather than a hardcoded
 * Monday. In the Philippines, where this app is being built and defended, the week
 * conventionally starts on Sunday; hardcoding Monday would put Sunday's training in
 * "last week" for every user who does not think of it that way, and the number would
 * silently disagree with the athlete's own sense of their week.
 */
enum class TrainingPeriod(@param:StringRes val label: Int) {
    ThisWeek(R.string.home_period_week),
    ThisMonth(R.string.home_period_month),
    AllTime(R.string.home_period_all);

    /**
     * @param zone injected rather than read inline so the boundary maths is
     *   testable without depending on where the test machine happens to be.
     */
    fun filter(
        sessions: List<SessionResult>,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): List<SessionResult> {
        val start = startDate(today) ?: return sessions
        val cutoff = start.atStartOfDay(zone).toInstant().toEpochMilli()
        return sessions.filter { it.startedAtEpochMs >= cutoff }
    }

    private fun startDate(today: LocalDate): LocalDate? = when (this) {
        ThisWeek -> today.with(
            TemporalAdjusters.previousOrSame(WeekFields.of(Locale.getDefault()).firstDayOfWeek),
        )
        ThisMonth -> today.withDayOfMonth(1)
        AllTime -> null
    }
}

/**
 * Everything the dashboard draws, from three sources at once.
 *
 * @param athlete the saved assessment and plan, or null while it is looked up. With no
 *   answers the dashboard offers the assessment rather than inventing advice.
 * @param done planned drills already done this week.
 * @param assignments the coach's open assignments and how far each has got.
 */
data class HomeUiState(
    val profile: AthleteProfile = AthleteProfile.Anonymous,
    val sessions: List<SessionResult> = emptyList(),
    val athlete: AthleteState? = null,
    val planner: PlannerStatus = PlannerStatus.Idle,
    val drills: Map<String, Drill> = emptyMap(),
    val done: Set<String> = emptySet(),
    val assignments: List<HomeAssignment> = emptyList(),
    val loaded: Boolean = false,
)

class HomeViewModel(
    profileRepository: ProfileRepository,
    sessionRepository: SessionRepository,
    coachRepository: CoachRepository,
    athleteRepository: AthleteAssessmentRepository,
    workouts: DrillPracticeRepository,
    drillRepository: DrillRepository,
    private val planner: AthletePlanner,
) : ViewModel() {

    private val drills = MutableStateFlow<Map<String, Drill>>(emptyMap())

    init {
        viewModelScope.launch { drills.value = Skill.All.flatMap { drillRepository.drillsFor(it) }.associateBy { it.id } }
        // Writes a plan that is waiting, or a new one when there is enough new performance.
        viewModelScope.launch {
            try { planner.refreshIfDue() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
        }
    }

    val uiState: StateFlow<HomeUiState> = combine(
        profileRepository.profile,
        sessionRepository.sessions,
        combine(coachRepository.assignmentsForMe, coachRepository.mySubmissions, ::Pair),
        combine(athleteRepository.state, planner.status, ::Pair),
        combine(workouts.history, drills, ::Pair),
    ) { profile, sessions, (assignments, submissions), (athlete, status), (history, drills) ->
        HomeUiState(
            profile = profile,
            sessions = sessions,
            athlete = athlete,
            planner = status,
            drills = drills,
            done = athlete?.plan?.let { PlanRules.done(it, sessions, history, weekStart()) }.orEmpty(),
            assignments = HomeAssignment.of(assignments, sessions, submissions, System.currentTimeMillis()),
            loaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    private fun weekStart(zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(WeekFields.of(Locale.getDefault()).firstDayOfWeek))
            .atStartOfDay(zone).toInstant().toEpochMilli()

    fun retryPlan() { viewModelScope.launch { planner.write() } }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    profileRepository = appContainer.profileRepository,
                    sessionRepository = appContainer.sessionRepository,
                    coachRepository = appContainer.coachRepository,
                    athleteRepository = appContainer.athleteAssessmentRepository,
                    workouts = appContainer.drillPracticeRepository,
                    drillRepository = appContainer.drillRepository,
                    planner = appContainer.athletePlanner,
                )
            }
        }
    }
}
