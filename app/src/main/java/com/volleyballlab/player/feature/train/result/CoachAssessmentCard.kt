package com.volleyballlab.player.feature.train.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.CoachAssessment
import com.volleyballlab.player.domain.model.CueUnit
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun CoachAssessmentCard(assessment: CoachAssessment, modifier: Modifier = Modifier) {
    val unavailable = stringResource(R.string.coach_assessment_unavailable)
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.coach_assessment_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.coach_assessment_scope), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.coach_assessment_positioning,
                assessment.bodyPositioningScore?.let { "$it/100" } ?: unavailable))
            Text(stringResource(R.string.coach_assessment_coverage, (assessment.evidenceCoverage * 100).roundToInt()),
                style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.coach_assessment_coordination, unavailable), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.coach_assessment_timing, unavailable), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.coach_assessment_confidence, unavailable), style = MaterialTheme.typography.bodySmall)
            assessment.meanMovementDurationMs?.let { duration ->
                Text(stringResource(R.string.coach_assessment_duration, duration / 1000f), style = MaterialTheme.typography.bodySmall)
            }
            assessment.meanStartToMotionPeakMs?.let { peak ->
                Text(stringResource(R.string.coach_assessment_peak, peak / 1000f), style = MaterialTheme.typography.bodySmall)
            }
            assessment.medianStanceWidthShoulderRatio?.let { width ->
                Text(stringResource(R.string.coach_assessment_stance, width, assessment.stanceObservedReps),
                    style = MaterialTheme.typography.bodySmall)
            }
            assessment.medianKneeLoadToArmPeakMs?.let { lag ->
                Text(stringResource(R.string.coach_assessment_motion_lag, lag / 1000f, assessment.timingObservedReps),
                    style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.coach_assessment_mpjpe), style = MaterialTheme.typography.bodySmall)
            if (assessment.overallScore == null) {
                Text(stringResource(R.string.coach_assessment_more_evidence), color = MaterialTheme.colorScheme.primary)
            }
            assessment.corrections.forEach { correction ->
                HorizontalDivider()
                val evidence = correction.evidence
                Text(stringResource(evidence.cue.label), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.coach_assessment_evidence,
                    formatMeasurement(evidence.medianMeasured, evidence.cue.unit), evidence.measuredReps))
                Text(stringResource(R.string.coach_assessment_reference,
                    formatMeasurement(evidence.cue.target.start, evidence.cue.unit),
                    formatMeasurement(evidence.cue.target.endInclusive, evidence.cue.unit)),
                    style = MaterialTheme.typography.bodySmall)
                Text(correction.explanation, style = MaterialTheme.typography.bodySmall)
                Text(correction.instruction, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.coach_assessment_drill, correction.drillName), style = MaterialTheme.typography.bodySmall)
            }
            assessment.improvements.forEach { change ->
                HorizontalDivider()
                val angle = stringResource(when (change.angle) {
                    CameraAngle.Front -> R.string.coach_assessment_front
                    CameraAngle.Side -> R.string.coach_assessment_side
                    CameraAngle.Rear -> R.string.coach_assessment_rear
                    CameraAngle.Unknown -> R.string.coach_assessment_unavailable
                })
                Text(stringResource(R.string.coach_assessment_change, stringResource(change.cue.label), angle,
                    formatMeasurement(change.previousMedian, change.cue.unit),
                    formatMeasurement(change.currentMedian, change.cue.unit)), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun formatMeasurement(value: Float, unit: CueUnit): String = when (unit) {
    CueUnit.Degrees -> String.format(Locale.getDefault(), "%.0f°", value)
    CueUnit.Metres -> String.format(Locale.getDefault(), "%.2f m", value)
    CueUnit.Ratio -> String.format(Locale.getDefault(), "%.0f%%", value * 100)
}
