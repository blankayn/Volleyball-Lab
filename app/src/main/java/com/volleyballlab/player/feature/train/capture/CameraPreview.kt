package com.volleyballlab.player.feature.train.capture

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.util.Rational
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.MirrorMode
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
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
    recordingId: String?,
    onVideoFinalized: (sessionId: String, path: String?) -> Unit,
    modifier: Modifier = Modifier,
    onVideoClock: (sessionId: String, cameraMs: Long, recordedMs: Long, started: Boolean) -> Unit = { _, _, _, _ -> },
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val finalizedCallback by rememberUpdatedState(onVideoFinalized)
    val frameCallback by rememberUpdatedState(onFrame)
    val clockCallback by rememberUpdatedState(onVideoClock)
    val latestCameraMs = remember { AtomicLong(0L) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var videoSupported by remember { mutableStateOf<Boolean?>(null) }

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

    LaunchedEffect(lensFacing, lifecycleOwner) {
        videoCapture = null
        videoSupported = null
        val provider = context.awaitCameraProvider()
        val mirrored = lensFacing == CameraSelector.LENS_FACING_FRONT
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0

        val preview = Preview.Builder().setTargetRotation(rotation).build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val analysis = ImageAnalysis.Builder()
            .setTargetRotation(rotation)
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
                            latestCameraMs.set(proxy.imageInfo.timestamp / NANOS_PER_MILLI)
                            frameCallback(
                                proxy.toUprightBitmap(),
                                proxy.imageInfo.timestamp / NANOS_PER_MILLI,
                                mirrored,
                            )
                        }.onFailure { Log.w(TAG, "Frame processing failed", it) }
                    }
                }
            }

        val video = VideoCapture.Builder(
            Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.SD))
                .build()
        ).setTargetRotation(rotation).setMirrorMode(MirrorMode.MIRROR_MODE_OFF).build()
        // Both streams cover the same sensor crop. Crop analysis pixels before detection.
        val viewport = ViewPort.Builder(Rational(3, 4), rotation).setScaleType(ViewPort.FILL_CENTER).build()
        val withVideo = runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                UseCaseGroup.Builder().setViewPort(viewport).addUseCase(preview)
                    .addUseCase(analysis).addUseCase(video).build(),
            )
        }
        if (withVideo.isSuccess) {
            videoCapture = video
            videoSupported = true
        } else {
            Log.w(TAG, "Video capture unavailable; trying pose-only camera", withVideo.exceptionOrNull())
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                    UseCaseGroup.Builder().setViewPort(viewport).addUseCase(preview).addUseCase(analysis).build(),
                )
            }.onFailure { Log.e(TAG, "Camera binding failed", it) }
            videoSupported = false
        }
    }

    // The ViewModel changes recordingId at the scoring boundary. Disposal stops
    // the encoder, then CameraX finalizes the file before the result is saved.
    DisposableEffect(recordingId, videoCapture, videoSupported) {
        var recording: Recording? = null
        if (recordingId != null && videoSupported == false) {
            finalizedCallback(recordingId, null)
        } else if (recordingId != null && videoSupported == true && videoCapture != null) {
            val file = File(File(context.filesDir, "training_videos").apply { mkdirs() },
                "$recordingId.mp4")
            runCatching {
                recording = videoCapture!!.output
                    .prepareRecording(context, FileOutputOptions.Builder(file).build())
                    .start(ContextCompat.getMainExecutor(context)) { event ->
                        if (event is VideoRecordEvent.Start || event is VideoRecordEvent.Status) {
                            clockCallback(recordingId, latestCameraMs.get(),
                                event.recordingStats.recordedDurationNanos / NANOS_PER_MILLI,
                                event is VideoRecordEvent.Start)
                        }
                        if (event is VideoRecordEvent.Finalize) {
                            if (event.hasError()) {
                                Log.w(TAG, "Video recording failed: ${event.error}")
                                file.delete()
                                finalizedCallback(recordingId, null)
                            } else {
                                finalizedCallback(recordingId, file.absolutePath)
                            }
                        }
                    }
            }.onFailure {
                Log.w(TAG, "Could not start video recording", it)
                file.delete()
                finalizedCallback(recordingId, null)
            }
        }
        onDispose { recording?.stop() }
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
    val full = toBitmap()
    val crop = cropRect
    val source = if (crop.left == 0 && crop.top == 0 && crop.width() == full.width && crop.height() == full.height) full
        else Bitmap.createBitmap(full, crop.left, crop.top, crop.width(), crop.height()).also { full.recycle() }
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return source
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true).also { if (it !== source) source.recycle() }
}

private const val TAG = "CameraPreview"
private const val NANOS_PER_MILLI = 1_000_000L
