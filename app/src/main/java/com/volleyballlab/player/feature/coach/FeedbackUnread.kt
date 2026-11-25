package com.volleyballlab.player.feature.coach

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.volleyballlab.player.R
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.domain.model.CoachNote
import com.volleyballlab.player.domain.model.NotificationEvents
import kotlinx.coroutines.delay

/** How long a note is on screen before it counts as read. */
private const val SEEN_AFTER_MS = 1_500L

/** Ids of the coach's notes the player has not read. */
@Composable
internal fun rememberUnreadFeedback(): Set<String> {
    val context = LocalContext.current
    val flow = remember(context) { (context.applicationContext as VolleyballLabApplication).container.unreadFeedback }
    val unread by flow.collectAsStateWithLifecycle(emptySet())
    return unread
}

/**
 * Call where [notes] are shown. Marks them read once they have been on screen for a
 * moment, and returns the ones to label New, which stay labelled for this visit.
 */
@Composable
internal fun rememberSeenFeedback(notes: List<CoachNote>): Set<String> {
    val context = LocalContext.current
    val repository = remember(context) { (context.applicationContext as VolleyballLabApplication).container.notificationRepository }
    val unread = rememberUnreadFeedback()
    var fresh by remember { mutableStateOf(emptySet<String>()) }
    val here = notes.map { it.id }.filterTo(mutableSetOf()) { it in unread }
    LaunchedEffect(here) {
        if (here.isEmpty()) return@LaunchedEffect
        fresh = fresh + here
        delay(SEEN_AFTER_MS)
        repository.markRead(here.mapTo(mutableSetOf(), NotificationEvents::feedbackId))
    }
    return fresh
}

@Composable
internal fun NewFeedbackChip(note: CoachNote, fresh: Set<String>) {
    if (note.id in fresh) Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = CircleShape) {
        Text(stringResource(R.string.coach_feedback_new), Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

/** [content] with the number of unread notes on it, or plain when there are none. */
@Composable
internal fun FeedbackBadged(content: @Composable () -> Unit) {
    val count = rememberUnreadFeedback().size
    if (count == 0) { content(); return }
    val description = pluralStringResource(R.plurals.coach_feedback_unread, count, count)
    BadgedBox(badge = {
        Badge(Modifier.semantics { contentDescription = description },
            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
            Text(if (count > 99) "99+" else count.toString())
        }
    }) { content() }
}
