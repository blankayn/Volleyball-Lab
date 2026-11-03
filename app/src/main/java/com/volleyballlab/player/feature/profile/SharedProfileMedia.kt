package com.volleyballlab.player.feature.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.data.coach.ProfileMedia
import com.volleyballlab.player.data.coach.validProfileImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberProfileMedia(uid: String?, coach: Boolean = false): ProfileMedia {
    val context = LocalContext.current
    val store = remember(context) { (context.applicationContext as VolleyballLabApplication).container.coachPhotoStore }
    if (uid.isNullOrBlank()) return ProfileMedia()
    val flow = remember(uid, coach, store) { store.media(uid, coach) }
    return flow.collectAsStateWithLifecycle(ProfileMedia()).value
}

@Composable
internal fun ProfileMediaImage(source: String?, description: String?, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(null, source) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (source.isNullOrBlank()) null
                else if (source.startsWith("data:")) {
                    require(validProfileImage(source, banner = true))
                    val bytes = Base64.decode(source.substringAfter(','), Base64.DEFAULT)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1536) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                } else BitmapFactory.decodeFile(source)
            }.getOrNull()
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), description, modifier, contentScale = ContentScale.Crop) }
}

@Composable
internal fun ProfileAvatar(uid: String?, initials: String?, modifier: Modifier = Modifier, coach: Boolean = false) {
    val media = rememberProfileMedia(uid, coach)
    Surface(modifier.size(44.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            Text(initials?.takeIf(String::isNotBlank) ?: "·", style = MaterialTheme.typography.titleMedium)
            ProfileMediaImage(media.photo, "Profile photo", Modifier.fillMaxSize())
        }
    }
}
