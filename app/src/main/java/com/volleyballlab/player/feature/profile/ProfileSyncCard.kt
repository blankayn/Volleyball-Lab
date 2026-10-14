package com.volleyballlab.player.feature.profile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.domain.repository.ProfileSyncStatus
import kotlinx.coroutines.launch

@Composable
fun ProfileSyncCard(modifier: Modifier = Modifier) {
    val repository = (LocalContext.current.applicationContext as VolleyballLabApplication).container.profileRepository
    val status by repository.syncStatus.collectAsStateWithLifecycle(ProfileSyncStatus.Checking)
    val error by repository.syncError.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Account details", style = MaterialTheme.typography.labelLarge)
                Text(when (status) {
                    ProfileSyncStatus.Checking -> "Checking saved details…"
                    ProfileSyncStatus.Saved -> "Saved to your account"
                    ProfileSyncStatus.Saving -> "Saving changes…"
                    ProfileSyncStatus.WaitingForConnection -> "Saved on this phone · waiting for connection"
                    ProfileSyncStatus.Failed -> error ?: "Could not sync changes"
                }, style = MaterialTheme.typography.bodySmall)
            }
            if (status == ProfileSyncStatus.Failed || status == ProfileSyncStatus.WaitingForConnection)
                TextButton(onClick = { scope.launch { repository.retrySync() } }) { Text("Retry") }
        }
    }
}
