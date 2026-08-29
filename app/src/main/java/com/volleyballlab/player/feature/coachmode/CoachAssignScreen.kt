package com.volleyballlab.player.feature.coachmode

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.R
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.data.coach.DataStoreCoachRepository
import com.volleyballlab.player.domain.model.AssignmentStatus
import com.volleyballlab.player.domain.model.CoachAssignment
import com.volleyballlab.player.domain.model.Drill
import com.volleyballlab.player.domain.model.DrillLevel
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.CoachRepository
import com.volleyballlab.player.domain.repository.DrillRepository
import com.volleyballlab.player.domain.repository.SessionDirectory
import com.volleyballlab.player.ui.component.EntranceAnimation
import com.volleyballlab.player.ui.component.SkillAccentRule
import com.volleyballlab.player.ui.theme.VbTabularNumerals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CoachAssignViewModel(
    private val coachRepository: CoachRepository,
    private val authRepository: AuthRepository,
    private val drillRepository: DrillRepository,
    sessionDirectory: SessionDirectory,
) : ViewModel() {

    val state: StateFlow<CoachTeamState> =
        coachTeamState(coachRepository, sessionDirectory).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CoachTeamState(),
        )

    /**
     * The built-in catalogue, flattened across all five skills.
     *
     * This is Figure 28's "quick-access drill library". Read once and held,
     * because [com.volleyballlab.player.data.skill.StaticDrillRepository] is a
     * constant list - when it stops being one, this becomes a flow and nothing on
     * the screen changes.
     */
    private val _drills = MutableStateFlow<List<Drill>>(emptyList())
    val drills: StateFlow<List<Drill>> = _drills.asStateFlow()

    init {
        viewModelScope.launch {
            _drills.value = Skill.All.flatMap { drillRepository.drillsFor(it) }
        }
    }

    /**
     * Writes the assignment.
     *
     * @param existingId non-null when editing, so the write replaces rather than
     *   duplicates. The created-at timestamp of an edited assignment is preserved
     *   for the same reason: a coach fixing a typo should not reorder their list.
     */
    fun save(
        existingId: String?,
        playerUid: String,
        title: String,
        skill: Skill,
        level: DrillLevel,
        summary: String,
        instructions: String,
        sets: Int,
        reps: Int,
        dueInDays: Int?,
        createdAtEpochMs: Long?,
        onSaved: () -> Unit,
    ) {
        val coachUid = authRepository.currentUid ?: return
        if (playerUid.isBlank() || title.isBlank()) return

        val now = System.currentTimeMillis()
        viewModelScope.launch {
            coachRepository.saveAssignment(
                CoachAssignment(
                    id = existingId ?: DataStoreCoachRepository.newId(),
                    coachUid = coachUid,
                    playerUid = playerUid,
                    title = title.trim().take(CoachAssignment.MAX_TITLE_LENGTH),
                    skill = skill,
                    level = level,
                    summary = summary.trim().take(CoachAssignment.MAX_SUMMARY_LENGTH),
                    instructions = instructions.trim()
                        .take(CoachAssignment.MAX_INSTRUCTIONS_LENGTH),
                    targetSets = sets.coerceIn(CoachAssignment.SETS_RANGE),
                    targetReps = reps.coerceIn(CoachAssignment.REPS_RANGE),
                    // Stored as an absolute instant, not as "in five days". A
                    // relative deadline would silently move every time the record
                    // was read, which is not what anyone means by a due date.
                    dueAtEpochMs = dueInDays?.let { now + it * DAY_MS },
                    status = AssignmentStatus.Pending,
                    createdAtEpochMs = createdAtEpochMs ?: now,
                ),
            )
            onSaved()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { coachRepository.deleteAssignment(id) }
    }

    companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CoachAssignViewModel(
                    coachRepository = appContainer.coachRepository,
                    authRepository = appContainer.authRepository,
                    drillRepository = appContainer.drillRepository,
                    sessionDirectory = appContainer.sessionDirectory,
                )
            }
        }
    }
}

