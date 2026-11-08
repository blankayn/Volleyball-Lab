package com.volleyballlab.player.feature.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import kotlin.math.roundToInt
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
fun ProfileTrainingTools(modifier: Modifier = Modifier, section: ProfileToolSection = ProfileToolSection.Training, publishCoachPhoto: Boolean = false,
    mediaRequest: Boolean? = null, onMediaRequestHandled: () -> Unit = {}, showControls: Boolean = true,
    onMediaBusyChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val graph = remember { (context.applicationContext as VolleyballLabApplication).container }
    val profile by graph.profileRepository.profile.collectAsStateWithLifecycle(AthleteProfile.Anonymous)
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var pickingUid by remember { mutableStateOf<String?>(null) }
    var pickingBanner by remember { mutableStateOf(false) }
    var pendingBannerPath by remember { mutableStateOf<String?>(null) }
    val media = rememberProfileMedia(graph.authRepository.currentUid, coach = publishCoachPhoto)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val uid = pickingUid
        val banner = pickingBanner
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
                    if (banner) {
                        pendingBannerPath = path
                        message = "Adjust your cover photo, then choose Use cover photo."
                    } else {
                    val current = graph.profileRepository.profile.first()
                    graph.profileRepository.update { it.copy(photoPath = path) }
                    current.photoPath?.let { old -> withContext(Dispatchers.IO) {
                        val file = File(old).canonicalFile
                        if (file.parentFile == File(context.filesDir, "profile_photos").canonicalFile) file.delete()
                    } }
                    message = run {
                        try {
                            graph.coachPhotoStore.saveLocalPhoto(path, uid, coach = publishCoachPhoto)
                            "Profile photo saved and synced."
                        } catch (failure: Exception) {
                            if (failure is kotlinx.coroutines.CancellationException) throw failure
                            "Photo saved on this device. Tap Sync photo to retry sharing it."
                        }
                    }
                    }
                } else withContext(Dispatchers.IO) { File(path).delete() }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                message = failure.message ?: "Photo could not be saved."
            }
            finally { busy = false }
        }
    }
    LaunchedEffect(busy, pickingUid, pendingBannerPath) { onMediaBusyChanged(busy || pickingUid != null || pendingBannerPath != null) }
    LaunchedEffect(mediaRequest) {
        if (mediaRequest != null) {
            if (!busy && pickingUid == null && pendingBannerPath == null) {
                pickingBanner = mediaRequest
                pickingUid = graph.authRepository.currentUid
                if (pickingUid != null) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            onMediaRequestHandled()
        }
    }
    pendingBannerPath?.let { path ->
        BannerCropDialog(
            path = path,
            busy = busy,
            onDismiss = {
                if (!busy) {
                    pendingBannerPath = null
                    scope.launch(Dispatchers.IO) { File(path).delete() }
                }
            },
            onSave = { zoom, panX, panY ->
                if (!busy) scope.launch {
                    val uid = graph.authRepository.currentUid
                    if (uid == null) {
                        message = "Sign in again to save your cover photo."
                        pendingBannerPath = null
                        withContext(Dispatchers.IO) { File(path).delete() }
                        return@launch
                    }
                    busy = true
                    var croppedPath: String? = null
                    try {
                        val selectedCrop = withContext(Dispatchers.IO) { cropBannerToFile(path, zoom, panX, panY) }
                        croppedPath = selectedCrop
                        check(graph.authRepository.currentUid == uid) { "Sign in again to save your cover photo." }
                        graph.coachPhotoStore.saveLocalPhoto(selectedCrop, uid, banner = true, coach = publishCoachPhoto)
                        message = "Cover photo saved and synced."
                    } catch (failure: Exception) {
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        message = failure.message ?: "Could not save the cover photo. Please retry."
                    } finally {
                        withContext(Dispatchers.IO) { File(path).delete(); croppedPath?.let { File(it).delete() } }
                        pendingBannerPath = null
                        busy = false
                    }
                }
            },
        )
    }
    if (!showControls) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        return
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = !busy && pickingUid == null, modifier = Modifier.heightIn(min = 48.dp), onClick = { pickingBanner = false; pickingUid = graph.authRepository.currentUid; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Choose photo") }
                if (profile.photoPath != null || !media.photo.isNullOrBlank()) TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        val uid = graph.authRepository.currentUid ?: return@launch
                        busy = true
                        try {
                        graph.coachPhotoStore.saveLocalPhoto(null, uid, coach = publishCoachPhoto)
                        check(graph.authRepository.currentUid == uid)
                        val old = graph.profileRepository.profile.first().photoPath
                        graph.profileRepository.update { it.copy(photoPath = null) }
                        old?.let { path -> withContext(Dispatchers.IO) {
                            val file = File(path).canonicalFile
                            if (file.parentFile == File(context.filesDir, "profile_photos").canonicalFile) file.delete()
                        } }
                        message = "Profile photo removed."
                        } catch (failure: Exception) {
                            if (failure is kotlinx.coroutines.CancellationException) throw failure
                            message = "Could not remove the photo. Check your connection and try again."
                        } finally { busy = false }
                    }
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove photo") }
                if (profile.photoPath != null) TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        val uid = graph.authRepository.currentUid ?: return@launch
                        busy = true
                        try { graph.coachPhotoStore.saveLocalPhoto(profile.photoPath, uid, coach = publishCoachPhoto); message = "Profile photo synced." }
                        catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; message = "Could not sync the photo. Please retry." }
                        finally { busy = false }
                    }
                }) { Text("Sync photo") }
            }
            Text("Profile banner", style = MaterialTheme.typography.titleMedium)
            Text(if (publishCoachPhoto) "Your photo and banner appear on your coach profile and player connection cards." else "Your photo and banner are visible to you and coaches connected to you.", style = MaterialTheme.typography.bodySmall)
            if (!media.banner.isNullOrBlank()) ProfileMediaImage(media.banner, "Profile banner", Modifier.fillMaxWidth().height(110.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy && pickingUid == null, onClick = {
                    pickingBanner = true; pickingUid = graph.authRepository.currentUid
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Choose banner") }
                if (!media.banner.isNullOrBlank()) TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        val uid = graph.authRepository.currentUid ?: return@launch
                        busy = true
                        try { graph.coachPhotoStore.saveLocalPhoto(null, uid, banner = true, coach = publishCoachPhoto); message = "Banner removed." }
                        catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; message = "Could not remove the banner. Please retry." }
                        finally { busy = false }
                    }
                }) { Text("Remove banner") }
            }
            }
            if (section == ProfileToolSection.Training) {
            Text("Export your stored sessions, final scores, cue measurements, and guided-angle estimates.", style = MaterialTheme.typography.bodySmall)
            ProfileExportActions(busy, { exportCsv.launch("volleyball-progress.csv") }, { exportJson.launch("volleyball-progress.json") })
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
internal fun ProfileExportActions(busy: Boolean, onCsv: () -> Unit, onJson: () -> Unit) {
    com.volleyballlab.player.ui.component.ResponsivePair(
        first = { OutlinedButton(enabled = !busy, onClick = onCsv, modifier = it.heightIn(min = 48.dp)) { Text("Export CSV") } },
        second = { OutlinedButton(enabled = !busy, onClick = onJson, modifier = it.heightIn(min = 48.dp)) { Text("Export JSON") } })
}

private data class BannerCropRect(val left: Int, val top: Int, val width: Int, val height: Int)

private fun bannerCropRect(bitmap: Bitmap, zoom: Float, panX: Float, panY: Float): BannerCropRect {
    val aspect = 1024f / 410f
    val fittedWidth = if (bitmap.width.toFloat() / bitmap.height > aspect) bitmap.height * aspect else bitmap.width.toFloat()
    val cropWidth = (fittedWidth / zoom).roundToInt().coerceIn(1, bitmap.width)
    val cropHeight = (cropWidth / aspect).roundToInt().coerceIn(1, bitmap.height)
    val left = (bitmap.width / 2f + panX.coerceIn(-1f, 1f) * (bitmap.width - cropWidth) / 2f - cropWidth / 2f)
        .roundToInt().coerceIn(0, bitmap.width - cropWidth)
    val top = (bitmap.height / 2f + panY.coerceIn(-1f, 1f) * (bitmap.height - cropHeight) / 2f - cropHeight / 2f)
        .roundToInt().coerceIn(0, bitmap.height - cropHeight)
    return BannerCropRect(left, top, cropWidth, cropHeight)
}

private fun cropBannerToFile(path: String, zoom: Float, panX: Float, panY: Float): String {
    val source = BitmapFactory.decodeFile(path) ?: error("Cover photo could not be opened")
    try {
        val crop = bannerCropRect(source, zoom, panX, panY)
        val selected = Bitmap.createBitmap(source, crop.left, crop.top, crop.width, crop.height)
        val output = if (selected.width == 1024 && selected.height == 410) selected else Bitmap.createScaledBitmap(selected, 1024, 410, true)
        if (output !== selected && selected !== source) selected.recycle()
        val sourceFile = File(path)
        val destination = File(sourceFile.parentFile, "${sourceFile.nameWithoutExtension}-cover.jpg")
        try {
            destination.outputStream().use { stream ->
                check(output.compress(Bitmap.CompressFormat.JPEG, 88, stream)) { "Cover photo could not be encoded" }
            }
        } finally { if (output !== source) output.recycle() }
        return destination.absolutePath
    } finally { source.recycle() }
}

@Composable
private fun BannerCropDialog(
    path: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (zoom: Float, panX: Float, panY: Float) -> Unit,
) {
    val bitmap by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
    }
    // ImageBitmap wraps this Android bitmap and Compose may still have a draw
    // frame queued after the dialog leaves composition. Let GC reclaim it so
    // that frame cannot hit a recycled bitmap and crash the process.
    var zoom by remember(path) { mutableStateOf(1f) }
    var panX by remember(path) { mutableStateOf(0f) }
    var panY by remember(path) { mutableStateOf(0f) }
    val previewBitmap = bitmap
    val image = remember(previewBitmap) { previewBitmap?.asImageBitmap() }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Adjust cover photo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Drag the photo to position it. Use zoom to crop closer.", style = MaterialTheme.typography.bodySmall)
                if (previewBitmap == null || image == null) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1024f / 410f), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val sourceBitmap = previewBitmap
                    val previewImage = image
                    Canvas(
                        Modifier.fillMaxWidth()
                            .aspectRatio(1024f / 410f)
                            .clip(RoundedCornerShape(12.dp))
                            .semantics { contentDescription = "Cover photo crop preview" }
                            .pointerInput(sourceBitmap, zoom) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val rect = bannerCropRect(sourceBitmap, zoom, panX, panY)
                                    val horizontalRoom = sourceBitmap.width - rect.width
                                    val verticalRoom = sourceBitmap.height - rect.height
                                    if (horizontalRoom > 0) panX = (panX - dragAmount.x * 2f * rect.width / (size.width * horizontalRoom.toFloat())).coerceIn(-1f, 1f)
                                    if (verticalRoom > 0) panY = (panY - dragAmount.y * 2f * rect.height / (size.height * verticalRoom.toFloat())).coerceIn(-1f, 1f)
                                }
                            },
                    ) {
                        val rect = bannerCropRect(sourceBitmap, zoom, panX, panY)
                        drawImage(
                            image = previewImage,
                            srcOffset = IntOffset(rect.left, rect.top),
                            srcSize = IntSize(rect.width, rect.height),
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            filterQuality = FilterQuality.Medium,
                        )
                    }
                }
                Text("Zoom · ${((zoom * 100).roundToInt())}%", style = MaterialTheme.typography.labelLarge)
                Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 1f..2.5f, enabled = !busy && previewBitmap != null)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && previewBitmap != null, onClick = { onSave(zoom, panX, panY) }) {
                Text(if (busy) "Saving…" else "Use cover photo")
            }
        },
        dismissButton = {
            Row {
                TextButton(enabled = !busy && bitmap != null, onClick = { zoom = 1f; panX = 0f; panY = 0f }) { Text("Reset") }
                TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
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
