package com.volleyballlab.player.feature.coach

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.data.coach.CoachPhotoStore
import com.volleyballlab.player.data.coach.validCoachPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

private class CoachPortraitModel(uid: String, store: CoachPhotoStore) : ViewModel() {
    val photo = store.photo(uid).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
internal fun CoachAvatar(coachUid: String, initials: String?, modifier: Modifier = Modifier) {
    val model: CoachPortraitModel = viewModel(key = "coach-portrait-$coachUid", factory = viewModelFactory {
        initializer { CoachPortraitModel(coachUid, appContainer.coachPhotoStore) }
    })
    val photo by model.photo.collectAsStateWithLifecycle()
    CoachPortrait(photo, initials, modifier)
}

@Composable
internal fun CoachPortrait(photo: String?, initials: String?, modifier: Modifier = Modifier) {
    val bitmap by key(photo) { produceState<Bitmap?>(null) {
        value = withContext(Dispatchers.IO) {
            photo?.takeIf { it.isNotEmpty() && validCoachPhoto(it) }?.let {
                runCatching {
                    val bytes = Base64.decode(it.substringAfter(','), Base64.DEFAULT)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                }.getOrNull()
            }
        }
    } }
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            Text(initials?.takeIf { it.isNotBlank() } ?: "C", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
            bitmap?.let { Image(it.asImageBitmap(), "Coach profile photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
    }
}