/** Every assignment this coach has written, with the button that adds another. */
@Composable
fun CoachAssignListScreen(
    onNew: () -> Unit,
    onEdit: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val viewModel: CoachAssignViewModel = viewModel(factory = CoachAssignViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now = remember { System.currentTimeMillis() }
    val names = remember(state.roster) {
        state.roster.associate { it.playerUid to it.playerName }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            EntranceAnimation(index = 0) {
                CoachPageHeader(
                    title = stringResource(R.string.coach_assign_title),
                    subtitle = null,
                )
            }
        }

        item {
            EntranceAnimation(index = 1) {
                Column(
                    modifier = Modifier.padding(horizontal = CoachGutter),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CoachPillButton(
                        label = stringResource(R.string.coach_assign_new),
                        onClick = onNew,
                        // Disabled rather than hidden, so a coach with no squad can see
                        // that the feature exists and read why it is not available yet.
                        // The footnote directly beneath is the "why", which is why the
                        // two live in one block rather than as separate list items.
                        enabled = state.activeMembers.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.activeMembers.isEmpty()) {
                        CoachFootnote(stringResource(R.string.coach_assign_no_players))
                    }
                }
            }
        }

        if (state.assignments.isEmpty()) {
            item {
                EntranceAnimation(index = 2) {
                    CoachEmptyCard(
                        title = stringResource(R.string.coach_assign_empty_title),
                        body = stringResource(R.string.coach_assign_empty_body),
                        icon = Icons.Rounded.EditNote,
                        accent = CoachAccent.Violet,
                        modifier = Modifier.padding(horizontal = CoachGutter),
                    )
                }
            }
        } else {
            items(items = state.assignments, key = { it.id }) { assignment ->
                AssignmentRow(
                    assignment = assignment,
                    playerName = names[assignment.playerUid].orEmpty(),
                    now = now,
                    onClick = { onEdit(assignment.id) },
                    modifier = Modifier.padding(horizontal = CoachGutter),
                )
            }
        }
    }
}

/**
 * One assignment as a list row.
 *
 * Two colour systems meet here and neither is decorative. The rule on the left is
 * the *skill*, in the same palette the player half of the app uses, so a coach
 * scanning the list sorts by discipline without reading. The chip on the right is
 * the *status*: violet, because an assignment is written material and that is what
 * violet means everywhere in this shell. Overdue is the one case that leaves the
 * family - it drops to the error tone, since the whole point of the chip then is
 * that this one is not like the others.
 */
