package com.volleyballlab.player.feature.profile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.volleyballlab.player.VolleyballLabApplication

@Composable
internal fun TrainingSetupPlanCard(onRefreshPlan: () -> Unit) {
    val graph = (LocalContext.current.applicationContext as VolleyballLabApplication).container
    val plan by graph.assessmentRepository.trainingSetup.collectAsStateWithLifecycle(null)
    Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Your starting plan", style = MaterialTheme.typography.titleMedium)
            plan?.let {
                Text("Based on your answers", style = MaterialTheme.typography.labelMedium)
                Text(it.summary, style = MaterialTheme.typography.bodyMedium)
            } ?: Text("Find a starting practice that fits your goals.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onRefreshPlan, modifier = Modifier.fillMaxWidth()) { Text("Refresh my plan") }
            ProfileSyncCard(Modifier.fillMaxWidth())
        }
    }
}
