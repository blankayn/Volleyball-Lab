package com.volleyballlab.player.feature.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.data.session.ProgressExport
import com.volleyballlab.player.domain.model.*
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import android.media.ExifInterface
import android.graphics.Matrix
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProfilePhoto(path: String?, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { path?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() } }
    }
    bitmap?.let { Image(it.asImageBitmap(), "Profile photo", modifier.clip(CircleShape), contentScale = ContentScale.Crop) }
}

enum class ProfileToolSection { Photo, Training }

@Composable
fun ProfileTrainingTools(modifier: Modifier = Modifier, section: ProfileToolSection = ProfileToolSection.Training) {
    val context = LocalContext.current
    val graph = remember { (context.applicationContext as VolleyballLabApplication).container }
    val profile by graph.profileRepository.profile.collectAsStateWithLifecycle(AthleteProfile.Anonymous)
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var pickingUid by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val uid = pickingUid
        pickingUid = null
        if (uri != null && uid != null) scope.launch {
            busy = true
            try {
                val path = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var read = input.read(buffer)
                        while (read > 0 && output.size() <= 10 * 1024 * 1024) { output.write(buffer, 0, read); read = input.read(buffer) }
                        output.toByteArray()
                    }
                        ?: error("Image could not be opened")
                    require(bytes.size <= 10 * 1024 * 1024) { "Choose a photo smaller than 10 MB" }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Choose a supported image" }
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
                    var decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?: error("Photo could not be decoded")
                    val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                    val matrix = Matrix()
                    when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
                    }
                    if (!matrix.isIdentity) {
                        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                        if (rotated !== decoded) decoded.recycle()
                        decoded = rotated
                    }
                    val folder = File(context.filesDir, "profile_photos").apply { mkdirs() }
                    val key = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }
                    val file = File(folder, "$key-${System.currentTimeMillis()}.jpg")
                    try { file.outputStream().use { decoded.compress(Bitmap.CompressFormat.JPEG, 85, it) } } finally { decoded.recycle() }
                    file.absolutePath
                }
                if (graph.authRepository.currentUid == uid) {
                    val current = graph.profileRepository.profile.first()
                    graph.profileRepository.update { it.copy(photoPath = path) }
                    current.photoPath?.let { old -> withContext(Dispatchers.IO) {
                        val file = File(old).canonicalFile
                        if (file.parentFile == File(context.filesDir, "profile_photos").canonicalFile) file.delete()
                    } }
                    message = "Profile photo saved on this device."
                } else withContext(Dispatchers.IO) { File(path).delete() }
            } catch (failure: Exception) { message = failure.message ?: "Photo could not be saved." }
            finally { busy = false }
        }
    }
    fun export(uri: Uri?, csv: Boolean) {
        if (uri == null || busy) return
        val uid = graph.authRepository.currentUid ?: return
        scope.launch {
            busy = true
            try {
                val sessions = graph.sessionRepository.sessions.first()
                check(graph.authRepository.currentUid == uid)
                withContext(Dispatchers.IO) {
                    val text = if (csv) ProgressExport.csv(sessions) else ProgressExport.json(sessions)
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
                        ?: error("Export destination could not be opened")
                }
                message = "Progress exported. Private video paths are excluded."
            } catch (_: Exception) { message = "Export failed. Choose a writable destination and retry." }
            finally { busy = false }
        }
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { export(it, false) }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { export(it, true) }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = AccountMenuSurface) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (section == ProfileToolSection.Training) {
            Text("Training goals & data", style = MaterialTheme.typography.titleMedium)
            Text(profile.goalSkill?.let { "Focus: ${stringResource(it.displayName)}" } ?: "Focus follows your assessment")
            if (profile.goalText.isNotBlank()) Text(profile.goalText, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy, onClick = { editing = true }) { Text("Edit training goal") }
            }
            if (section == ProfileToolSection.Photo) {
            Text("Profile photo", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy && pickingUid == null, onClick = { pickingUid = graph.authRepository.currentUid; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Choose photo") }
                if (profile.photoPath != null) TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        val old = graph.profileRepository.profile.first().photoPath
                        graph.profileRepository.update { it.copy(photoPath = null) }
                        old?.let { path -> withContext(Dispatchers.IO) {
                            val file = File(path).canonicalFile
                            if (file.parentFile == File(context.filesDir, "profile_photos").canonicalFile) file.delete()
                        } }
                    }
                }) { Text("Remove photo") }
            }
            }
            if (section == ProfileToolSection.Training) {
            Text("Export your stored sessions, final scores, cue measurements, and guided-angle estimates.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { exportCsv.launch("volleyball-progress.csv") }) { Text("Export CSV") }
                OutlinedButton(enabled = !busy, onClick = { exportJson.launch("volleyball-progress.json") }) { Text("Export JSON") }
            }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    if (editing) GoalDialog(profile, onDismiss = { editing = false }, onSave = { skill, text ->
        scope.launch { graph.profileRepository.update { it.copy(goalSkill = skill, goalText = text) }; editing = false }
    })
}

@Composable
private fun GoalDialog(profile: AthleteProfile, onDismiss: () -> Unit, onSave: (Skill?, String) -> Unit) {
    var selected by remember { mutableStateOf(profile.goalSkill) }
    var text by remember { mutableStateOf(profile.goalText) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Training goal") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Choose your focus skill", style = MaterialTheme.typography.labelLarge)
            Skill.All.forEach { skill -> FilterChip(selected == skill, { selected = if (selected == skill) null else skill }, label = { Text(stringResource(skill.displayName)) }) }
            OutlinedTextField(text, { text = it.take(160) }, label = { Text("Personal goal") }, maxLines = 2)
        }
    }, confirmButton = { TextButton({ onSave(selected, text) }) { Text("Save") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
