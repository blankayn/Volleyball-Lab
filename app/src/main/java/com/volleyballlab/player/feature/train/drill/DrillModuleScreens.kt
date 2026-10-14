package com.volleyballlab.player.feature.train.drill

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.data.drill.DrillEntitlement
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.feature.profile.ProPlanCard
import com.volleyballlab.player.feature.train.skill.DrillPostureVisual
import com.volleyballlab.player.ui.component.GhostLayout
import com.volleyballlab.player.ui.component.GhostLoadingScreen
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException

@Composable
fun DrillLibraryScreen(
    onDrill: (String) -> Unit,
    onResume: (DrillPracticeDraft) -> Unit,
    onSummary: (String) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val model: DrillLibraryViewModel = viewModel(factory = DrillLibraryViewModel.Factory)
    val state by model.state.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var skillFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var levelFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var history by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    if (!state.loaded && state.error == null) {
        GhostLoadingScreen(GhostLayout.List, contentPadding)
        return
    }
    val filtered = state.drills.filter {
        (skillFilter == null || it.skill.name == skillFilter) &&
            (levelFilter == null || it.level.name == levelFilter) &&
            (search.isBlank() || it.name.contains(search.trim(), true) || it.description.contains(search.trim(), true))
    }
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item { ModuleHeader("Drill library", onBack) }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Learn the steps. Build your routine.", style = MaterialTheme.typography.bodyLarge)
                Text(if (state.entitlement.plan == UserPlan.Pro) "Pro · All 15 drills" else "Free · Five starter drills, one for each skill",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!history, { history = false }, label = { Text("Library") })
                    FilterChip(history, { history = true }, label = { Text("History") })
                }
            }
        }
        state.error?.let { error -> item { ModuleError(error, model::refresh) } }
        state.draft?.let { draft -> item {
            ModuleCard {
                Text("Workout in progress", style = MaterialTheme.typography.titleMedium)
                Text(draft.drillName)
                Text("${draft.sets.size}/${draft.config.sets} sets confirmed · ${draft.totalReps} manual reps")
                val drill = state.drills.firstOrNull { it.id == draft.drillId }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = state.entitlement.canStart(drill), onClick = { onResume(draft) }) { Text("Resume") }
                    TextButton(onClick = { discard = true }) { Text("Discard") }
                }
                if (!state.entitlement.canStart(drill)) Text("Premium workouts need current Pro access to resume.", style = MaterialTheme.typography.bodySmall)
            }
        } }
        if (history) {
            item { Text("Manual workouts only. Camera results are in Progress.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall) }
            if (state.history.isEmpty()) item { ModuleCard { Text("No drill workouts yet. Complete a set to save your first workout.") } }
            items(state.history, key = { it.id }) { record ->
                Surface(onClick = { onSummary(record.id) }, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(record.drillName, style = MaterialTheme.typography.titleMedium)
                        Text("${if (record.completed) "Completed" else "Partial"} · ${record.sets.size}/${record.config.sets} sets · ${record.totalReps} manual reps")
                        Text("${formatDrillTime(record.durationMs)} · ${DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(record.startedAtEpochMs))}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(search, { search = it }, label = { Text("Search drills") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(skillFilter == null, { skillFilter = null }, label = { Text("All skills") })
                        Skill.All.forEach { skill -> FilterChip(skillFilter == skill.name, { skillFilter = skill.name }, label = { Text(stringResource(skill.displayName)) }) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(levelFilter == null, { levelFilter = null }, label = { Text("All levels") })
                        DrillLevel.entries.forEach { level -> FilterChip(levelFilter == level.name, { levelFilter = level.name }, label = { Text(stringResource(level.displayName)) }) }
                    }
                }
            }
            if (filtered.isEmpty()) item { ModuleCard { Text("No drills match your filters.") } }
            items(filtered, key = { it.id }) { drill ->
                DrillPreview(drill, state.entitlement, { onDrill(drill.id) })
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard unfinished workout?") },
        text = { Text("This removes the unfinished workout and its confirmed sets.") },
        confirmButton = { TextButton({ model.discardDraft(); discard = false }) { Text("Discard") } },
        dismissButton = { TextButton({ discard = false }) { Text("Keep workout") } })
}

@Composable
fun DrillDetailScreen(
    drillId: String,
    onPractice: (DrillWorkoutConfig) -> Unit,
    onResume: (DrillPracticeDraft) -> Unit,
    onCamera: (Drill) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val model: DrillLibraryViewModel = viewModel(factory = DrillLibraryViewModel.Factory)
    val state by model.state.collectAsStateWithLifecycle()
    var upgrade by rememberSaveable { mutableStateOf(false) }
    var sets by rememberSaveable(drillId) { mutableIntStateOf(3) }
    var reps by rememberSaveable(drillId) { mutableIntStateOf(0) }
    var rest by rememberSaveable(drillId) { mutableIntStateOf(30) }
    val drill = state.drills.firstOrNull { it.id == drillId }
    LaunchedEffect(drill?.id) { if (drill != null && reps == 0) reps = drill.targetReps }
    if (!state.loaded && state.error == null) { GhostLoadingScreen(GhostLayout.Detail, contentPadding); return }
    val guide = drill?.let(DrillGuideCatalog::forDrill)
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        item { ModuleHeader(drill?.name ?: "Drill", onBack) }
        state.error?.let { item { ModuleError(it, model::refresh) } }
        if (drill == null || guide == null) {
            item { ModuleCard { Text("Drill not found.") } }
        } else {
            item { ModuleCard {
                Text("${stringResource(drill.skill.displayName)} · ${stringResource(drill.level.displayName)}", color = MaterialTheme.colorScheme.primary)
                Text("${drill.targetReps} target reps · About ${drill.estimatedMinutes} min")
            } }
            if (!state.entitlement.canStart(drill)) {
                item { ModuleCard {
                    Text(if (!state.entitlement.resolved) "Checking access…" else "Unlock this drill with Pro", style = MaterialTheme.typography.titleMedium)
                    Text("Free includes five starter drills. Pro unlocks all 15 guides and workouts.")
                    Button({ upgrade = true }) { Text("View Pro") }
                } }
            } else {
                item { ModuleCard {
                    Text("Purpose", style = MaterialTheme.typography.titleMedium)
                    Text(drill.description)
                    Text("Equipment", style = MaterialTheme.typography.titleSmall)
                    Text(guide.equipment)
                    Text("Before you begin", style = MaterialTheme.typography.titleSmall)
                    Text(guide.preparation)
                } }
                item { ModuleCard {
                    Text("Posture sample", style = MaterialTheme.typography.titleMedium)
                    DrillPostureVisual(drill.id)
                    Text("Posture reference. Camera feedback measures body form, not ball-target success.", style = MaterialTheme.typography.bodySmall)
                } }
                item { ModuleCard {
                    Text("Drill instructions", style = MaterialTheme.typography.titleMedium)
                    guide.steps.forEach { step -> Text(step.title, fontWeight = FontWeight.SemiBold); Text(step.action) }
                    Text("Common mistakes", style = MaterialTheme.typography.titleMedium)
                    guide.mistakes.forEach { Text("• $it") }
                } }
                item { ModuleCard {
                    Text("Build your workout", style = MaterialTheme.typography.titleMedium)
                    WorkoutNumber("Sets", sets, 1, 5, { sets = it })
                    WorkoutNumber("Reps per set", reps, 1, 100, { reps = it })
                    WorkoutNumber("Rest seconds", rest, 0, 120, { rest = it })
                    if (state.draft == null) Button(enabled = reps in 1..100, onClick = { onPractice(DrillWorkoutConfig(sets, reps, rest)) }, modifier = Modifier.fillMaxWidth()) { Text("Start practice") }
                    else {
                        val draft = requireNotNull(state.draft)
                        Text("Finish or discard your unfinished workout before starting another.", style = MaterialTheme.typography.bodySmall)
                        Button(enabled = state.entitlement.canStart(state.drills.firstOrNull { it.id == draft.drillId }), onClick = { onResume(draft) }) { Text("Resume ${draft.drillName}") }
                    }
                    OutlinedButton(onClick = { onCamera(drill) }, modifier = Modifier.fillMaxWidth()) { Text("Camera form check") }
                    Text("Practice reps are manually reported. Camera checks save separate results in Progress.", style = MaterialTheme.typography.bodySmall)
                } }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    if (upgrade) DrillUpgradeDialog { upgrade = false }
}

@Composable
fun DrillPracticeScreen(
    drillId: String, sets: Int, reps: Int, rest: Int, resumeId: String?,
    onSummary: (String) -> Unit, onBack: () -> Unit, contentPadding: PaddingValues,
) {
    val model: DrillPracticeViewModel = viewModel(key = "drill-workout-$drillId-${resumeId ?: "new"}",
        factory = DrillPracticeViewModel.factory(drillId, sets, reps, rest, resumeId))
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) model.foregroundChanged(false)
            if (event == Lifecycle.Event.ON_START) model.foregroundChanged(true)
        }
        owner.lifecycle.addObserver(observer)
        model.foregroundChanged(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { owner.lifecycle.removeObserver(observer); model.foregroundChanged(false) }
    }
    LaunchedEffect(state.savedId, state.discarded, state.leftPaused) { state.savedId?.let(onSummary); if (state.discarded || state.leftPaused) onBack() }
    var leaving by rememberSaveable { mutableStateOf(false) }
    var actualReps by rememberSaveable { mutableStateOf(reps.toString()) }
    LaunchedEffect(state.confirmingReps) { if (state.confirmingReps) actualReps = state.draft?.config?.reps.toString() }
    val draft = state.draft
    val askToLeave = { if (draft == null) onBack() else { model.pause(); leaving = true } }
    BackHandler(enabled = state.savedId == null && !state.discarded) { askToLeave() }
    if (state.loading) { GhostLoadingScreen(GhostLayout.Detail, contentPadding); return }
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        item { ModuleHeader(draft?.drillName ?: "Drill practice", askToLeave) }
        state.error?.let { error -> item { ModuleError(error, if (draft == null) onBack else model::retrySave, if (draft == null) "Back to drills" else "Retry save") } }
        if (draft != null) {
            item { ModuleCard {
                Text("Set ${draft.setNumber} of ${draft.config.sets}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("${draft.config.reps} target reps · Manually reported")
                Text("${draft.sets.size} confirmed sets · ${draft.totalReps} reps")
                Text("Practice ${formatDrillTime(draft.workMs)} · Rest ${formatDrillTime(draft.restMs)}", style = MaterialTheme.typography.bodyMedium)
                if (draft.stage == DrillPracticeStage.Resting) {
                    Text("Rest · ${formatDrillTime(draft.remainingRestMs)}", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    LinearProgressIndicator(progress = { (1f - draft.remainingRestMs / (draft.config.restSeconds * 1000f).coerceAtLeast(1f)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
                val enabled = !state.busy && state.error == null && !state.confirmingReps
                when (draft.stage) {
                    DrillPracticeStage.Ready -> Button(enabled = enabled, onClick = model::startSet, modifier = Modifier.fillMaxWidth()) { Text("Start set ${draft.setNumber}") }
                    DrillPracticeStage.Working -> Button(enabled = enabled, onClick = model::requestRepConfirmation, modifier = Modifier.fillMaxWidth()) { Text("Complete set") }
                    DrillPracticeStage.Resting -> OutlinedButton(enabled = enabled, onClick = model::skipRest) { Text("Skip rest") }
                    DrillPracticeStage.Paused -> Button(enabled = enabled, onClick = model::resume, modifier = Modifier.fillMaxWidth()) { Text("Resume") }
                    DrillPracticeStage.Complete -> Text(if (state.busy) "Saving workout…" else "Workout finished")
                }
                if (draft.stage !in setOf(DrillPracticeStage.Paused, DrillPracticeStage.Complete)) OutlinedButton(enabled = enabled, onClick = model::pause) { Text("Pause") }
                if (draft.stage != DrillPracticeStage.Complete) TextButton(enabled = !state.busy, onClick = { model.pause(); leaving = true }) { Text("Finish / discard workout") }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            } }
            val guide = DrillGuideCatalog.forDrill(Drill(draft.drillId, draft.skill, draft.drillName, "", draft.config.reps, 0, DrillLevel.Beginner))
            guide?.let { item { ModuleCard {
                Text("Posture sample", style = MaterialTheme.typography.titleMedium)
                DrillPostureVisual(draft.drillId)
                Text("Your drill steps", style = MaterialTheme.typography.titleMedium)
                it.steps.forEach { step -> Text("${step.title}: ${step.action}") }
            } } }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    if (state.confirmingReps) AlertDialog(onDismissRequest = model::cancelRepConfirmation, title = { Text("Confirm completed reps") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Enter the reps you actually performed in this set. These are manually reported.")
            OutlinedTextField(actualReps, { actualReps = it.filter(Char::isDigit).take(3) }, label = { Text("Actual reps (0–100)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        } },
        confirmButton = { TextButton(enabled = actualReps.toIntOrNull()?.let { it in 0..100 } == true && state.error == null, onClick = { model.confirmSet(actualReps.toInt()) }) { Text("Confirm set") } },
        dismissButton = { TextButton(model::cancelRepConfirmation) { Text("Keep paused") } })
    if (leaving) AlertDialog(onDismissRequest = { leaving = false }, title = { Text("Finish this workout?") },
        text = { Text(if (draft?.sets.isNullOrEmpty()) "No sets are confirmed yet. Leave it paused to resume later, or discard it." else "Save ${draft.sets.size} confirmed sets as a partial workout, leave it paused to resume later, or discard it.") },
        confirmButton = { if (!draft?.sets.isNullOrEmpty()) TextButton(enabled = !state.busy, onClick = { model.finish(); leaving = false }) { Text("Save and finish") } },
        dismissButton = { FlowRow {
            TextButton(enabled = !state.busy, onClick = { model.discard(); leaving = false }) { Text("Discard") }
            TextButton(enabled = !state.busy, onClick = { model.leavePaused(); leaving = false }) { Text("Leave paused") }
            TextButton({ leaving = false }) { Text("Close") }
        } })
}

@Composable
fun DrillPracticeSummaryScreen(workoutId: String, onBack: () -> Unit, contentPadding: PaddingValues) {
    val model: DrillLibraryViewModel = viewModel(factory = DrillLibraryViewModel.Factory)
    val state by model.state.collectAsStateWithLifecycle()
    if (!state.loaded && state.error == null) { GhostLoadingScreen(GhostLayout.Detail, contentPadding); return }
    val record = state.history.firstOrNull { it.id == workoutId }
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        item { ModuleHeader("Workout summary", onBack) }
        state.error?.let { item { ModuleError(it, model::refresh) } }
        if (record == null) item { ModuleCard { Text("Workout not found.") } } else {
            item { ModuleCard {
                Text(record.drillName, style = MaterialTheme.typography.headlineSmall)
                Text(if (record.completed) "Completed workout" else "Partial workout", color = MaterialTheme.colorScheme.primary)
                Text("${record.sets.size}/${record.config.sets} confirmed sets · ${record.totalReps} manually reported reps")
                Text("Practice ${formatDrillTime(record.workMs)} · Rest ${formatDrillTime(record.restMs)}")
                Text("No form score: this was a manual workout. Camera results are saved separately in Progress.", style = MaterialTheme.typography.bodySmall)
            } }
            items(record.sets, key = { it.number }) { set -> ModuleCard { Text("Set ${set.number} · ${set.reps}/${record.config.reps} reps · ${formatDrillTime(set.workMs)}") } }
            item { Button(onBack, Modifier.padding(horizontal = 20.dp).fillMaxWidth()) { Text("Back to drills") } }
        }
    }
}

/** Entry guard before camera permission, detector construction, or any scoring. */
@Composable
fun DrillCameraAccessGate(skill: Skill, drillId: String, onBack: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val graph = remember(context) { (context.applicationContext as VolleyballLabApplication).container }
    val entitlement by graph.drillAccessController.entitlement.collectAsStateWithLifecycle(DrillEntitlement())
    var drill by remember(drillId) { mutableStateOf<Drill?>(null) }
    var loaded by remember(drillId) { mutableStateOf(false) }
    val grantModel: DrillCameraGrantViewModel = viewModel(key = "drill-camera-grant-$drillId-${skill.name}")
    val grant by grantModel.uid.collectAsStateWithLifecycle()
    var error by remember(drillId, entitlement.uid) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var upgrade by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(entitlement.uid) {
        if (grant != null && graph.authRepository.currentUid != grant) grantModel.revoke()
    }
    LaunchedEffect(drillId, retry) {
        loaded = false
        error = null
        try { drill = graph.drillRepository.byId(drillId) }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; error = "Drill could not be loaded." }
        loaded = true
    }
    LaunchedEffect(drill, entitlement, retry) {
        if (grant == null && entitlement.canStart(drill) && drill?.skill == skill) {
            try { grantModel.grant(graph.drillAccessController.requireAccess(drillId).first); error = null }
            catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message ?: "Access could not be checked." }
        }
    }
    if (grant != null && graph.authRepository.currentUid == grant) { content(); return }
    Column(modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        when {
            !loaded -> { CircularProgressIndicator(); Text("Loading drill…") }
            error != null -> { Text(requireNotNull(error)); Button({ retry++ }) { Text("Retry") } }
            drill == null || drill?.skill != skill -> Text("Drill not found.")
            entitlement.canStart(drill) || !entitlement.resolved -> { CircularProgressIndicator(); Text("Checking drill access…") }
            else -> { Icon(Icons.Rounded.Lock, null); Text("This drill requires Pro", style = MaterialTheme.typography.titleLarge); Text("Free includes five starter drills."); Button({ upgrade = true }) { Text("View Pro") } }
        }
        TextButton(onBack) { Text("Back to drills") }
        Spacer(Modifier.weight(1f))
    }
    if (upgrade) DrillUpgradeDialog { upgrade = false }
}

@Composable fun DrillUpgradeDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Unlock the full drill library") },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Free includes one starter drill for each skill. Pro includes all 15 drill guides and workouts.")
            ProPlanCard()
        } }, confirmButton = { TextButton(onDismiss) { Text("Close") } })
}

@Composable private fun DrillPreview(drill: Drill, entitlement: DrillEntitlement, onClick: () -> Unit) {
    Surface(onClick, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(drill.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (drill.id !in DrillAccessPolicy.starterIds) {
                    if (!entitlement.canStart(drill)) Icon(Icons.Rounded.Lock, "Pro drill", Modifier.size(18.dp))
                    Text(" Pro", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                } else Text("Free", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            Text("${stringResource(drill.skill.displayName)} · ${stringResource(drill.level.displayName)}")
            Text("${drill.targetReps} target reps · ${drill.estimatedMinutes} min", style = MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable private fun ModuleHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
    }
}
@Composable private fun ModuleCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
@Composable private fun ModuleError(message: String, onRetry: () -> Unit, action: String = "Retry") {
    ModuleCard { Text(message, color = MaterialTheme.colorScheme.error); TextButton(onRetry) { Text(action) } }
}
@Composable private fun WorkoutNumber(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    var editing by rememberSaveable(label) { mutableStateOf(false) }
    var input by rememberSaveable(label) { mutableStateOf(value.toString()) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        TextButton(enabled = value > min, onClick = { onChange((value - 1).coerceAtLeast(min)) }) { Text("−") }
        TextButton(onClick = { input = value.toString(); editing = true }) { Text(value.toString(), fontWeight = FontWeight.Bold) }
        TextButton(enabled = value < max, onClick = { onChange((value + 1).coerceAtMost(max)) }) { Text("+") }
    }
    if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text(label) },
        text = { OutlinedTextField(input, { input = it.filter(Char::isDigit).take(3) }, singleLine = true,
            label = { Text("$min–$max") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) },
        confirmButton = { TextButton(enabled = input.toIntOrNull()?.let { it in min..max } == true,
            onClick = { onChange(input.toInt()); editing = false }) { Text("Apply") } },
        dismissButton = { TextButton({ editing = false }) { Text("Cancel") } })
}
private fun formatDrillTime(ms: Long): String = String.format(Locale.ROOT, "%d:%02d", ms.coerceAtLeast(0) / 60_000, (ms.coerceAtLeast(0) / 1000) % 60)
