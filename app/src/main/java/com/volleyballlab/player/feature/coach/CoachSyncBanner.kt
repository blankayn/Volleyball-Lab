package com.volleyballlab.player.feature.coach

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.volleyballlab.player.VolleyballLabApplication
import kotlinx.coroutines.launch

/** Visible even if a coach screen has not finished its first cloud read. */
@Composable
fun CoachSyncBanner(coach: Boolean) {
    val context = LocalContext.current
    val repository = remember(context) {
        (context.applicationContext as VolleyballLabApplication).container.coachRepository
    }
    val error by repository.syncError.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var retrying by remember { mutableStateOf(false) }
    error?.let { message ->
        Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !retrying, onClick = {
                    repository.retrySync()
                    if (coach) scope.launch {
                        retrying = true
                        try { repository.publishListing() } finally { retrying = false }
                    }
                }) { Text("Retry connection") }
            }
        }
    }
}
