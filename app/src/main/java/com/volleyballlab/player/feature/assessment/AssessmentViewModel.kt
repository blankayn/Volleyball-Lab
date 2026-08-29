package com.volleyballlab.player.feature.assessment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.domain.model.CourtPosition
import com.volleyballlab.player.domain.model.DominantHand
import com.volleyballlab.player.domain.model.ExperienceLevel
import com.volleyballlab.player.domain.model.SelfRating
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.model.SkillAssessment
import com.volleyballlab.player.domain.model.relevantSkills
import com.volleyballlab.player.domain.repository.AssessmentRepository
import com.volleyballlab.player.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The three steps of onboarding.
 *
 * An enum rather than an Int so that the screen cannot render a fourth step that
 * does not exist, and so [AssessmentUiState.progress] has something real to count.
 */
enum class AssessmentStep {
    Position,
    Experience,
    Ratings;

    companion object {
        val All: List<AssessmentStep> = entries
    }
}

data class AssessmentUiState(
    val step: AssessmentStep = AssessmentStep.Position,
    val position: CourtPosition? = null,
    val hand: DominantHand? = null,
    val experience: ExperienceLevel? = null,
    val ratings: Map<Skill, SelfRating> = emptyMap(),
    val saving: Boolean = false,
) {
    val progress: Float
        get() = (step.ordinal + 1).toFloat() / AssessmentStep.All.size

    /**
     * The skills this athlete is actually asked about.
     *
     * Narrowed by position, so a libero answers three questions rather than five and
     * is never asked to rate a skill the rules do not let them perform. With no
     * position given there is nothing to narrow by, so all five are asked.
     */
    val skillsToRate: List<Skill>
        get() = position?.let { pos -> Skill.All.filter { it in pos.relevantSkills } } ?: Skill.All

    val allRated: Boolean get() = skillsToRate.all { it in ratings }

    val canAdvance: Boolean
        get() = when (step) {
            // Position drives which skills get asked about and is the one answer
            // the recommendation genuinely needs, so it is the only required field.
            // Dominant hand is collected here because it is the natural moment to
            // ask, not because anything is waiting on it.
            AssessmentStep.Position -> position != null
            AssessmentStep.Experience -> experience != null
            AssessmentStep.Ratings -> allRated && !saving
        }

    val isLastStep: Boolean get() = step == AssessmentStep.Ratings
}

/**
 * Drives the onboarding questionnaire.
 *
 * Answers live here until the athlete finishes, then land in two places at once:
 * position and hand belong to [ProfileRepository] because that is where the rest of
 * the app already looks for them, and the self-ratings belong to
 * [AssessmentRepository]. Writing position into the assessment as well would give
 * the app two copies of one fact and a way for them to disagree.
 */
class AssessmentViewModel(
    private val assessmentRepository: AssessmentRepository,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AssessmentUiState())
    val state: StateFlow<AssessmentUiState> = _state.asStateFlow()

    init {
        // Prefills from whatever is already known, so retaking the assessment starts
        // from the athlete's current answers rather than from blank.
        viewModelScope.launch {
            val profile = profileRepository.profile.first()
            val existing = assessmentRepository.assessment.first()
            _state.update {
                it.copy(
                    position = profile.position,
                    hand = profile.dominantHand,
                    experience = existing.experience,
                    ratings = existing.ratings,
                )
            }
        }
    }

    /** Tapping the selected chip clears it, matching how the Profile tab behaves. */
    fun onPositionSelected(position: CourtPosition) = _state.update {
        val next = if (it.position == position) null else position
        // Ratings for skills the new position excludes are dropped rather than kept
        // out of sight: they would otherwise be saved, and go on driving a
        // recommendation for a skill the athlete was never shown a question about.
        val allowed = next?.relevantSkills ?: Skill.All.toSet()
        it.copy(position = next, ratings = it.ratings.filterKeys { skill -> skill in allowed })
    }

    fun onHandSelected(hand: DominantHand) = _state.update {
        it.copy(hand = if (it.hand == hand) null else hand)
    }

    fun onExperienceSelected(level: ExperienceLevel) = _state.update {
        it.copy(experience = if (it.experience == level) null else level)
    }

    fun onRatingSelected(skill: Skill, rating: SelfRating) = _state.update {
        it.copy(ratings = it.ratings + (skill to rating))
    }

    fun onBack() = _state.update { current ->
        val previous = AssessmentStep.All.getOrNull(current.step.ordinal - 1)
        if (previous == null) current else current.copy(step = previous)
    }

    fun onNext(onFinished: () -> Unit) {
        val current = _state.value
        if (!current.canAdvance) return
        val next = AssessmentStep.All.getOrNull(current.step.ordinal + 1)
        if (next != null) {
            _state.update { it.copy(step = next) }
            return
        }
        finish(current, onFinished)
    }

    /** Records that onboarding happened, without answers, and leaves. */
    fun onSkip(onFinished: () -> Unit) {
        viewModelScope.launch {
            assessmentRepository.skip()
            onFinished()
        }
    }

    private fun finish(current: AssessmentUiState, onFinished: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            // Read-modify-write against the stored profile rather than against a
            // fresh one, so onboarding cannot wipe a name the athlete already has.
            val profile = profileRepository.profile.first()
            profileRepository.save(
                profile.copy(position = current.position, dominantHand = current.hand),
            )
            assessmentRepository.save(
                SkillAssessment(
                    ratings = current.ratings,
                    experience = current.experience,
                    completedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            onFinished()
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AssessmentViewModel(
                    assessmentRepository = appContainer.assessmentRepository,
                    profileRepository = appContainer.profileRepository,
                )
            }
        }
    }
}
