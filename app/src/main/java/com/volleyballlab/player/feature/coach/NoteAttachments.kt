package com.volleyballlab.player.feature.coach

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.data.coach.validNotePhoto
import com.volleyballlab.player.domain.model.CoachNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The moment in the recording a note points at. Without [onOpen] it is a label only. */
@Composable
internal fun NoteMomentChip(note: CoachNote, onOpen: ((Long) -> Unit)? = null) {
    val at = note.videoMs ?: return
    AssistChip(
        onClick = { onOpen?.invoke(at) },
        enabled = onOpen != null,
        label = { Text(CoachNote.momentLabel(at, note.repNumber)) },
        leadingIcon = { Icon(Icons.Rounded.PlayCircle, contentDescription = if (onOpen != null) "Watch in the recording" else null, Modifier.size(18.dp)) },
    )
}

private sealed interface NotePhotoState {
    data object Loading : NotePhotoState
    data class Ready(val bitmap: Bitmap) : NotePhotoState
    data object Removed : NotePhotoState
    data object Failed : NotePhotoState
}

/** A note's photo: a thumbnail that opens full screen. [canRemove] is the coach's own view. */
@Composable
internal fun NotePhoto(note: CoachNote, canRemove: Boolean = false) {
    if (!note.hasPhoto) return
    val photos = (LocalContext.current.applicationContext as VolleyballLabApplication).container.notePhotos
    var attempt by remember(note.id) { mutableIntStateOf(0) }
    var state by remember(note.id) { mutableStateOf<NotePhotoState>(NotePhotoState.Loading) }
    var open by remember(note.id) { mutableStateOf(false) }
    var confirmRemove by remember(note.id) { mutableStateOf(false) }
    var removeFailed by remember(note.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(note.id, attempt) {
        state = NotePhotoState.Loading
        state = try {
            val source = photos.load(note.id)
            if (source == null) NotePhotoState.Removed
            else decodeNotePhoto(source)?.let { NotePhotoState.Ready(it) } ?: NotePhotoState.Failed
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { NotePhotoState.Failed }
    }
    when (val current = state) {
        NotePhotoState.Loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        NotePhotoState.Removed -> Text("Photo removed by the coach", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        NotePhotoState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("The photo could not be loaded.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { attempt++ }) { Text("Retry") }
        }
        is NotePhotoState.Ready -> {
            Image(current.bitmap.asImageBitmap(), "Photo from the coach. Tap to enlarge.", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { open = true })
            if (canRemove) {
                TextButton(onClick = { confirmRemove = true }) { Text("Remove photo") }
                if (removeFailed) Text("The photo could not be removed. Try again.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (open) NotePhotoViewer(current.bitmap) { open = false }
        }
    }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove this photo?") },
        text = { Text("The player will no longer see it. Your written feedback stays. This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = {
                confirmRemove = false
                scope.launch {
                    try { photos.remove(note.id); removeFailed = false; state = NotePhotoState.Removed }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { removeFailed = true }
                }
            }) { Text("Remove") }
        },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep") } },
    )
}

/** The photo a coach has picked but not yet sent. */
@Composable
internal fun DraftNotePhoto(source: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(null, source) { value = decodeNotePhoto(source) }
    bitmap?.let {
        Image(it.asImageBitmap(), "Photo to send", modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
    }
}

private suspend fun decodeNotePhoto(source: String): Bitmap? = withContext(Dispatchers.Default) {
    if (!validNotePhoto(source)) null
    else runCatching {
        val bytes = Base64.decode(source.substringAfter(','), Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}

@Composable
private fun NotePhotoViewer(bitmap: Bitmap, onClose: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(bitmap.asImageBitmap(), "Photo from the coach", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            IconButton(onClose, Modifier.align(Alignment.TopEnd).padding(16.dp).background(Color.Black.copy(alpha = .55f), RoundedCornerShape(24.dp))) {
                Icon(Icons.Rounded.Close, "Close photo", tint = Color.White)
            }
        }
    }
}
