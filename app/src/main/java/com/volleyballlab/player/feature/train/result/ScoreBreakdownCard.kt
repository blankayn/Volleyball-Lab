package com.volleyballlab.player.feature.train.result

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.CoachAssessmentEngine
import com.volleyballlab.player.domain.model.CueUnit
import com.volleyballlab.player.domain.model.ScoreBreakdown
import java.util.Locale
import kotlin.math.roundToInt

/** Closed until asked for: the working behind the session score, line by line. */
@Composable
internal fun ScoreBreakdownCard(breakdown: ScoreBreakdown, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.score_how_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    stringResource(if (open) R.string.score_how_close else R.string.score_how_open))
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (breakdown.rows.isEmpty()) {
                        Muted(stringResource(R.string.score_how_empty, CoachAssessmentEngine.MIN_REPS))
                    } else {
                        Muted(stringResource(R.string.score_how_intro))
                        breakdown.rows.forEach { row -> BreakdownRow(row) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            breakdown.total?.let {
                                stringResource(R.string.score_how_total, breakdown.rows.joinToString(" + ") { row -> one(row.points) }, it)
                            } ?: stringResource(R.string.score_how_no_total, percent(breakdown.coverage), percent(CoachAssessmentEngine.MIN_COVERAGE)),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        )
                        if (breakdown.total != null) Muted(stringResource(R.string.score_how_coverage, percent(breakdown.coverage), percent(CoachAssessmentEngine.MIN_COVERAGE)))
                    }
                    Muted(stringResource(R.string.score_how_limits, CoachAssessmentEngine.MIN_REPS))
                }
            }
        }
    }
}

@Composable
private fun BreakdownRow(row: ScoreBreakdown.Row) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(row.cue.label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.score_how_points, one(row.points)), style = MaterialTheme.typography.bodyMedium)
        }
        Muted(stringResource(R.string.score_how_measured, value(row.median, row.cue.unit),
            value(row.cue.target.start, row.cue.unit), value(row.cue.target.endInclusive, row.cue.unit), row.inRange, row.measuredReps))
        Muted(stringResource(R.string.score_how_working, row.score, one(row.share * 100)))
    }
}

@Composable
private fun Muted(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun one(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)
private fun percent(value: Float): Int = (value * 100).roundToInt()
private fun value(measured: Float, unit: CueUnit): String = when (unit) {
    CueUnit.Degrees -> "${measured.roundToInt()}°"
    CueUnit.Metres -> "${(measured * 100).roundToInt()} cm"
    CueUnit.Ratio -> "${(measured * 100).roundToInt()}%"
}
