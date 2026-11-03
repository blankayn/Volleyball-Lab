package com.volleyballlab.player.data.coach

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.volleyballlab.player.domain.repository.AuthRepository
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.map
import com.volleyballlab.player.data.cloud.awaitCloud
import kotlinx.coroutines.withContext

/** Shared profile media for either role; account settings remain owner-private. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CoachPhotoStore(private val db: FirebaseFirestore, private val auth: AuthRepository) {
    private fun observe(path: String): Flow<ProfileMedia?> = callbackFlow {
        val listener = db.document(path).addSnapshotListener { snapshot, error ->
            trySend(if (error == null && snapshot?.exists() == true) ProfileMedia(
                snapshot.getString("photo")?.takeIf { validProfileImage(it) },
                snapshot.getString("banner")?.takeIf { validProfileImage(it, banner = true) },
            ) else null)
        }
        awaitClose { listener.remove() }
    }.distinctUntilChanged()

    fun media(uid: String, coach: Boolean = false): Flow<ProfileMedia> = auth.currentUser.flatMapLatest { viewer ->
        if (viewer == null) flowOf(ProfileMedia()) else combine(
            observe("profileMedia/$uid"),
            if (coach) observe("coachPhotos/$uid") else flowOf(null),
            if (coach && viewer.uid == uid) observe("coaches/$uid/webSettings/preferences") else flowOf(null),
        ) { shared, legacy, private -> resolveProfileMedia(shared, legacy, private) }.onStart { emit(ProfileMedia()) }
    }.distinctUntilChanged()

    fun photo(uid: String): Flow<String?> = media(uid, coach = true).map { it.photo }

    /** Backfill an older phone-only portrait once; never replace a choice or removal. */
    suspend fun syncExistingLocalPhoto(path: String?, uid: String) {
        check(auth.currentUid == uid)
        val ref = db.collection("profileMedia").document(uid)
        if (!shouldSyncExistingPhoto(ref.get(Source.SERVER).awaitCloud().contains("photo"), path)) return
        val coach = db.collection("users").document(uid).get(Source.SERVER).awaitCloud().getString("role") == "COACH"
        val older = if (coach) {
            val legacy = db.collection("coachPhotos").document(uid).get(Source.SERVER).awaitCloud().getString("photo")
            legacy?.takeIf(::validCoachPhoto) ?: db.document("coaches/$uid/webSettings/preferences")
                .get(Source.SERVER).awaitCloud().getString("photo")?.takeIf(::validCoachPhoto)
        } else null
        val photo = older ?: encodePhoto(requireNotNull(path), banner = false)
        check(auth.currentUid == uid)
        db.runTransaction { transaction ->
            val current = transaction.get(ref)
            check(auth.currentUid == uid)
            if (!current.contains("photo")) {
                val values = mapOf("photo" to photo, "updatedAt" to System.currentTimeMillis())
                transaction.set(ref, values, SetOptions.merge())
                if (coach) transaction.set(db.collection("coachPhotos").document(uid), values, SetOptions.merge())
            }
            Unit
        }.awaitCloud()
    }

    suspend fun saveLocalPhoto(path: String?, uid: String, banner: Boolean = false, coach: Boolean = true) {
        check(auth.currentUid == uid)
        val photo = path?.let { encodePhoto(it, banner) }.orEmpty()
        check(auth.currentUid == uid)
        val values = mapOf((if (banner) "banner" else "photo") to photo, "updatedAt" to System.currentTimeMillis())
        val batch = db.batch()
        batch.set(db.collection("profileMedia").document(uid), values, SetOptions.merge())
        if (coach && !banner) batch.set(db.collection("coachPhotos").document(uid), values, SetOptions.merge())
        batch.commit().awaitCloud()
    }

    private suspend fun encodePhoto(path: String, banner: Boolean): String = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Photo could not be opened" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > (if (banner) 1024 else 384)) sample *= 2
        val bitmap = requireNotNull(BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }))
        try {
            val output = ByteArrayOutputStream()
            var quality = 80
            var result: String
            do {
                output.reset()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                result = "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                quality -= 10
            } while (!validProfileImage(result, banner) && quality >= 30)
            require(validProfileImage(result, banner)) { "Choose a smaller or simpler image" }
            result
        } finally { bitmap.recycle() }
    }
}

internal fun validCoachPhoto(value: String): Boolean = value.length <= 120_000 &&
    (value.isEmpty() || value.matches(Regex("data:image/jpeg;base64,[A-Za-z0-9+/]+=*")))
