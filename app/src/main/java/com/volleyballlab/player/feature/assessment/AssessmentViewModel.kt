package com.volleyballlab.player.feature.assessment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.data.assessment.AthleteAssessmentRepository
import com.volleyballlab.player.data.assessment.AthletePlanner
import com.volleyballlab.player.data.assessment.PlannerStatus
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.domain.repository.AssessmentRepository
import com.volleyballlab.player.domain.repository.DrillRepository
import com.volleyballlab.player.domain.repository.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The pages in order. The five skill pages come between [Time] and [Weak]. */
enum class AssessmentPage { About, Goals, Time, Skill, Weak, Result }

data class AssessmentUiState(
    val order: List<Skill> = Skill.All,
    val page: Int = 0,
    val experience: ExperienceLevel? = null,
    val position: CourtPosition? = null,
    val preferred: List<Skill> = emptyList(),
    val goal: MainGoal? = null,
    val goalNote: String = "",
    val days: Int = 3,
    val minutes: Int = 30,
    val statements: Map<String, CanDo> = emptyMap(),
    val weakPoints: List<WeakPoint> = emptyList(),
    val weakNote: String = "",
    val plan: AthletePlan? = null,
    val submittedAtEpochMs: Long = 0L,
    val planner: PlannerStatus = PlannerStatus.Idle,
    val drills: Map<String, Drill> = emptyMap(),
    val busy: Boolean = true,
    val error: String? = null,
) {
    val pages: Int get() = FIRST_SKILL + order.size + 2
    val kind: AssessmentPage get() = when {
        page == 0 -> AssessmentPage.About
        page == 1 -> AssessmentPage.Goals
        page == 2 -> AssessmentPage.Time
        page < FIRST_SKILL + order.size -> AssessmentPage.Skill
        page == FIRST_SKILL + order.size -> AssessmentPage.Weak
        else -> AssessmentPage.Result
    }
    val skill: Skill? get() = if (kind == AssessmentPage.Skill) order[page - FIRST_SKILL] else null
    /** The plan for these answers. An earlier one is not shown as their result. */
    val result: AthletePlan? get() = plan?.takeIf { submittedAtEpochMs > 0 && it.createdAtEpochMs >= submittedAtEpochMs }
    val progress: Float get() = (page + 1f) / pages
    val canAdvance: Boolean get() = !busy && when (kind) {
        AssessmentPage.About -> experience != null
        AssessmentPage.Goals -> goal != null
        AssessmentPage.Skill -> SkillCheck.forSkill(requireNotNull(skill)).all { it.id in statements }
        else -> true
    }
    val answers: AssessmentAnswers? get() {
        return AssessmentAnswers(experience ?: return null, position, preferred, goal ?: return null, goalNote.trim(), days, minutes,
            weakPoints, weakNote.trim(), SkillCheck.known(statements)).takeIf { it.isComplete }
    }

    companion object { const val FIRST_SKILL = 3 }
}

/**
 * Asks, saves, and has the AI write this athlete's assessment.
 *
 * The questions are the same for everyone; nothing in the result is. Answers are saved
 * before the AI is asked, so an athlete who is offline or over the limit loses nothing and
 * the plan is written later ([AthletePlanner.refreshIfDue]).
 */
