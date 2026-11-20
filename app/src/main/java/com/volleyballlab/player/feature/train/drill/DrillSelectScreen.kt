package com.volleyballlab.player.feature.train.drill

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.R
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.data.drill.DrillEntitlement
import com.volleyballlab.player.domain.model.Drill
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.model.SkillLevel
import com.volleyballlab.player.domain.model.SkillRecommendation
import com.volleyballlab.player.domain.repository.DrillPracticeRepository
import com.volleyballlab.player.domain.repository.DrillRepository
import com.volleyballlab.player.domain.repository.SessionRepository
import com.volleyballlab.player.feature.train.skill.SkillLearningDialog
import com.volleyballlab.player.ui.component.EntranceAnimation
import com.volleyballlab.player.ui.component.StudentCard
import com.volleyballlab.player.ui.component.skillVisual
import com.volleyballlab.player.ui.component.studentBackground
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Practice habit for one drill, kept separate from skill form scores. */
data class DrillPracticeProgress(
    val practiceCount: Int,
    val completedWorkouts: Int,
    val totalReps: Int,
    val cameraSessionCount: Int,
    val bestCameraScore: Int?,
    val lastPracticedEpochMs: Long?,
)

/** The skill-wide numbers at the top of the path. */
data class SkillPathSummary(val meanScore: Int? = null, val cameraSessions: Int = 0)

enum class PathStepStatus { Strong, Current, UpNext, Locked }

data class PathStep(val drill: Drill, val number: Int, val status: PathStepStatus)

/**
 * One skill's drills as a ladder, easiest first.
 *
 * ## How this differs from the drill library
 *
 * The library is the catalogue: every drill and its full guide.
 * This page is the training route through one skill, and it is driven only by
 * camera scores. A step is *strong* once its best camera score reaches the
 * Advanced band ([SkillLevel.fromScore]); the first open step that is not strong
 * yet is the one to train now. Manual workouts never mark a step strong, because
 * they carry no form score.
 */
object SkillPath {
    fun isStrong(progress: DrillPracticeProgress?): Boolean =
        progress?.bestCameraScore?.let { SkillLevel.fromScore(it) == SkillLevel.Advanced } == true

    fun steps(drills: List<Drill>, progress: Map<String, DrillPracticeProgress>, canStart: (Drill) -> Boolean): List<PathStep> {
        val ordered = drills.sortedBy { it.level.ordinal }
        val current = ordered.firstOrNull { canStart(it) && !isStrong(progress[it.id]) }
        return ordered.mapIndexed { index, drill ->
            PathStep(drill, index + 1, when {
                !canStart(drill) -> PathStepStatus.Locked
                drill == current -> PathStepStatus.Current
                isStrong(progress[drill.id]) -> PathStepStatus.Strong
                else -> PathStepStatus.UpNext
            })
        }
    }
}