@Composable
private fun AssignmentRow(
    assignment: CoachAssignment,
    playerName: String,
    now: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val overdue = assignment.isOverdue(now)
    CoachRowCard(modifier = modifier, onClick = onClick) {
        SkillAccentRule(skill = assignment.skill, height = 44)
        HorizontalGap(14)
        Column(Modifier.weight(1f)) {
            Text(
                text = assignment.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = listOf(
                    playerName.ifBlank { stringResource(R.string.coach_unnamed) },
                    stringResource(
                        R.string.coach_assign_target,
                        assignment.targetSets,
                        assignment.targetReps,
                    ),
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            assignment.dueAtEpochMs?.let { due ->
                Text(
                    text = stringResource(
                        R.string.coach_assign_due_on,
                        relativeDayLabel(due, now),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalGap(10)
        if (overdue) {
            StatusChip(
                label = stringResource(R.string.coach_assign_overdue),
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            AccentChip(
                label = stringResource(assignment.status.displayName),
                accent = CoachAccent.Violet,
            )
        }
    }
}

/**
 * Figure 28's assignment form.
 *
 * ## What the web form has that this does not
 *
 * The figure puts a quick-access drill library in a right-hand panel. There is no
 * room for a second column on a phone, so the library appears as a row of chips
 * above the name field: tapping one fills the form from
 * [com.volleyballlab.player.domain.repository.DrillRepository] and leaves every
 * field editable. That keeps the shortcut without pretending a panel fits.
 *
 * ## Three cards, not nine labelled strips
 *
 * The fields are grouped into three [CoachSectionCard]s - *who and what*, *what
 * shape it takes*, *what to tell them* - rather than being listed flat. Nine
 * equally-weighted headings down a page read as nine decisions; three cards read
 * as three, which is how many the coach is actually making. Where a section header
 * already names its first field (the section titled "Player" opening on the player
 * chips) that field is not labelled twice; every other field keeps its own label
 * next to it.
 *
 * ## Due dates are entered in days, not picked from a calendar
 *
 * A coach setting work says "by Friday" or "this week", and the number they
 * actually hold is a horizon rather than a date. Days-from-now is one tap; a date
 * picker is four, and the result is the same instant. The stored value is absolute
 * either way - see [CoachAssignViewModel.save].
 */
@Composable
fun CoachAssignEditScreen(
    presetPlayerUid: String,
    assignmentId: String,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val viewModel: CoachAssignViewModel = viewModel(factory = CoachAssignViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val drills by viewModel.drills.collectAsStateWithLifecycle()

    val existing = state.assignments.firstOrNull { it.id == assignmentId }

    // Keyed on the assignment id so opening a different one resets the form rather
    // than editing the previous drill's text into the new one.
    var playerUid by rememberSaveable(assignmentId) { mutableStateOf(presetPlayerUid) }
    var title by rememberSaveable(assignmentId) { mutableStateOf("") }
    var skill by rememberSaveable(assignmentId) { mutableStateOf(Skill.Serving) }
    var level by rememberSaveable(assignmentId) { mutableStateOf(DrillLevel.Beginner) }
    var summary by rememberSaveable(assignmentId) { mutableStateOf("") }
    var instructions by rememberSaveable(assignmentId) { mutableStateOf("") }
    var sets by rememberSaveable(assignmentId) { mutableStateOf(CoachAssignment.DEFAULT_SETS) }
    var reps by rememberSaveable(assignmentId) { mutableStateOf(CoachAssignment.DEFAULT_REPS) }
    var dueInDays by rememberSaveable(assignmentId) { mutableStateOf<Int?>(DEFAULT_DUE_DAYS) }
    var loadedExisting by rememberSaveable(assignmentId) { mutableStateOf(false) }

    // The store read lands after the first composition, so an edit has to fill the
    // form once it arrives. Guarded by a flag rather than a LaunchedEffect key on
    // `existing`, so a later store emission cannot overwrite what the coach has
    // since typed.
    if (existing != null && !loadedExisting) {
        loadedExisting = true
        playerUid = existing.playerUid
        title = existing.title
        skill = existing.skill
        level = existing.level
        summary = existing.summary
        instructions = existing.instructions
        sets = existing.targetSets
        reps = existing.targetReps
        dueInDays = null
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            CoachDetailHeader(
                title = stringResource(R.string.coach_assign_edit_title),
                subtitle = null,
                onBack = onBack,
                // The trailing slot draws nothing at all for a new assignment
                // rather than a greyed-out Delete: a disabled control in a header
                // is a puzzle the coach has to solve, and an absent one is not.
                trailing = {
                    if (existing != null) {
                        TextButton(
                            onClick = {
                                viewModel.delete(existing.id)
                                onBack()
                            },
                        ) {
                            Text(stringResource(R.string.coach_assign_delete))
                        }
                    }
                },
            )
        }

        item {
            CoachSectionCard(
                title = stringResource(R.string.coach_assign_player),
                icon = Icons.Rounded.Person,
                accent = CoachAccent.Rust,
                modifier = Modifier.padding(horizontal = CoachGutter),
            ) {
                FormFields {
                    if (state.activeMembers.isEmpty()) {
                        CoachFootnote(stringResource(R.string.coach_assign_no_players))
                    } else {
                        ChipRow {
                            state.activeMembers.forEach { member ->
                                FilterChip(
                                    selected = member.playerUid == playerUid,
                                    onClick = { playerUid = member.playerUid },
                                    label = {
                                        Text(
                                            member.playerName.ifBlank {
                                                stringResource(R.string.coach_unnamed)
                                            },
                                        )
                                    },
                                )
                                HorizontalGap(8)
                            }
                        }
                    }

                    if (drills.isNotEmpty()) {
                        FormField(stringResource(R.string.coach_assign_library)) {
                            ChipRow {
                                drills.forEach { drill ->
                                    FilterChip(
                                        selected = title == drill.name,
                                        onClick = {
                                            title = drill.name
                                            skill = drill.skill
                                            level = drill.level
                                            summary = drill.description
                                            reps = drill.targetReps
                                                .coerceIn(CoachAssignment.REPS_RANGE)
                                        },
                                        label = { Text(drill.name) },
                                    )
                                    HorizontalGap(8)
                                }
                            }
                        }
                    }

                    FormField(stringResource(R.string.coach_assign_name)) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it.take(CoachAssignment.MAX_TITLE_LENGTH) },
                            placeholder = { Text(stringResource(R.string.coach_assign_name_hint)) },
                            singleLine = true,
                            shape = CoachInnerShape,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        item {
            // Green, because everything in this card is the *training* being
            // prescribed - the skill, the difficulty, the sets and reps - and green
            // is this shell's tone for training throughout.
            CoachSectionCard(
                title = stringResource(R.string.coach_assign_skill),
                icon = Icons.Rounded.Tune,
                accent = CoachAccent.Green,
                modifier = Modifier.padding(horizontal = CoachGutter),
            ) {
                FormFields {
                    ChipRow {
                        Skill.All.forEach { candidate ->
                            FilterChip(
                                selected = candidate == skill,
                                onClick = { skill = candidate },
                                label = { Text(stringResource(candidate.displayName)) },
                            )
                            HorizontalGap(8)
                        }
                    }

                    FormField(stringResource(R.string.coach_assign_level)) {
                        ChipRow {
                            DrillLevel.entries.forEach { candidate ->
                                FilterChip(
                                    selected = candidate == level,
                                    onClick = { level = candidate },
                                    label = { Text(stringResource(candidate.displayName)) },
                                )
                                HorizontalGap(8)
                            }
                        }
                    }

                    // The steppers carry their own labels, so they sit in the card
                    // as a bare pair rather than inside another labelled field.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Stepper(
                            label = stringResource(R.string.coach_assign_sets),
                            value = sets,
                            range = CoachAssignment.SETS_RANGE,
                            onChange = { sets = it },
                            modifier = Modifier.weight(1f),
                        )
                        Stepper(
                            label = stringResource(R.string.coach_assign_reps),
                            value = reps,
                            range = CoachAssignment.REPS_RANGE,
                            onChange = { reps = it },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    FormField(stringResource(R.string.coach_assign_due)) {
                        ChipRow {
                            FilterChip(
                                selected = dueInDays == null,
                                onClick = { dueInDays = null },
                                label = { Text(stringResource(R.string.coach_assign_due_none)) },
                            )
                            HorizontalGap(8)
                            DUE_OPTIONS.forEach { days ->
                                FilterChip(
                                    selected = dueInDays == days,
                                    onClick = { dueInDays = days },
                                    label = {
                                        Text(stringResource(R.string.coach_assign_due_days, days))
                                    },
                                )
                                HorizontalGap(8)
                            }
                        }
                    }
                }
            }
        }

        item {
            CoachSectionCard(
                title = stringResource(R.string.coach_assign_instructions),
                icon = Icons.Rounded.EditNote,
                accent = CoachAccent.Violet,
                modifier = Modifier.padding(horizontal = CoachGutter),
            ) {
                // Both fields are labelled here, including the one whose label
                // repeats the card's title: the summary sits between them, so
                // borrowing the header as a label would leave it a card apart from
                // the box it names.
                FormFields {
                    FormField(stringResource(R.string.coach_assign_summary)) {
                        OutlinedTextField(
                            value = summary,
                            onValueChange = {
                                summary = it.take(CoachAssignment.MAX_SUMMARY_LENGTH)
                            },
                            placeholder = {
                                Text(stringResource(R.string.coach_assign_summary_hint))
                            },
                            shape = CoachInnerShape,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    FormField(stringResource(R.string.coach_assign_instructions)) {
                        OutlinedTextField(
                            value = instructions,
                            onValueChange = {
                                instructions = it.take(CoachAssignment.MAX_INSTRUCTIONS_LENGTH)
                            },
                            placeholder = {
                                Text(stringResource(R.string.coach_assign_instructions_hint))
                            },
                            shape = CoachInnerShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp),
                        )
                    }
                }
            }
        }

        item {
            CoachPillButton(
                label = stringResource(R.string.coach_assign_save),
                onClick = {
                    viewModel.save(
                        existingId = existing?.id,
                        playerUid = playerUid,
                        title = title,
                        skill = skill,
                        level = level,
                        summary = summary,
                        instructions = instructions,
                        sets = sets,
                        reps = reps,
                        dueInDays = dueInDays,
                        createdAtEpochMs = existing?.createdAtEpochMs,
                        onSaved = onBack,
                    )
                },
                enabled = playerUid.isNotBlank() && title.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CoachGutter, vertical = 8.dp),
            )
        }
    }
}

/**
 * The fields of one section card, evenly spaced.
 *
 * A single arrangement rather than a margin on each field, so the rhythm inside a
 * card cannot drift between the three sections.
 */
@Composable
private fun FormFields(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/**
 * One labelled control inside a section card.
 *
 * The label is small and grey rather than a heading: inside a card the heading
 * work is already done by the section title, and a second bold line per field
 * would flatten the hierarchy back to the list this replaced.
 */
@Composable
private fun FormField(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** Chips in one scrolling row, so five skills fit on a narrow phone. */
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/**
 * Minus, number, plus.
 *
 * A stepper rather than a text field because both values are small integers with
 * hard bounds, and a keyboard here invites "10 " and "ten" and an empty string,
 * each of which then needs a validation message that a stepper makes impossible.
 *
 * Sits on [CoachInnerShape] over `surfaceContainer` so it reads as a well sunk
 * into the white section card. The value is set at title size rather than at the
 * display size the stat strip uses: two fixed 48 dp touch targets flank it inside
 * half a card, and a headline number would be the first thing to be clipped when
 * the system font scale goes up. Tabular figures keep the digits from shifting as
 * the value crosses from one column to two.
 */
@Composable
private fun Stepper(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CoachInnerShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onChange((value - 1).coerceIn(range)) },
                    enabled = value > range.first,
                ) {
                    Icon(Icons.Rounded.Remove, contentDescription = null)
                }
                Text(
                    text = value.toString(),
                    style = MaterialTheme.typography.titleMedium.merge(VbTabularNumerals),
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { onChange((value + 1).coerceIn(range)) },
                    enabled = value < range.last,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                }
            }
        }
    }
}

/** Horizons a coach actually sets work over. */
private val DUE_OPTIONS = listOf(2, 3, 7, 14)

private const val DEFAULT_DUE_DAYS = 7
