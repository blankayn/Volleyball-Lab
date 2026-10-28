package com.volleyballlab.player.data.coach

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.firebase.firestore.FirebaseFirestore
import com.volleyballlab.player.domain.repository.AuthRepository
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import com.volleyballlab.player.data.cloud.awaitCloud
import kotlinx.coroutines.withContext

/** Only the chosen portrait is public. Account preferences stay owner-private. */
class CoachPhotoStore(private val db: FirebaseFirestore, private val auth: AuthRepository) {
    fun photo(uid: String): Flow<String?> = callbackFlow {
        val listener = db.collection("coachPhotos").document(uid).addSnapshotListener { snapshot, error ->
            trySend(if (error == null) snapshot?.getString("photo")?.takeIf(::validCoachPhoto) else null)
        }
        awaitClose { listener.remove() }
    }.distinctUntilChanged()

    suspend fun saveLocalPhoto(path: String?, uid: String) {
        check(auth.currentUid == uid)
        val photo = path?.let { encodePhoto(it) }.orEmpty()
        check(auth.currentUid == uid)
        db.collection("coachPhotos").document(uid)
            .set(mapOf("photo" to photo, "updatedAt" to System.currentTimeMillis())).awaitCloud()
    }

    private suspend fun encodePhoto(path: String): String = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Photo could not be opened" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 384) sample *= 2
        val bitmap = requireNotNull(BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }))
        try {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, output)
            ("data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)).also {
                require(validCoachPhoto(it)) { "Photo is too large to share" }
            }
        } finally { bitmap.recycle() }
    }
}

internal fun validCoachPhoto(value: String): Boolean = value.length <= 120_000 &&
    (value.isEmpty() || value.matches(Regex("data:image/jpeg;base64,[A-Za-z0-9+/]+=*")))
