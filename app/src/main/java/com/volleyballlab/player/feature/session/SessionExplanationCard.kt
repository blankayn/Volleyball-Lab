package com.volleyballlab.player.feature.session

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.functions.FirebaseFunctionsException
import com.volleyballlab.player.R
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.data.ai.SessionExplanationPoint
import com.volleyballlab.player.data.ai.SessionExplanationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SessionExplanationCard(sessionId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember(context) { (context.applicationContext as VolleyballLabApplication).container.sessionExplanationRepository }
    val scope = rememberCoroutineScope()
    var result by remember(sessionId) { mutableStateOf<SessionExplanationResult?>(null) }
    var busy by remember(sessionId) { mutableStateOf(false) }
    var error by remember(sessionId) { mutableStateOf(false) }
    var limited by remember(sessionId) { mutableStateOf(false) }
    var unsupported by remember(sessionId) { mutableStateOf(false) }
    var revision by remember(sessionId) { mutableIntStateOf(0) }
    LaunchedEffect(sessionId) {
        val attempt = ++revision
        busy = true
        try { val loaded = repository.load(sessionId, false); if (attempt == revision) result = loaded }
        catch (failure: Exception) { if (failure is CancellationException) throw failure }
        finally { if (attempt == revision) busy = false }
    }
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.session_ai_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("AI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            val ready = result?.takeIf { it.status == "ready" }
            when {
                ready != null -> {
                    Text(ready.overview, style = MaterialTheme.typography.bodyMedium)
                    ExplanationPoints(stringResource(R.string.session_ai_strengths), ready.strengths, false)
                    ExplanationPoints(stringResource(R.string.session_ai_priorities), ready.priorities, true)
                    if (ready.limitedCueCount > 0) Text(stringResource(R.string.session_ai_limited), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                result?.status == "insufficient" -> Text(stringResource(R.string.session_ai_insufficient), style = MaterialTheme.typography.bodyMedium)
                else -> {
                    Text(stringResource(R.string.session_ai_body), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.session_ai_consent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = !busy, onClick = {
                        val attempt = ++revision
                        busy = true; error = false; limited = false; unsupported = false
                        scope.launch {
                            try { val loaded = repository.load(sessionId, true); if (attempt == revision) result = loaded }
                            catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                error = true
                                limited = failure is FirebaseFunctionsException && failure.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED
                                unsupported = failure is FirebaseFunctionsException && failure.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION
                            } finally { if (attempt == revision) busy = false }
                        }
                    }) {
                        if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(9.dp)) }
                        Text(stringResource(if (busy) R.string.session_ai_loading else R.string.session_ai_generate))
                    }
                    if (error) Text(stringResource(when {
                        limited -> R.string.session_ai_rate_limit
                        unsupported -> R.string.session_ai_unsupported
                        else -> R.string.session_ai_unavailable
                    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ExplanationPoints(title: String, points: List<SessionExplanationPoint>, practice: Boolean) {
    if (points.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        points.forEach { point ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(point.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(point.explanation, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.session_ai_measured_reps, point.measuredReps), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (practice) Text(point.tip, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
