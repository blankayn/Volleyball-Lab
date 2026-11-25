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

enum class AssessmentPage { About, Goals, Time, Card, Finish, Result }

/** One screen. A [Card][AssessmentPage.Card] asks about one characteristic of one skill. */
data class AssessmentStep(val kind: AssessmentPage, val statement: SkillStatement? = null)

data class AssessmentUiState(
    /** The order skills are offered in. */
    val order: List<Skill> = Skill.All,
    val page: Int = 0,
    val experience: ExperienceLevel? = null,
    val position: CourtPosition? = null,
    /** The skills the athlete wants to work on, in the order they chose them. */
    val preferred: List<Skill> = emptyList(),
    /** Skills added at the end. */
    val extra: List<Skill> = emptyList(),
    /** The skill opened again from the end; its last card leads straight back there. */
    val editing: Skill? = null,
    val goal: MainGoal? = null,
    val goalNote: String = "",
    val days: Int = 3,
    val minutes: Int = 30,
    val statements: Map<String, CanDo> = emptyMap(),
    val details: Map<String, List<String>> = emptyMap(),
    val weakNote: String = "",
    val plan: AthletePlan? = null,
    val submittedAtEpochMs: Long = 0L,
    val planner: PlannerStatus = PlannerStatus.Idle,
    val drills: Map<String, Drill> = emptyMap(),
    val busy: Boolean = true,
    val error: String? = null,
) {
    /** Only these are asked about. */
    val assessed: List<Skill> get() = preferred + (extra - preferred.toSet())
    /** Skills that can still be added. */
    val others: List<Skill> get() = order - assessed.toSet()
    internal val finishPage: Int get() = INTRO.size + assessed.size * SkillCheck.PER_SKILL
    /** Where Next goes. */
    val after: Int get() = if (kind == AssessmentPage.Card && editing == skill && card == SkillCheck.PER_SKILL - 1) finishPage else page + 1
    /** Where Back goes. */
    val before: Int get() = if (kind == AssessmentPage.Card && editing == skill && card == 0) finishPage else (page - 1).coerceAtLeast(0)
    val steps: List<AssessmentStep> get() = INTRO + assessed.flatMap { skill -> SkillCheck.forSkill(skill).map { AssessmentStep(AssessmentPage.Card, it) } } + OUTRO
    private val step: AssessmentStep get() = steps.let { it[page.coerceIn(0, it.lastIndex)] }
    val kind: AssessmentPage get() = step.kind
    val statement: SkillStatement? get() = step.statement
    val skill: Skill? get() = statement?.skill
    /** Where this card is in its skill, from 0. */
    val card: Int get() = statement?.let { SkillCheck.forSkill(it.skill).indexOf(it) } ?: 0
    /** The plan for these answers. An earlier one is not shown as their result. */
    val result: AthletePlan? get() = plan?.takeIf { submittedAtEpochMs > 0 && it.createdAtEpochMs >= submittedAtEpochMs }
    /** Of the questions, so the last one is full. The skills are not known before they are chosen, so one is counted. */
    val progress: Float get() = (page + 1f) / (INTRO.size + maxOf(assessed.size, 1) * SkillCheck.PER_SKILL + 1)
    val canAdvance: Boolean get() = !busy && when (kind) {
        AssessmentPage.About -> experience != null
        AssessmentPage.Goals -> goal != null && preferred.isNotEmpty()
        AssessmentPage.Card -> statement?.id in statements
        AssessmentPage.Finish -> answers != null
        else -> true
    }
    val answers: AssessmentAnswers? get() {
        val asked = assessed.flatMap(SkillCheck::forSkill)
        if (asked.any { it.id !in statements }) return null
        val given = asked.associate { it.id to statements.getValue(it.id) }
        val said = asked.filter { SkillCheck.asksDetails(given[it.id]) }.associate { it.id to details[it.id].orEmpty() }.filterValues { it.isNotEmpty() }
        return AssessmentAnswers(experience ?: return null, position, preferred, goal ?: return null, goalNote.trim(), days, minutes,
            statements = given, details = said, weakNote = weakNote.trim()).takeIf { it.isComplete }
    }

    companion object {
        private val INTRO = listOf(AssessmentPage.About, AssessmentPage.Goals, AssessmentPage.Time).map { AssessmentStep(it) }
        private val OUTRO = listOf(AssessmentPage.Finish, AssessmentPage.Result).map { AssessmentStep(it) }
    }
}

