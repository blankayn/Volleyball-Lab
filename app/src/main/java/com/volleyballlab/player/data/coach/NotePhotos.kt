package com.volleyballlab.player.data.coach

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import com.google.firebase.firestore.FirebaseFirestore
import com.volleyballlab.player.data.cloud.CloudSchema
import com.volleyballlab.player.data.cloud.awaitCloud
import com.volleyballlab.player.domain.model.CoachNote
import com.volleyballlab.player.domain.repository.AuthRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val NOTE_PHOTO = Regex("data:image/jpeg;base64,[A-Za-z0-9+/]+=*")

/** The same bound and shape the access rules enforce. */
internal fun validNotePhoto(value: String): Boolean =
    value.length <= CoachNote.MAX_PHOTO_LENGTH && NOTE_PHOTO.matches(value)

/**
 * Reads and removes feedback photos. They are written with their note, in one
 * commit, by [FirestoreCoachRepository.postNote]; only the note's coach and
 * player can open one, and only the coach can remove it.
 */
class NotePhotos(private val db: FirebaseFirestore, private val auth: AuthRepository) {

    /** The photo, or null when the coach has removed it. Failures propagate so the screen can offer a retry. */
    suspend fun load(noteId: String): String? {
        val uid = requireNotNull(auth.currentUid)
        val photo = db.collection(CloudSchema.NOTE_MEDIA).document(noteId).get().awaitCloud().getString("photo")
        check(auth.currentUid == uid) { "The signed-in account changed." }
        return photo?.takeIf(::validNotePhoto)
    }

    suspend fun remove(noteId: String) {
        requireNotNull(auth.currentUid)
        db.collection(CloudSchema.NOTE_MEDIA).document(noteId).delete().awaitCloud()
    }

    companion object {
        private const val MAX_SOURCE_BYTES = 15 * 1024 * 1024
        private const val MAX_SIDE = 1280

        /**
         * Shrinks a picked image to a small upright JPEG. Re-encoding drops the
         * original's metadata, so a photo's location never leaves the phone.
         */
        suspend fun encode(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var read = input.read(buffer)
                while (read > 0 && output.size() <= MAX_SOURCE_BYTES) { output.write(buffer, 0, read); read = input.read(buffer) }
                output.toByteArray()
            } ?: error("That photo could not be opened.")
            require(bytes.size <= MAX_SOURCE_BYTES) { "Choose a photo smaller than 15 MB." }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "That file is not a photo." }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error("That photo could not be opened.")
            val matrix = Matrix()
            val scale = MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
            if (scale < 1f) matrix.postScale(scale, scale)
            when (runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            }
            var bitmap = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (bitmap !== decoded) decoded.recycle()
            try {
                val output = ByteArrayOutputStream()
                // Quality first, then size: a busy photo may need both.
                repeat(3) {
                    for (quality in intArrayOf(82, 70, 58, 46)) {
                        output.reset()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                        val value = "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                        if (validNotePhoto(value)) return@withContext value
                    }
                    val smaller = Bitmap.createScaledBitmap(bitmap, (bitmap.width * 0.7f).toInt().coerceAtLeast(1), (bitmap.height * 0.7f).toInt().coerceAtLeast(1), true)
                    if (smaller !== bitmap) bitmap.recycle()
                    bitmap = smaller
                }
                error("That photo is too detailed to attach. Try another one.")
            } finally { bitmap.recycle() }
        }
    }
}
