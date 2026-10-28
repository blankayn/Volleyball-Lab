package com.volleyballlab.player.feature.train.skill

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.repository.SessionRepository
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Form progress for one skill, from camera-scored sessions only. */
data class SkillFormProgress(
    val sessionCount: Int,
    val meanScore: Int?,
    val lastPracticedEpochMs: Long?,
)

data class SkillProgressUiState(
    val loaded: Boolean = false,
    val bySkill: Map<Skill, SkillFormProgress> = emptyMap(),
)

class SkillSelectViewModel(sessionRepository: SessionRepository) : ViewModel() {

    val progress: StateFlow<SkillProgressUiState> = sessionRepository.sessions
        .map { sessions -> SkillProgressUiState(loaded = true, bySkill = sessions.bySkill()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SkillProgressUiState())

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { SkillSelectViewModel(appContainer.sessionRepository) }
        }
    }
}

private fun List<SessionResult>.bySkill(): Map<Skill, SkillFormProgress> =
    groupBy { it.skill }.mapValues { (_, sessions) ->
        val scores = sessions.mapNotNull { it.formScore() }
        SkillFormProgress(
            sessionCount = sessions.size,
            meanScore = scores.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            lastPracticedEpochMs = sessions.maxOfOrNull { it.startedAtEpochMs },
        )
    }
