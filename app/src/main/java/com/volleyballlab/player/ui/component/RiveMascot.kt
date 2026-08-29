package com.volleyballlab.player.ui.component

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.rive.Result
import app.rive.Rive
import app.rive.RiveFileSource
import app.rive.rememberRiveFile
import app.rive.rememberRiveWorker
import app.rive.runtime.kotlin.core.Rive as RiveCore
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val riveInitialized = AtomicBoolean(false)

private fun ensureRiveInitialized(context: Context): Boolean = try {
    if (riveInitialized.compareAndSet(false, true)) {
        RiveCore.init(context)
    }
    true
} catch (_: UnsatisfiedLinkError) {
    false
}

/**
 * The animated mascot, played from a Rive file exported by the Rive editor.
 *
 * The `.riv` file is read from `assets/[assetName]`; until that file exists the
 * composable renders nothing, so the app builds and runs before the artwork is
 * dropped in. Once present, the artboard's default state machine plays on loop.
 */
@Composable
fun RiveMascot(
    modifier: Modifier = Modifier,
    assetName: String = "mascot.riv",
    playing: Boolean = true,
) {
    val context = LocalContext.current
    val loadedBytes by produceState<ByteArray?>(initialValue = null, assetName) {
        value = withContext(Dispatchers.IO) {
            try {
                context.assets.open(assetName).use { it.readBytes() }
            } catch (_: IOException) {
                null
            }
        }
    }
    val bytes = loadedBytes ?: return

    if (!ensureRiveInitialized(context)) return

    val riveWorker = rememberRiveWorker()
    val fileResult = rememberRiveFile(RiveFileSource.Bytes(bytes), riveWorker)
    when (fileResult) {
        is Result.Loading, is Result.Error -> Unit
        is Result.Success -> Rive(
            file = fileResult.value,
            playing = playing,
            modifier = modifier,
        )
    }
}
