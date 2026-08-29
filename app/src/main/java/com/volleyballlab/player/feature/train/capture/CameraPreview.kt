package com.volleyballlab.player.feature.train.capture

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The camera viewfinder, wired to a per-frame callback.
 *
 * ## Why frames come back as an upright Bitmap
 *
 * MediaPipe expects an image the right way up. The sensor almost never delivers
 * one: on a portrait phone the analysis stream typically arrives rotated 90 or
 * 270 degrees, and feeding that straight in produces a tracked pose of someone
 * lying on their side, with every joint angle wrong in a way that looks
 * plausible. Rotation is applied here, once, at the boundary - so no code below
 * this file has to know about sensor orientation.
 *
 * @param onFrame invoked on a background executor, never the main thread. The
 *   receiver must not block: [ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST] drops
 *   frames rather than queueing them, so a slow consumer costs frame rate, not
 *   latency, which is the right trade for a live overlay.
 */
@Composable
fun CameraPreview(
    lensFacing: Int,
    onFrame: (bitmap: Bitmap, timestampMs: Long, mirrored: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            // FILL_CENTER crops rather than letterboxes. PoseOverlay reproduces
            // exactly this transform when placing landmarks; if one is changed
            // the other must change with it or the skeleton drifts off the body.
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    val analysisExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(analysisExecutor) {
        onDispose { analysisExecutor.shutdown() }
    }

    LaunchedEffect(lensFacing) {
        val provider = context.awaitCameraProvider()
        val mirrored = lensFacing == CameraSelector.LENS_FACING_FRONT

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val analysis = ImageAnalysis.Builder()
            // RGBA_8888 so toBitmap() is a copy rather than a YUV conversion.
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { imageAnalysis ->
                imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    // use() guarantees close() even if the callback throws.
                    // A leaked ImageProxy stalls the whole analysis pipeline
                    // after a handful of frames, and it presents as the camera
                    // silently freezing rather than as an error.
                    imageProxy.use { proxy ->
                        runCatching {
                            onFrame(
                                proxy.toUprightBitmap(),
                                proxy.imageInfo.timestamp / NANOS_PER_MILLI,
                                mirrored,
                            )
                        }.onFailure { Log.w(TAG, "Frame processing failed", it) }
                    }
                }
            }

        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                preview,
                analysis,
            )
        }.onFailure { Log.e(TAG, "Camera binding failed", it) }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * Awaits the camera provider without pulling in a Guava coroutines dependency.
 *
 * `ProcessCameraProvider.getInstance` returns a ListenableFuture; this is the
 * minimal bridge to a suspend function.
 */
private suspend fun android.content.Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            { continuation.resume(future.get()) },
            ContextCompat.getMainExecutor(this),
        )
    }

/**
 * Rotates the frame into the upright orientation MediaPipe expects.
 *
 * Returns the original bitmap untouched when no rotation is needed, which is the
 * common case on a tablet in landscape and avoids an allocation per frame.
 */
private fun ImageProxy.toUprightBitmap(): Bitmap {
    val source = toBitmap()
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return source
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

private const val TAG = "CameraPreview"
private const val NANOS_PER_MILLI = 1_000_000L
