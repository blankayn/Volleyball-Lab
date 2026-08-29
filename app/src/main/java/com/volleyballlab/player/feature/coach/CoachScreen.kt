package com.volleyballlab.player.feature.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.R
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.domain.model.FormCue
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.repository.SessionRepository
import com.volleyballlab.player.ui.component.EntranceAnimation
import com.volleyballlab.player.ui.component.VbSectionHeader
import com.volleyballlab.player.ui.component.scoreColor
import com.volleyballlab.player.ui.component.SkillAccentRule
import com.volleyballlab.player.ui.theme.VbButtonShape
import com.volleyballlab.player.ui.theme.vbColors
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * One cue, aggregated across every rep the athlete has recorded of it.
 *
 * @param meanScore `0f..1f`, the mean of the per-rep cue scores.
 * @param measuredReps how many reps actually produced a measurement. Shown in the
 *   UI because advice built on four reps and advice built on ninety are not the
 *   same claim, and the athlete is entitled to know which one they are reading.
 */
data class CueInsight(
    val skill: Skill,
    val cue: FormCue,
    val meanScore: Float,
    val measuredReps: Int,
) {
    val scoreOutOfHundred: Int get() = (meanScore * 100).roundToInt().coerceIn(0, 100)
}

/**
 * What the Coach tab has to say, derived entirely from stored sessions.
 *
 * @param unmeasuredFraction share of cue measurements that came back null. High
 *   values mean the camera could not see what it needed to, which is a filming
 *   problem rather than a technique problem, and the screen says so.
 */
data class CoachAdvice(
    val sessionCount: Int,
    val focus: CueInsight?,
    val strength: CueInsight?,
    val perSkill: List<CueInsight>,
    val unmeasuredFraction: Float,
) {
    val hasAdvice: Boolean get() = focus != null

    val cameraAngleIsHurting: Boolean
        get() = sessionCount > 0 && unmeasuredFraction >= UNMEASURED_WARNING_FRACTION

    companion object {
        val Empty = CoachAdvice(0, null, null, emptyList(), 0f)

        /**
         * Above this share of unmeasurable cues, the framing is costing the
         * athlete real feedback and is worth a note of its own.
         */
        const val UNMEASURED_WARNING_FRACTION = 0.3f

        /**
         * Builds the advice.
         *
         * ## Why cues are pooled per skill rather than per session
         *
         * A single session's weakest cue is often just that session - one bad
         * light, one tired set. Pooling every rep of a cue across every session
         * makes the recommendation a claim about the athlete's technique rather
         * than about one afternoon, and it is what lets the
         * [SessionResult.MIN_REPS_FOR_RECOMMENDATION] floor mean something.
         */
        fun from(sessions: List<SessionResult>): CoachAdvice {
            if (sessions.isEmpty()) return Empty

            var measured = 0
            var unmeasured = 0

            val insights = sessions
                .flatMap { session -> session.cueScores.map { session.skill to it } }
                .onEach { (_, cueScore) -> if (cueScore.isMeasured) measured++ else unmeasured++ }
                .filter { (_, cueScore) -> cueScore.isMeasured }
                .groupBy { (skill, cueScore) -> skill to cueScore.cueId }
                .mapNotNull { (key, scores) ->
                    val (skill, cueId) = key
                    val cue = FormCueCatalog.byId(cueId) ?: return@mapNotNull null
                    if (scores.size < SessionResult.MIN_REPS_FOR_RECOMMENDATION) return@mapNotNull null
                    CueInsight(
                        skill = skill,
                        cue = cue,
                        meanScore = scores.mapNotNull { it.second.score }.average().toFloat(),
                        measuredReps = scores.size,
                    )
                }

            val total = measured + unmeasured

            return CoachAdvice(
                sessionCount = sessions.size,
                focus = insights.minByOrNull { it.meanScore },
                // Only called a strength if it is genuinely good. "Your best cue"
                // on a set of mediocre ones is faint praise dressed as a
                // compliment, and it costs the whole screen its credibility.
                strength = insights
                    .filter { it.meanScore >= STRENGTH_FLOOR }
                    .maxByOrNull { it.meanScore },
                perSkill = insights
                    .groupBy { it.skill }
                    .mapNotNull { (_, forSkill) -> forSkill.minByOrNull { it.meanScore } }
                    .sortedBy { it.meanScore },
                unmeasuredFraction = if (total == 0) 0f else unmeasured.toFloat() / total,
            )
        }

        /** Mean cue score at or above which a cue may be called a strength. */
        private const val STRENGTH_FLOOR = 0.75f
    }
}

private val SessionResult.cueScores get() = reps.flatMap { it.cueScores }

class CoachViewModel(sessionRepository: SessionRepository) : ViewModel() {

    val advice: StateFlow<CoachAdvice> = sessionRepository.sessions
        .map(CoachAdvice::from)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CoachAdvice.Empty,
        )

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { CoachViewModel(appContainer.sessionRepository) }
        }
    }
}

/**
 * The Coach tab.
 *
 * ## Which "Coach" this is
 *
 * Conflict 8 in the capstone document is unresolved: Objective 4 describes Coach
 * Mode as AI personalisation, while every other section describes a human coach
 * reviewing sessions remotely. This screen implements neither of those claims
 * beyond what the app can actually stand behind today. What it does is read the
 * athlete's own recorded cue scores and surface the weakest one with the
 * coaching tip already written for it in [FormCueCatalog].
 *
 * That is a real, useful, honest thing: it is the same analysis the results
 * screen performs, pooled across sessions instead of confined to one. It is
 * deliberately not dressed up as a person, and it says on the screen that it was
 * generated on this device from the athlete's own sessions. When conflict 8 is
 * settled - remote review, a real coach account, a model - this screen gains a
 * second source, and nothing here has to be unpicked first.
 */
