package com.volleyballlab.player.feature.train.result

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.pose.analysis.CuePlainWords
import com.volleyballlab.player.ui.component.CueGauge
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.feature.train.skill.DrillPostureFrame
import com.volleyballlab.player.pose.review.*
import androidx.compose.ui.res.stringResource

@Composable
internal fun PostureComparisonCard(path: String, drillId: String, review: VideoReview,
    comparison: PostureComparison, onSeek: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Compare front and side", style = MaterialTheme.typography.titleSmall)
        Text("Recorded in separate stages — compare the shape, not the instant.", style = MaterialTheme.typography.bodySmall)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(comparison.front, comparison.side).forEach { pane ->
                    Column(Modifier.weight(1f)) {
                        Text("${pane.angle.name} · ${pane.repNumber?.let { "Rep $it" } ?: "Not captured"}", style = MaterialTheme.typography.labelMedium)
                        val frame by produceState<android.graphics.Bitmap?>(null, path, pane.timestampMs) {
                            value = null
                            value = pane.timestampMs?.let { ReviewStillLoader.load(path, it) }
                        }
                        Box(Modifier.fillMaxWidth().height(180.dp).background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable(enabled = pane.timestampMs != null) { pane.timestampMs?.let(onSeek) }, contentAlignment = Alignment.Center) {
                            frame?.let { Image(it.asImageBitmap(), "${pane.angle.name} recorded posture", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                            if (frame == null) Text(if (pane.timestampMs == null) "${pane.angle.name} view not captured" else "Frame unavailable", Modifier.padding(8.dp))
                            pane.pose?.let { ReviewSkeleton(it, review.width, review.height, listOfNotNull(pane.issue), null, Modifier.fillMaxSize()) }
                        }
                        if (pane.timestampMs != null && pane.pose == null) Text("Skeleton unavailable", style = MaterialTheme.typography.labelSmall)
                        val cue = pane.issue?.let { FormCueCatalog.byId(it.cueId) }
                        Text(if (cue == null) "Not measured from this view" else
                            "${reviewValue(pane.issue!!.measured, cue.unit)} / ${reviewValue(cue.target.start, cue.unit)}–${reviewValue(cue.target.endInclusive, cue.unit)}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (com.volleyballlab.player.domain.model.DrillPostureSamples.forDrill(drillId) != null) {
            Text("Reference posture (illustration)", style = MaterialTheme.typography.labelSmall)
            DrillPostureFrame(drillId, comparison.referencePhase, Modifier.fillMaxWidth().height(130.dp))
        }
        CoachLearningNote(comparison.issue)
    }
}

@Composable
internal fun CoachLearningNote(issue: ReviewIssue, showMeasurements: Boolean = false) {
    val cue = FormCueCatalog.byId(issue.cueId) ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Coach note · ${stringResource(cue.label)}", style = MaterialTheme.typography.titleSmall)
            Text("What: ${ReelComment.what(issue)}")
            Text("Why: ${ReelComment.why(issue.cueId)}")
            Text("Practice: ${ReelComment.practice(issue)}")
            if (showMeasurements) {
                Text("Measured ${reviewValue(issue.measured, cue.unit)} · Practice range ${reviewValue(cue.target.start, cue.unit)}–${reviewValue(cue.target.endInclusive, cue.unit)}",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                val reading = CuePlainWords.read(cue, issue.measured)
                Text(reading.line, style = MaterialTheme.typography.bodySmall)
                CueGauge(reading)
            }
        }
    }
}
