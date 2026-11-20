package com.volleyballlab.player.feature.train.drill

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.volleyballlab.player.R
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.data.drill.DrillEntitlement
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.feature.profile.ProPlanCard
import com.volleyballlab.player.feature.train.skill.DrillPostureVisual
import com.volleyballlab.player.ui.component.GhostLayout
import com.volleyballlab.player.ui.component.GhostLoadingScreen
import com.volleyballlab.player.ui.component.StudentCard
import com.volleyballlab.player.ui.component.StudentPageHeading
import com.volleyballlab.player.ui.component.DrillArt
import com.volleyballlab.player.ui.component.skillVisual
import com.volleyballlab.player.ui.component.studentBackground
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException

@Composable
fun DrillLibraryScreen(
    onDrill: (String) -> Unit,
    onCamera: (Drill) -> Unit,
    onResume: (DrillPracticeDraft) -> Unit,
    onSummary: (String) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val model: DrillLibraryViewModel = viewModel(factory = DrillLibraryViewModel.Factory)
    val state by model.state.collectAsStateWithLifecycle()
    val picks by model.picks.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var skillFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var levelFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var history by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var upgrade by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    if (!state.loaded && state.error == null) {
        GhostLoadingScreen(GhostLayout.List, contentPadding)
        return
    }
    val query = search.trim()
    val filtered = state.drills.filter {
        (skillFilter == null || it.skill.name == skillFilter) &&
            (levelFilter == null || it.level.name == levelFilter) &&
            (query.isEmpty() || it.name.contains(query, true) || it.description.contains(query, true))
    }
    val filtering = skillFilter != null || levelFilter != null || query.isNotEmpty()
    // The capture screen re-checks access itself, so an unresolved plan goes
    // through to its "checking access" state instead of a paywall.
    val startCamera: (Drill) -> Unit = { drill ->
        if (!state.entitlement.canStart(drill) && state.entitlement.resolved) upgrade = true else onCamera(drill)
    }
    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize().studentBackground(),
    ) {
        item { LibraryHeader(onBack) }
        item {
            PlanStrip(
                pro = state.entitlement.plan == UserPlan.Pro,
                starters = state.drills.count { it.id in DrillAccessPolicy.starterIds },
                total = state.drills.size,
                onUpgrade = { upgrade = true },
            )
        }
        item { LibraryTabs(history, state.history.size) { history = it } }
        state.error?.let { error -> item { ModuleError(error, model::refresh) } }
        state.draft?.let { draft -> item {
            val drill = state.drills.firstOrNull { it.id == draft.drillId }
            DraftCard(draft, state.entitlement.canStart(drill), onResume = { onResume(draft) }, onDiscard = { discard = true })
        } }
        if (history) {
            item { LibraryNote("Workouts you logged by hand. Camera checks are saved in Progress.") }
            if (state.history.isEmpty()) item {
                EmptyLibraryCard(Icons.Rounded.FitnessCenter, "No workouts yet", "Finish a set in any drill to save your first workout here.")
            }
            items(state.history, key = { it.id }) { record -> HistoryCard(record) { onSummary(record.id) } }
        } else {
            if (!filtering && picks.isNotEmpty()) item {
                RecommendedSection(picks, onOpen = { onDrill(it.id) }, onCamera = startCamera)
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text("Search drills") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = {
                            if (search.isNotEmpty()) IconButton({ search = "" }) { Icon(Icons.Rounded.Close, "Clear search") }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                    )
                    FilterRow {
                        FilterChip(skillFilter == null, { skillFilter = null }, label = { Text("All skills") })
                        Skill.All.forEach { skill ->
                            FilterChip(
                                selected = skillFilter == skill.name,
                                onClick = { skillFilter = if (skillFilter == skill.name) null else skill.name },
                                label = { Text(stringResource(skill.displayName)) },
                                leadingIcon = { SkillDot(skill) },
                            )
                        }
                    }
                    FilterRow {
                        FilterChip(levelFilter == null, { levelFilter = null }, label = { Text("All levels") })
                        DrillLevel.entries.forEach { level ->
                            FilterChip(
                                selected = levelFilter == level.name,
                                onClick = { levelFilter = if (levelFilter == level.name) null else level.name },
                                label = { Text(stringResource(level.displayName)) },
                            )
                        }
                    }
                }
            }
            item {
                Row(Modifier.padding(start = 20.dp, end = 8.dp).fillMaxWidth().heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(pluralStringResource(R.plurals.drill_library_count, filtered.size, filtered.size),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (filtering) TextButton({ search = ""; skillFilter = null; levelFilter = null }) { Text("Clear filters") }
                }
            }
            if (filtered.isEmpty()) item {
                EmptyLibraryCard(Icons.Rounded.Search, "No drills match", "Try another skill or level, or clear the search.")
            }
            items(filtered, key = { it.id }) { drill ->
                val locked = !state.entitlement.canStart(drill)
                DrillLibraryCard(drill = drill, locked = locked, onOpen = { onDrill(drill.id) }, onCamera = { startCamera(drill) })
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard unfinished workout?") },
        text = { Text("This removes the unfinished workout and its confirmed sets.") },
        confirmButton = { TextButton({ model.discardDraft(); discard = false }) { Text("Discard") } },
        dismissButton = { TextButton({ discard = false }) { Text("Keep workout") } })
    if (upgrade) DrillUpgradeDialog { upgrade = false }
}

@Composable
fun DrillDetailScreen(
    drillId: String,
    onCamera: (Drill) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val model: DrillLibraryViewModel = viewModel(factory = DrillLibraryViewModel.Factory)
    val state by model.state.collectAsStateWithLifecycle()
    var upgrade by rememberSaveable { mutableStateOf(false) }
    val drill = state.drills.firstOrNull { it.id == drillId }
    if (!state.loaded && state.error == null) { GhostLoadingScreen(GhostLayout.Detail, contentPadding); return }
    val guide = drill?.let(DrillGuideCatalog::forDrill)
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        item { ModuleHeader(drill?.name ?: "Drill", onBack) }
        state.error?.let { item { ModuleError(it, model::refresh) } }
        if (drill == null || guide == null) {
            item { ModuleCard { Text("Drill not found.") } }
        } else {
            item { DrillHeroArt(drill) }
            item { ModuleCard {
                Text("${stringResource(drill.skill.displayName)} · ${stringResource(drill.level.displayName)}", color = MaterialTheme.colorScheme.primary)
                Text("${drill.targetReps} target reps · About ${drill.estimatedMinutes} min")
                if (state.entitlement.canStart(drill)) {
                    CameraPracticeButton { onCamera(drill) }
                    Text("Your form is checked live as you practise. Results are saved in Progress.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
                    Text("Posture reference. Camera feedback focuses on your body position and alignment.", style = MaterialTheme.typography.bodySmall)
                } }
                item { ModuleCard {
                    Text("Drill instructions", style = MaterialTheme.typography.titleMedium)
                    guide.steps.forEach { step -> Text(step.title, fontWeight = FontWeight.SemiBold); Text(step.action) }
                    Text("Common mistakes", style = MaterialTheme.typography.titleMedium)
                    guide.mistakes.forEach { Text("• $it") }
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

@Composable private fun LibraryHeader(onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        IconButton(onBack, Modifier.padding(start = 8.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back))
        }
        StudentPageHeading(
            eyebrow = "TRAINING GUIDES",
            title = "Drill library",
            subtitle = "Learn each drill step by step, then check your form on camera.",
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

@Composable private fun PlanStrip(pro: Boolean, starters: Int, total: Int, onUpgrade: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = colors.primary.copy(alpha = .08f), contentColor = colors.onSurface) {
        Row(Modifier.heightIn(min = 52.dp).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (pro) Icons.Rounded.WorkspacePremium else Icons.Rounded.LockOpen, null,
                tint = colors.primary, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(if (pro) "Pro plan" else "Free plan", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(if (pro) "All $total drills unlocked" else "$starters of $total drills unlocked, one for each skill",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (!pro) TextButton(onUpgrade) { Text("See Pro") }
        }
    }
}

@Composable private fun LibraryTabs(history: Boolean, historyCount: Int, onSelect: (Boolean) -> Unit) {
    Surface(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(4.dp)) {
            LibraryTab("Drills", Icons.AutoMirrored.Rounded.MenuBook, !history, Modifier.weight(1f)) { onSelect(false) }
            LibraryTab(if (historyCount > 0) "History · $historyCount" else "History", Icons.Rounded.History, history,
                Modifier.weight(1f)) { onSelect(true) }
        }
    }
}

@Composable private fun LibraryTab(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp).semantics { role = Role.Tab },
        shape = CircleShape,
        color = if (selected) colors.surfaceContainerLowest else Color.Transparent,
        contentColor = if (selected) colors.primary else colors.onSurfaceVariant,
        shadowElevation = if (selected) 1.dp else 0.dp,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        }
    }
}

@Composable private fun DraftCard(draft: DrillPracticeDraft, canResume: Boolean, onResume: () -> Unit, onDiscard: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("WORKOUT IN PROGRESS", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
            Text(draft.drillName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${draft.sets.size} of ${draft.config.sets} sets confirmed · ${draft.totalReps} reps logged",
                style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(
                progress = { (draft.sets.size / draft.config.sets.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = colors.primary,
                trackColor = colors.onPrimaryContainer.copy(alpha = .12f),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = canResume, onClick = onResume, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Resume")
                }
                TextButton(onDiscard, colors = ButtonDefaults.textButtonColors(contentColor = colors.onPrimaryContainer)) { Text("Discard") }
            }
            if (!canResume) Text("Premium workouts need current Pro access to resume.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** One drill with its pose illustration and both ways to train it. */
@Composable private fun DrillLibraryCard(drill: Drill, locked: Boolean, onOpen: () -> Unit, onCamera: () -> Unit) {
    val visual = skillVisual(drill.skill)
    val colors = MaterialTheme.colorScheme
    StudentCard(onClick = onOpen, accent = visual.accent, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DrillTile(drill.id, drill.skill, 64, dimmed = locked)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(drill.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        AccessBadge(pro = drill.id !in DrillAccessPolicy.starterIds, locked = locked)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SkillDot(drill.skill)
                        Text("${stringResource(drill.skill.displayName)} · ${stringResource(drill.level.displayName)}",
                            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                    Text(if (locked) "Unlock with Pro for the full guide, workout and camera check." else drill.description,
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        MetaItem(Icons.Rounded.Repeat, pluralStringResource(R.plurals.drill_reps, drill.targetReps, drill.targetReps))
                        MetaItem(Icons.Rounded.Timer, stringResource(R.string.drill_duration, drill.estimatedMinutes))
                    }
                }
            }
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = .5f))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onOpen) {
                    Text("View guide")
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(16.dp))
                }
                Button(onCamera, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    Icon(if (locked) Icons.Rounded.Lock else Icons.Rounded.CameraAlt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Camera check", maxLines = 1)
                }
            }
        }
    }
}

@Composable private fun CameraPracticeButton(onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth(), contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
        Icon(Icons.Rounded.CameraAlt, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Practice with camera")
    }
}

@Composable private fun RecommendedSection(picks: List<DrillPick>, onOpen: (Drill) -> Unit, onCamera: (Drill) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text("Recommended for you", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text("Picked from your focus skill, your level and what you have trained so far.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Equal-height cards, so every camera button lines up along the bottom.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).height(IntrinsicSize.Max).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            picks.forEach { pick ->
                RecommendedCard(pick, { onOpen(pick.drill) }, { onCamera(pick.drill) }, Modifier.width(264.dp).fillMaxHeight())
            }
        }
    }
}

@Composable private fun RecommendedCard(pick: DrillPick, onOpen: () -> Unit, onCamera: () -> Unit, modifier: Modifier) {
    val drill = pick.drill
    val colors = MaterialTheme.colorScheme
    StudentCard(onClick = onOpen, accent = skillVisual(drill.skill).accent, modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DrillTile(drill.id, drill.skill, 48)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(drill.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${stringResource(drill.skill.displayName)} · ${stringResource(drill.level.displayName)}",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            Text(pickReason(pick.reason), style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onCamera, Modifier.fillMaxWidth(), contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                Icon(Icons.Rounded.CameraAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Camera check", maxLines = 1)
            }
        }
    }
}

@Composable private fun pickReason(reason: DrillPickReason): String = when (reason) {
    is DrillPickReason.Focus -> {
        val level = stringResource(reason.level.label)
        if (reason.measured) "Your focus skill, at the $level level your camera scores show."
        else "Your focus skill, at the $level level from your answers."
    }
    is DrillPickReason.NextStep -> "Your next step once ${reason.after.name} feels easy."
    is DrillPickReason.Untrained -> "You haven't trained ${stringResource(reason.skill.displayName)} yet."
    is DrillPickReason.LeastTrained -> "${stringResource(reason.skill.displayName)} is your least-trained skill so far."
}

@Composable private fun HistoryCard(record: DrillPracticeRecord, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val date = DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(record.startedAtEpochMs))
    StudentCard(onClick = onClick, accent = skillVisual(record.skill).accent,
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DrillTile(record.drillId, record.skill, 52)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(record.drillName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(if (record.completed) Icons.Rounded.CheckCircle else Icons.Rounded.Timelapse, null,
                        tint = if (record.completed) colors.primary else colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    Text("${if (record.completed) "Completed" else "Partial"} · ${record.sets.size}/${record.config.sets} sets · ${record.totalReps} reps",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Text("${formatDrillTime(record.durationMs)} · $date", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.onSurfaceVariant)
        }
    }
}

@Composable private fun EmptyLibraryCard(icon: ImageVector, title: String, body: String) {
    StudentCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable private fun LibraryNote(text: String) {
    Text(text, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun FilterRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable private fun DrillTile(drillId: String, skill: Skill, size: Int, dimmed: Boolean = false) {
    val visual = skillVisual(skill)
    val poseArtwork = DrillArt.forDrill(drillId) ?: drillPoseIcon(drillId)
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 3.5f).dp)).background(visual.wash), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(poseArtwork ?: visual.illustration),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().padding(if (poseArtwork == null) 6.dp else 3.dp),
            contentScale = ContentScale.Fit,
            alpha = if (dimmed) .5f else 1f,
        )
    }
}

/** A large picture of the drill's position at the top of its guide, shown even when the guide is locked. */
@Composable private fun DrillHeroArt(drill: Drill) {
    val visual = skillVisual(drill.skill)
    val artwork = DrillArt.forDrill(drill.id) ?: drillPoseIcon(drill.id) ?: visual.illustration
    Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(220.dp).clip(MaterialTheme.shapes.large).background(visual.wash),
        contentAlignment = Alignment.Center) {
        Image(painterResource(artwork), "${drill.name} position", Modifier.fillMaxSize().padding(12.dp), contentScale = ContentScale.Fit)
    }
}

@Composable private fun SkillDot(skill: Skill) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(skillVisual(skill).accent))
}

@Composable private fun AccessBadge(pro: Boolean, locked: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = CircleShape, color = if (pro) colors.primary.copy(alpha = .12f) else colors.surfaceContainerHighest,
        contentColor = if (pro) colors.primary else colors.onSurfaceVariant) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (locked) Icon(Icons.Rounded.Lock, "Locked", Modifier.size(12.dp))
            Text(if (pro) "PRO" else "FREE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = .6.sp)
        }
    }
}

@Composable private fun MetaItem(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun formatDrillTime(ms: Long): String = String.format(Locale.ROOT, "%d:%02d", ms.coerceAtLeast(0) / 60_000, (ms.coerceAtLeast(0) / 1000) % 60)