@Composable
fun CoachScreen(
    onPractiseSkill: (Skill) -> Unit,
    onStartTraining: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val viewModel: CoachViewModel = viewModel(factory = CoachViewModel.Factory)
    val advice by viewModel.advice.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            EntranceAnimation(index = 0) {
                CoachHeader(advice)
            }
        }

        // The human coach comes first, the on-device advice second.
        //
        // Both are on this tab and they are not the same thing: below is
        // arithmetic over this phone's own recordings, and this card is a person
        // who has written something back. Ordering it first is the claim that when
        // an athlete has a real coach, that coach outranks the app.
        item {
            EntranceAnimation(index = 1) {
                Column {
                    VbSectionHeader(
                        text = stringResource(R.string.coach_link_section),
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    CoachLinkSection(Modifier.padding(horizontal = 20.dp))
                }
            }
        }

        if (!advice.hasAdvice) {
            item {
                EntranceAnimation(index = 2) {
                    NoAdviceCard(
                        sessionCount = advice.sessionCount,
                        onStartTraining = onStartTraining,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            return@LazyColumn
        }

        item {
            EntranceAnimation(index = 2) {
                FocusCard(
                    insight = advice.focus!!,
                    onPractise = { onPractiseSkill(advice.focus!!.skill) },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }

        advice.strength?.let { strength ->
            item {
                EntranceAnimation(index = 3) {
                    StrengthCard(strength, Modifier.padding(horizontal = 20.dp))
                }
            }
        }

        if (advice.cameraAngleIsHurting) {
            item {
                EntranceAnimation(index = 4) {
                    CameraNoteCard(
                        unmeasuredFraction = advice.unmeasuredFraction,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
        }

        if (advice.perSkill.size > 1) {
            item {
                EntranceAnimation(index = 5) {
                    VbSectionHeader(
                        text = stringResource(R.string.coach_section_by_skill),
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            itemsIndexed(advice.perSkill) { index, insight ->
                EntranceAnimation(index = index + 6) {
                    SkillNoteRow(
                        insight = insight,
                        onClick = { onPractiseSkill(insight.skill) },
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.coach_provenance),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun CoachHeader(advice: CoachAdvice) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.nav_coach),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = if (advice.sessionCount > 0) {
                pluralStringResource(
                    R.plurals.coach_subtitle,
                    advice.sessionCount,
                    advice.sessionCount,
                )
            } else {
                stringResource(R.string.coach_subtitle_none)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The one thing to work on next.
 *
 * Exactly one, and stated as an instruction rather than a chart. The results
 * screen already shows the athlete every cue they scored; the value this screen
 * adds is choosing, and a "top three weaknesses" list would give that choice
 * straight back to the person who came here to be told.
 */
@Composable
private fun FocusCard(
    insight: CueInsight,
    onPractise: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkillAccentRule(skill = insight.skill, height = 44)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = stringResource(R.string.coach_focus_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(insight.cue.label),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(insight.cue.coachingTip),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            CueScoreBar(insight)

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onPractise,
                shape = VbButtonShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.coach_practise_cta))
            }
        }
    }
}

@Composable
private fun CueScoreBar(insight: CueInsight, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = pluralStringResource(
                    R.plurals.coach_measured_reps,
                    insight.measuredReps,
                    insight.measuredReps,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = insight.scoreOutOfHundred.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = scoreColor(insight.scoreOutOfHundred),
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { insight.meanScore.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = scoreColor(insight.scoreOutOfHundred),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

@Composable
private fun StrengthCard(insight: CueInsight, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.vbColors.success,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = stringResource(R.string.coach_strength_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.vbColors.success,
                )
                Text(
                    text = stringResource(insight.cue.label),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        R.string.coach_strength_detail,
                        stringResource(insight.skill.displayName),
                        insight.scoreOutOfHundred,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Raised when a large share of cues came back unmeasurable.
 *
 * Worth its own card because the athlete's natural reading of a missing cue is
 * that they did something wrong, when the actual cause is where they put the
 * phone - and that is both easier to fix and invisible unless something says it.
 */
@Composable
private fun CameraNoteCard(unmeasuredFraction: Float, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Rounded.CameraAlt,
                contentDescription = null,
                tint = MaterialTheme.vbColors.warning,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = stringResource(R.string.coach_camera_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        R.string.coach_camera_body,
                        (unmeasuredFraction * 100).roundToInt(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SkillNoteRow(
    insight: CueInsight,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkillAccentRule(skill = insight.skill, height = 34)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(insight.skill.displayName),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(insight.cue.label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = insight.scoreOutOfHundred.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = scoreColor(insight.scoreOutOfHundred),
            )
        }
    }
}

/**
 * Shown both when nothing has been recorded and when something has but no cue has
 * yet cleared [SessionResult.MIN_REPS_FOR_RECOMMENDATION]. Those are different
 * situations and get different copy: one needs a first session, the other needs a
 * longer one.
 */
@Composable
private fun NoAdviceCard(
    sessionCount: Int,
    onStartTraining: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.coach_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (sessionCount == 0) {
                    stringResource(R.string.coach_empty_body_no_sessions)
                } else {
                    stringResource(
                        R.string.coach_empty_body_too_few_reps,
                        SessionResult.MIN_REPS_FOR_RECOMMENDATION,
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = onStartTraining, shape = VbButtonShape) {
                Text(stringResource(R.string.coach_empty_cta))
            }
        }
    }
}
