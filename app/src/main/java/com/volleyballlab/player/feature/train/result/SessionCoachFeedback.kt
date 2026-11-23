package com.volleyballlab.player.feature.train.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.ui.component.StudentCard
import kotlinx.coroutines.flow.combine

/** Professional feedback stays attached to the session that was shared. */
@Composable
internal fun SessionCoachFeedback(sessionId: String, modifier: Modifier = Modifier, onOpenMoment: ((Long) -> Unit)? = null) {
    val context = LocalContext.current
    val coaches = remember(context) { (context.applicationContext as VolleyballLabApplication).container.coachRepository }
    val feedback by remember(coaches, sessionId) {
        combine(coaches.notesForMe, coaches.mySubmissions) { notes, submissions ->
            notes.filter { it.sessionId == sessionId }.sortedBy { it.createdAtEpochMs } to
                submissions.filter { it.sessionId == sessionId }
        }
    }.collectAsStateWithLifecycle(initialValue = null)
    StudentCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Coach Feedback", style = MaterialTheme.typography.titleMedium)
            val current = feedback
            when {
                current == null -> Text("Loading coach feedback…")
                current.first.isNotEmpty() -> {
                    Text("Feedback Provided", style = MaterialTheme.typography.labelLarge)
                    current.first.forEach { note ->
                        Text(note.text)
                        com.volleyballlab.player.feature.coach.NoteMomentChip(note, onOpenMoment)
                        com.volleyballlab.player.feature.coach.NotePhoto(note)
                    }
                }
                current.second.isNotEmpty() -> Text("Awaiting Coach Feedback")
                else -> Text("Share this session with your coach from My Coach to receive professional feedback.")
            }
        }
    }
}