class DrillSelectViewModel(
    drillRepository: DrillRepository,
    drillPracticeRepository: DrillPracticeRepository,
    sessionRepository: SessionRepository,
    skill: Skill,
) : ViewModel() {

    val drills: StateFlow<List<Drill>> = flow { emit(drillRepository.drillsFor(skill)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val progress: StateFlow<Map<String, DrillPracticeProgress>> = combine(
        flow { emit(drillRepository.drillsFor(skill)) },
        drillPracticeRepository.history,
        sessionRepository.sessions,
    ) { drills, practiceHistory, sessions ->
        drills.associate { drill ->
            val practices = practiceHistory.filter { it.drillId == drill.id }
            val cameraSessions = sessions.filter { it.drillId == drill.id }
            drill.id to DrillPracticeProgress(
                practiceCount = practices.size,
                completedWorkouts = practices.count { it.completed },
                totalReps = practices.sumOf { it.totalReps },
                cameraSessionCount = cameraSessions.size,
                bestCameraScore = cameraSessions.mapNotNull { it.formScore() }.maxOrNull(),
                lastPracticedEpochMs = (practices.map { it.startedAtEpochMs } +
                    cameraSessions.map { it.startedAtEpochMs }).maxOrNull(),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val summary: StateFlow<SkillPathSummary> = sessionRepository.sessions.map { sessions ->
        SkillPathSummary(SkillRecommendation.meanScoreFor(skill, sessions), sessions.count { it.skill == skill })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SkillPathSummary())

    companion object {
        /**
         * The skill is a navigation argument rather than something this ViewModel
         * looks up, so it is passed to the factory. That keeps the ViewModel a
         * plain constructor-injected class with no SavedStateHandle parsing in it.
         */
        fun factory(skill: Skill): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DrillSelectViewModel(
                    appContainer.drillRepository,
                    appContainer.drillPracticeRepository,
                    appContainer.sessionRepository,
                    skill,
                )
            }
        }
    }
}

@Composable
fun DrillSelectScreen(
    skill: Skill,
    onDrillSelected: (Drill) -> Unit,
    onOpenGuide: (Drill) -> Unit,
    onOpenLibrary: () -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val viewModel: DrillSelectViewModel = viewModel(
        // Keyed by skill so switching skills produces a new ViewModel rather than
        // reusing the previous skill's loaded drill list.
        key = "drills-${skill.name}",
        factory = DrillSelectViewModel.factory(skill),
    )
    val drills by viewModel.drills.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val graph = remember(context) { (context.applicationContext as VolleyballLabApplication).container }
    val entitlement by graph.drillAccessController.entitlement.collectAsStateWithLifecycle(DrillEntitlement())
    var upgrade by remember { mutableStateOf(false) }
    if (upgrade) DrillUpgradeDialog { upgrade = false }
    var learning by remember { mutableStateOf(false) }
    if (learning) SkillLearningDialog(skill) { learning = false }
    val steps = SkillPath.steps(drills, progress, entitlement::canStart)

    LazyColumn(
        modifier = modifier.fillMaxSize().studentBackground(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            IconButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp, top = 4.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back))
            }
        }
        item {
            EntranceAnimation(index = 0) {
                PathHero(skill, summary, steps, onLearn = { learning = true }, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        item {
            Text("Your path", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp))
        }
        itemsIndexed(steps, key = { _, step -> step.drill.id }) { index, step ->
            EntranceAnimation(index = index + 1) {
                PathStepRow(
                    step = step,
                    progress = progress[step.drill.id],
                    last = index == steps.lastIndex,
                    onCamera = { if (step.status == PathStepStatus.Locked) upgrade = true else onDrillSelected(step.drill) },
                    onGuide = { onOpenGuide(step.drill) },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        item { LibraryLink(onOpenLibrary, Modifier.padding(horizontal = 20.dp)) }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** The skill's picture, how far along the athlete is, and what "strong" means here. */
@Composable
private fun PathHero(skill: Skill, summary: SkillPathSummary, steps: List<PathStep>, onLearn: () -> Unit, modifier: Modifier) {
    val visual = skillVisual(skill)
    val strong = steps.count { it.status == PathStepStatus.Strong }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = visual.wash, contentColor = PathInk,
        border = BorderStroke(1.dp, visual.accent.copy(alpha = .18f)), shadowElevation = 2.dp) {
        Box {
            Image(painterResource(visual.illustration), null, contentScale = ContentScale.Fit, alpha = .9f,
                modifier = Modifier.align(Alignment.TopEnd).size(120.dp).padding(top = 10.dp, end = 10.dp))
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SKILL PATH", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Bold, color = visual.accent.darken())
                Text(stringResource(skill.displayName), style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 110.dp))
                Text("Clear each step with a camera score of 80 or more, then move up.",
                    style = MaterialTheme.typography.bodySmall, color = PathMutedInk, modifier = Modifier.padding(end = 110.dp))
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    HeroStat(summary.meanScore?.toString() ?: "–", "avg form")
                    HeroStat("$strong/${steps.size}", "steps strong")
                    HeroStat(summary.cameraSessions.toString(), "sessions")
                }
                LinearProgressIndicator(
                    progress = { if (steps.isEmpty()) 0f else strong / steps.size.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color = visual.accent, trackColor = visual.accent.copy(alpha = .16f),
                )
                OutlinedButton(onLearn, border = BorderStroke(1.dp, PathInk.copy(alpha = .25f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PathInk),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    Icon(Icons.Rounded.School, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Learn correct form")
                }
            }
        }
    }
}

@Composable
private fun HeroStat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = PathMutedInk)
    }
}

/** One rung: a numbered node on a rail, and the drill's camera record beside it. */
@Composable
private fun PathStepRow(
    step: PathStep,
    progress: DrillPracticeProgress?,
    last: Boolean,
    onCamera: () -> Unit,
    onGuide: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val accent = skillVisual(step.drill.skill).accent
    val current = step.status == PathStepStatus.Current
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(32.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.padding(top = 16.dp).size(32.dp).clip(CircleShape).background(when (step.status) {
                    PathStepStatus.Strong, PathStepStatus.Current -> accent
                    else -> colors.surfaceContainerHighest
                }),
                contentAlignment = Alignment.Center,
            ) {
                when (step.status) {
                    PathStepStatus.Strong -> Icon(Icons.Rounded.Check, "Strong", tint = Color.White, modifier = Modifier.size(18.dp))
                    PathStepStatus.Locked -> Icon(Icons.Rounded.Lock, "Pro", tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    else -> Text(step.number.toString(), fontWeight = FontWeight.Bold,
                        color = if (current) Color.White else colors.onSurfaceVariant)
                }
            }
            if (!last) Box(Modifier.padding(top = 4.dp).width(2.dp).weight(1f).clip(CircleShape)
                .background(if (step.status == PathStepStatus.Strong) accent.copy(alpha = .5f) else colors.outlineVariant))
        }
        StudentCard(
            accent = accent,
            border = if (current) BorderStroke(2.dp, accent) else null,
            modifier = Modifier.weight(1f),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("STEP ${step.number} · ${stringResource(step.drill.level.displayName).uppercase()}",
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = .8.sp,
                        color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                    StatusChip(step.status, accent)
                }
                Text(step.drill.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                CameraRecord(progress, accent, step.status == PathStepStatus.Locked)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (step.status) {
                        PathStepStatus.Locked -> OutlinedButton(onCamera) { Text("Unlock with Pro") }
                        PathStepStatus.Current -> Button(onCamera, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                            Icon(Icons.Rounded.CameraAlt, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Train now")
                        }
                        else -> FilledTonalButton(onCamera, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                            Icon(Icons.Rounded.CameraAlt, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (step.status == PathStepStatus.Strong) "Train again" else "Try it")
                        }
                    }
                    if (step.status != PathStepStatus.Locked) TextButton(onGuide) { Text("Guide") }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: PathStepStatus, accent: Color) {
    val colors = MaterialTheme.colorScheme
    val (label, background, content) = when (status) {
        PathStepStatus.Strong -> Triple("Strong", accent.copy(alpha = .14f), accent.darken())
        PathStepStatus.Current -> Triple("Your step", accent, Color.White)
        PathStepStatus.UpNext -> Triple("Up next", colors.surfaceContainerHighest, colors.onSurfaceVariant)
        PathStepStatus.Locked -> Triple("Pro", colors.surfaceContainerHighest, colors.onSurfaceVariant)
    }
    Surface(shape = CircleShape, color = background, contentColor = content) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** Best camera score against the 80 that makes a step strong. Manual workouts are not counted here. */
@Composable
private fun CameraRecord(progress: DrillPracticeProgress?, accent: Color, locked: Boolean) {
    val colors = MaterialTheme.colorScheme
    val best = progress?.bestCameraScore
    val sessions = progress?.cameraSessionCount ?: 0
    if (locked) {
        Text("Pro unlocks this step, its guide and camera scoring.", style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant)
        return
    }
    if (best == null) {
        Text(if (sessions == 0) "Not tried with the camera yet." else "No scored reps yet. Stay in frame for the whole rep.",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        return
    }
    val locale = LocalLocale.current.platformLocale
    val last = progress?.lastPracticedEpochMs?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM", locale))
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Best $best", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(listOfNotNull("$sessions ${if (sessions == 1) "session" else "sessions"}", last?.let { "last $it" }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { (best / STRONG_SCORE.toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
            color = accent, trackColor = accent.copy(alpha = .14f),
        )
        if (best < STRONG_SCORE) Text("${STRONG_SCORE - best} more points to make this step strong.",
            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

/** Where everything this page leaves out lives. */
@Composable
private fun LibraryLink(onOpenLibrary: () -> Unit, modifier: Modifier) {
    StudentCard(onClick = onOpenLibrary, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.AutoMirrored.Rounded.MenuBook, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Drill library", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("Full guides and equipment for every drill in every skill.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

/** Skill accents are tuned for artwork; text in them needs a darker shade to stay readable on the wash. */
private fun Color.darken(): Color = Color(red * .62f, green * .62f, blue * .62f, alpha)

/** Matches the Advanced band in [SkillLevel.fromScore], which is what [SkillPath.isStrong] tests. */
private const val STRONG_SCORE = 80
private val PathInk = Color(0xFF22302A)
private val PathMutedInk = Color(0xFF47534B)