/**
 * Asks, saves, and has the AI write this athlete's assessment.
 *
 * Only the skills the athlete chose are asked about, and what follows an answer depends on
 * it. The same is asked about a skill whoever chooses it. Answers are saved before the AI
 * is asked, so an athlete who is offline or over the limit loses nothing and the plan is
 * written later ([AthletePlanner.refreshIfDue]).
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
                    statements = SkillCheck.known(earlier.answers + saved?.statements.orEmpty()), details = saved?.details.orEmpty(), weakNote = saved?.weakNote.orEmpty(),
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
    fun onPreferred(skill: Skill) = change { it.copy(preferred = it.preferred.toggled(skill), extra = it.extra - skill) }
    fun onGoal(value: MainGoal) = change { it.copy(goal = value) }
    fun onGoalNote(text: String) = change { it.copy(goalNote = text.take(AssessmentAnswers.MAX_NOTE)) }
    fun onDays(value: Int) = change { it.copy(days = value.coerceIn(1, 7)) }
    fun onMinutes(value: Int) = change { if (value in AssessmentAnswers.MINUTES) it.copy(minutes = value) else it }
    fun onAnswer(statementId: String, answer: CanDo) = change { it.copy(statements = it.statements + (statementId to answer),
        details = if (SkillCheck.asksDetails(answer)) it.details else it.details - statementId) }
    fun onDetail(statementId: String, detailId: String) = change { state ->
        if (!SkillCheck.asksDetails(state.statements[statementId])) state
        else state.copy(details = state.details + (statementId to state.details[statementId].orEmpty().toggled(detailId)))
    }

    /** Answers the rest of this skill at once, for someone who has never tried it. */
    fun onNeverTried() = change { state ->
        val skill = state.skill ?: return@change state
        val ids = SkillCheck.forSkill(skill).map { it.id }
        state.copy(statements = state.statements + ids.associateWith { CanDo.NotTried }, details = state.details - ids.toSet(),
            page = if (state.editing == skill) state.finishPage else state.page + SkillCheck.PER_SKILL - state.card, editing = null)
    }

    /** Adds another skill at the end and opens its first card. */
    fun onAddSkill(skill: Skill) = change { state ->
        if (state.kind != AssessmentPage.Finish || skill !in state.others) state
        else state.copy(extra = state.extra + skill)
    }

    /** Takes an added skill out again. The skills chosen first stay. */
    fun onRemoveSkill(skill: Skill) = change { state ->
        if (state.kind != AssessmentPage.Finish || skill !in state.extra) state
        else state.copy(extra = state.extra - skill).let { it.copy(page = it.finishPage) }
    }

    /** Opens a skill's cards again from the end. */
    fun onEditSkill(skill: Skill) = change { state ->
        if (state.kind != AssessmentPage.Finish || skill !in state.assessed) state
        else state.copy(page = state.steps.indexOfFirst { it.statement?.skill == skill }, editing = skill)
    }
    fun onWeakNote(text: String) = change { it.copy(weakNote = text.take(AssessmentAnswers.MAX_NOTE)) }
    private fun AssessmentUiState.at(page: Int) = copy(page = page, editing = editing.takeIf { page != finishPage })
    fun onBack() = change { it.at(it.before) }

    fun onNext(onFinished: () -> Unit) {
        val state = _state.value
        if (!state.canAdvance) return
        when (state.kind) {
            AssessmentPage.Result -> onFinished()
            AssessmentPage.Finish -> submit(state)
            else -> change { it.at(it.after) }
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
