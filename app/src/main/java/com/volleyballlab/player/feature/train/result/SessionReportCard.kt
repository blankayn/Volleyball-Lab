package com.volleyballlab.player.feature.train.result

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.CoachAssessment
import com.volleyballlab.player.domain.model.CoachAssessmentEngine
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.NextStep
import com.volleyballlab.player.domain.model.NextStepReason
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.analysis.CuePlainWords
import com.volleyballlab.player.domain.model.ObservedCue
import com.volleyballlab.player.ui.theme.vbColors

/**
 * The report in three plain parts: what went well, what to do next, and
 * any measured progress. Each part appears only when its evidence exists.
 */
@Composable
internal fun SessionReportCard(
    repCount: Int,
    assessment: CoachAssessment,
    modifier: Modifier = Modifier,
    skill: Skill? = null,
    next: NextStepAdvice? = null,
    onNextStep: ((NextStep) -> Unit)? = null,
) {
    val observed = assessment.observedCues.filter { it.cue.id != CueId.SpikeJumpHeight }
    val strength = observed.filter {
        it.inRange >= CoachAssessmentEngine.MIN_REPS && it.inRange >= it.measuredReps * 0.6f
    }.maxByOrNull { it.inRange.toFloat() / it.measuredReps }
    val correction = assessment.corrections.firstOrNull()

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.session_report_title), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)

        if (strength != null) ReportPart(
            icon = Icons.Rounded.CheckCircle,
            tint = MaterialTheme.vbColors.success,
            title = stringResource(R.string.session_report_strength),
        ) {
            Text(stringResource(R.string.session_report_in_range,
                stringResource(strength.cue.label), strength.inRange, strength.measuredReps),
                style = MaterialTheme.typography.bodyMedium)
        }

        ReportPart(
            icon = Icons.Rounded.AutoAwesome,
            tint = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.next_step_title),
        ) {
            val step = next?.step
            // Another skill is the whole suggestion; a correction beside it would be two things to do.
            if (step != null && skill != null && step.reason == NextStepReason.OtherSkill) {
                Text(nextStepText(step, skill, false).orEmpty(), style = MaterialTheme.typography.bodyMedium)
                onNextStep?.let { NextStepButton(step, it) }
                return@ReportPart
            }
            if (correction != null) {
                val evidence = correction.evidence
                val below = evidence.belowReference > evidence.aboveReference
                Text(stringResource(
                    if (below) R.string.session_report_below else R.string.session_report_above,
                    stringResource(evidence.cue.label),
                    if (below) evidence.belowReference else evidence.aboveReference,
                    evidence.measuredReps,
                ), style = MaterialTheme.typography.bodyMedium)
                Text(correction.explanation, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // The action is the line to remember, so it gets its own block.
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) {
                    Text(next?.instruction ?: correction.instruction, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface)
                }
                if (step == null) Text(stringResource(R.string.coach_assessment_drill, correction.drillName),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (step == null || step.reason == NextStepReason.Repeat) {
                Text(stringResource(when {
                    repCount == 0 -> R.string.session_report_no_reps
                    observed.isEmpty() -> R.string.session_report_not_enough
                    strength != null -> R.string.session_report_repeat
                    else -> R.string.session_report_mixed
                }), style = MaterialTheme.typography.bodyMedium)
            }
            if (step != null && skill != null) {
                nextStepText(step, skill, correction != null)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                onNextStep?.let { NextStepButton(step, it) }
            }
        }

        assessment.improvements.firstOrNull()?.let { change ->
            ReportPart(
                icon = Icons.AutoMirrored.Rounded.TrendingUp,
                tint = MaterialTheme.colorScheme.tertiary,
                title = stringResource(R.string.session_report_progress),
            ) {
                // Plain words, not "from 142° to 151°": the zone each side of the change.
                val before = CuePlainWords.read(change.cue, change.previousMedian)
                val now = CuePlainWords.read(change.cue, change.currentMedian)
                Text(
                    if (before.zone != now.zone) {
                        stringResource(R.string.session_report_closer, stringResource(change.cue.label), before.verdict, now.verdict)
                    } else {
                        stringResource(R.string.session_report_closer_still, stringResource(change.cue.label), now.verdict)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Text(stringResource(R.string.session_report_basis), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReportPart(icon: ImageVector, tint: Color, title: String, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(36.dp).background(tint.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = tint)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                content()
            }
        }
    }
}

private val ObservedCue.inRange: Int
    get() = measuredReps - belowReference - aboveReference