class AssessmentViewModel(
    private val assessments: AssessmentRepository,
    private val profiles: ProfileRepository,
    private val athlete: AthleteAssessmentRepository,
    private val planner: AthletePlanner,
    private val drills: DrillRepository,
    private val currentUid: () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val uid = currentUid()
    private val _state = MutableStateFlow(AssessmentUiState(order = SkillCheck.orderFor(uid)))
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val saved = withTimeoutOrNull(LOAD_MS) { athlete.state.filterNotNull().first() }?.answers
                val earlier = assessments.assessment.first()
                val profile = profiles.profile.first()
                checkAccount()
                _state.update { it.copy(
                    experience = saved?.experience ?: earlier.experience, position = saved?.position ?: profile.position,
                    preferred = saved?.preferred ?: earlier.focus, goal = saved?.goal, goalNote = (saved?.goalNote ?: profile.goalText).take(AssessmentAnswers.MAX_NOTE),
                    days = (saved?.days ?: profile.weeklySessionGoal).coerceIn(1, 7), minutes = saved?.minutes?.takeIf { m -> m in AssessmentAnswers.MINUTES } ?: 30,
                    statements = SkillCheck.known(saved?.statements ?: earlier.answers), weakPoints = saved?.weakPoints.orEmpty(), weakNote = saved?.weakNote.orEmpty(),
                    drills = Skill.All.flatMap { skill -> drills.drillsFor(skill) }.associateBy { drill -> drill.id }, busy = false) }
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { _state.update { it.copy(busy = false, error = "Could not load your saved answers. You can still answer again.") } }
        }
        viewModelScope.launch { planner.status.collect { status -> _state.update { it.copy(planner = status) } } }
        viewModelScope.launch { athlete.state.collect { saved -> _state.update { it.copy(plan = saved?.plan) } } }
    }

    private fun checkAccount() { check(uid != null && currentUid() == uid) { "Your account changed. Reopen the assessment to continue." } }
    private fun change(change: (AssessmentUiState) -> AssessmentUiState) = _state.update { if (it.busy) it else change(it).copy(error = null) }
    private fun <T> List<T>.toggled(item: T) = if (item in this) this - item else this + item

    fun onExperience(value: ExperienceLevel) = change { it.copy(experience = value) }
    fun onPosition(value: CourtPosition?) = change { it.copy(position = value) }
    fun onPreferred(skill: Skill) = change { it.copy(preferred = it.preferred.toggled(skill)) }
    fun onGoal(value: MainGoal) = change { it.copy(goal = value) }
    fun onGoalNote(text: String) = change { it.copy(goalNote = text.take(AssessmentAnswers.MAX_NOTE)) }
    fun onDays(value: Int) = change { it.copy(days = value.coerceIn(1, 7)) }
    fun onMinutes(value: Int) = change { if (value in AssessmentAnswers.MINUTES) it.copy(minutes = value) else it }
    fun onAnswer(statementId: String, answer: CanDo) = change { it.copy(statements = it.statements + (statementId to answer)) }
    fun onWeakPoint(value: WeakPoint) = change { it.copy(weakPoints = it.weakPoints.toggled(value)) }
    fun onWeakNote(text: String) = change { it.copy(weakNote = text.take(AssessmentAnswers.MAX_NOTE)) }
    fun onBack() = change { it.copy(page = (it.page - 1).coerceAtLeast(0)) }

    fun onNext(onFinished: () -> Unit) {
        val state = _state.value
        if (!state.canAdvance) return
        when (state.kind) {
            AssessmentPage.Result -> onFinished()
            AssessmentPage.Weak -> submit(state)
            else -> change { it.copy(page = it.page + 1) }
        }
    }

    private fun submit(state: AssessmentUiState) {
        val answers = state.answers?.copy(savedAtEpochMs = now()) ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                checkAccount()
                assessments.complete(answers)
                checkAccount()
                athlete.saveAnswers(requireNotNull(uid), answers)
                checkAccount()
                _state.update { it.copy(page = it.page + 1, submittedAtEpochMs = answers.savedAtEpochMs, busy = false) }
                planner.write(answers)
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { _state.update { it.copy(error = message("Could not save. Your answers are still here; please try again.")) } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }

    fun retryPlan() { if (_state.value.planner != PlannerStatus.Working) viewModelScope.launch { planner.write() } }

    fun onSkip(onFinished: () -> Unit) {
        if (_state.value.busy) return
        if (_state.value.kind == AssessmentPage.Result) { onFinished(); return }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try { checkAccount(); assessments.skip(); checkAccount(); onFinished() }
            catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { _state.update { it.copy(error = message("Could not finish. Check your account and try again.")) } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }

    private fun message(text: String) = if (currentUid() != uid) "Your account changed. Reopen the assessment to continue." else text

    companion object {
        private const val LOAD_MS = 4_000L
        val Factory: ViewModelProvider.Factory = viewModelFactory { initializer {
            AssessmentViewModel(appContainer.assessmentRepository, appContainer.profileRepository, appContainer.athleteAssessmentRepository,
                appContainer.athletePlanner, appContainer.drillRepository, { appContainer.authRepository.currentUid })
        } }
    }
}
