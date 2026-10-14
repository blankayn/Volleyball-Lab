package com.volleyballlab.player.feature.notifications

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.R
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun NotificationBell() {
    val context = LocalContext.current
    val repository = remember { (context.applicationContext as VolleyballLabApplication).container.notificationRepository }
    val inbox by repository.inbox.collectAsStateWithLifecycle(emptyList())
    var open by remember { mutableStateOf(false) }
    val unreadCount = inbox.count { !it.isRead }
    BadgedBox(badge = {
        if (unreadCount > 0) {
            Badge(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Text(if (unreadCount > 99) "99+" else unreadCount.toString()) }
        }
    }) {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.Notifications, contentDescription = stringResource(R.string.notifications_title))
        }
    }
    if (open) NotificationInboxDialog { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationInboxDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { (context.applicationContext as VolleyballLabApplication).container.notificationRepository }
    val inbox by repository.inbox.collectAsStateWithLifecycle(emptyList())
    val enabled by repository.deliveryEnabled.collectAsStateWithLifecycle(false)
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch { repository.setDeliveryEnabled(granted) }
        if (!granted) message = "System alerts are off. Updates remain in this inbox."
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Notifications", style = MaterialTheme.typography.headlineSmall)
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("System training alerts")
                    Text("Background updates may be delayed by Android battery settings.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(enabled, { value ->
                    if (value && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else scope.launch { repository.setDeliveryEnabled(value) }
                })
            }
            Row {
                TextButton({ scope.launch { repository.markRead() } }) { Text("Mark all read") }
                TextButton(enabled = !refreshing, onClick = {
                    refreshing = true
                    scope.launch {
                        try { kotlinx.coroutines.withTimeout(20_000) { repository.refresh() }; message = null }
                        catch (_: Exception) { message = "Updates couldn't be refreshed. Saved notifications remain available." }
                        finally { refreshing = false }
                    }
                }) { Text("Refresh") }
            }
            if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (inbox.isEmpty()) item { Text("No notifications yet. Analysis, coach feedback, assignments, and milestones will appear here.", Modifier.padding(vertical = 20.dp)) }
                items(inbox, key = { it.id }) { item ->
                    Surface(onClick = { scope.launch { repository.markRead(item.id) } }, shape = MaterialTheme.shapes.medium,
                        color = if (item.isRead) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.primaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.title + if (item.isRead) "" else " · Unread", style = MaterialTheme.typography.titleSmall)
                            Text(item.message, style = MaterialTheme.typography.bodyMedium)
                            Text(Instant.ofEpochMilli(item.createdAtEpochMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, HH:mm")), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
