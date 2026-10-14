package com.volleyballlab.player.feature.coachmode

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.feature.train.result.AnnotatedSessionVideo
import kotlinx.coroutines.CancellationException

@Composable
internal fun SharedRecordingCard(session: SessionResult, ownerUid: String, modifier: Modifier = Modifier) {
    val container = (LocalContext.current.applicationContext as VolleyballLabApplication).container
    var path by remember(session.id, ownerUid) { mutableStateOf<String?>(null) }
    var loading by remember(session.id, ownerUid) { mutableStateOf(true) }
    var error by remember(session.id, ownerUid) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(session.id, ownerUid, retry) {
        loading = true; error = null
        try { path = container.sharedRecordingStore.download(session, ownerUid) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Could not open the recording. Check your connection and retry." }
        finally { loading = false }
    }
    CoachCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Recording feedback", style = MaterialTheme.typography.titleMedium)
            when {
                loading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Opening recording…") }
                error != null -> { Text(requireNotNull(error)); TextButton(onClick = { retry++ }) { Text("Retry") } }
                path != null -> AnnotatedSessionVideo(session, requireNotNull(path))
                else -> Text("This session includes a form summary. No recording was shared.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
