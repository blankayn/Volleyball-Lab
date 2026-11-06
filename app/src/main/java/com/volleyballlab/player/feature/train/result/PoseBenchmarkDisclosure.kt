package com.volleyballlab.player.feature.train.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.R
import com.volleyballlab.player.pose.PoseBenchmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PoseBenchmarkDisclosure() {
    val context = LocalContext.current.applicationContext
    val benchmark by produceState<PoseBenchmark?>(null, context) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(PoseBenchmark.ASSET).bufferedReader().use { PoseBenchmark.parse(it.readText()) }
            }.getOrNull()
        }
    }
    var open by rememberSaveable { mutableStateOf(false) }
    val result = benchmark ?: return
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.pose_benchmark_open)) }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.pose_benchmark_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.pose_benchmark_value, result.mpjpeMm),
                        style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.pose_benchmark_definition))
                    Text(stringResource(R.string.pose_benchmark_sample, result.referenceFrames, result.jointPairs))
                    Text(stringResource(R.string.pose_benchmark_scope))
                    Text(stringResource(R.string.pose_benchmark_session))
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.pose_benchmark_close)) }
            },
        )
    }
}
