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
import com.volleyballlab.player.domain.model.SkillRecommendation
import com.volleyballlab.player.domain.repository.AssessmentRepository
import com.volleyballlab.player.domain.repository.ProfileRepository
import com.volleyballlab.player.domain.repository.SessionRepository
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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
 * @param recommendation null when the athlete has rated nothing - they skipped
 *   onboarding. The dashboard offers the questionnaire rather than inventing advice,
 *   which is the same rule the Coach tab follows when there are no sessions to
 *   reason about.
 */
data class HomeUiState(
    val profile: AthleteProfile = AthleteProfile.Anonymous,
    val sessions: List<SessionResult> = emptyList(),
    val recommendation: SkillRecommendation? = null,
    val loaded: Boolean = false,
)

class HomeViewModel(
    profileRepository: ProfileRepository,
    sessionRepository: SessionRepository,
    assessmentRepository: AssessmentRepository,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        profileRepository.profile,
        sessionRepository.sessions,
        assessmentRepository.assessment,
    ) { profile, sessions, assessment ->
        HomeUiState(
            profile = profile,
            sessions = sessions,
            recommendation = SkillRecommendation.from(assessment, profile, sessions),
            loaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    profileRepository = appContainer.profileRepository,
                    sessionRepository = appContainer.sessionRepository,
                    assessmentRepository = appContainer.assessmentRepository,
                )
            }
        }
    }
}
