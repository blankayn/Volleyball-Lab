package com.volleyballlab.player.pose

import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.volleyballlab.player.pose.analysis.BlockingArmFeatures
import com.volleyballlab.player.pose.model.Landmark
import com.volleyballlab.player.pose.model.PoseFrame
import com.volleyballlab.player.pose.review.SequentialVideoDecoder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import kotlin.math.abs

/** Public prerecorded replay only. Never enables coaching or uploads student video. */
@RunWith(AndroidJUnit4::class)
class BlockingArmPipelineDeviceTest {
    @Test fun decodedPublicVideoRunsThroughTheActualLiveDetectorAndCandidate() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val test = instrumentation.context
        val app = instrumentation.targetContext
        val requiredInferences = InstrumentationRegistry.getArguments().getString("benchmarkInferences")
            ?.toInt()?.also { require(it in 12..100) } ?: 100
        val useSequential = InstrumentationRegistry.getArguments().getString("decoder") != "retriever"
        val fixture = test.assets.open("blocking_candidate/probes.json").bufferedReader().use { JSONObject(it.readText()) }
        assertFalse(fixture.getBoolean("deployment_approved"))
        assertEquals("blocking_arm_time_v2", fixture.getString("feature_recipe"))
        val digest = MessageDigest.getInstance("SHA-256").digest(test.assets.open("blocking_candidate/candidate.tflite").use { it.readBytes() })
            .joinToString("") { "%02x".format(it) }
        assertEquals(fixture.getString("model_sha256"), digest)
        val videoDigest = MessageDigest.getInstance("SHA-256")
            .digest(test.assets.open("blocking_candidate/public-replay.mp4").use { it.readBytes() })
            .joinToString("") { "%02x".format(it) }
        assertEquals(fixture.getString("source_video_sha256"), videoDigest)
        val poseDigest = MessageDigest.getInstance("SHA-256")
            .digest(app.assets.open(MediaPipePoseDetector.DEFAULT_MODEL_ASSET).use { it.readBytes() })
            .joinToString("") { "%02x".format(it) }
        assertEquals(fixture.getString("pose_model_sha256"), poseDigest)
        val cases = checkSyntheticParity()
        val mean = fixture.getJSONObject("normalisation").getJSONArray("mean")
        val std = fixture.getJSONObject("normalisation").getJSONArray("std")
        val disabled = fixture.getJSONArray("disabled_features")
        val names = fixture.getJSONArray("feature_names")
        val model = CompiledModel.create(test.assets, "blocking_candidate/candidate.tflite", CompiledModel.Options(Accelerator.CPU), null)
        val inputs = model.createInputBuffers(); val outputs = model.createOutputBuffers()
        val retriever = MediaMetadataRetriever()
        val video = test.assets.openFd("blocking_candidate/public-replay.mp4")
        val timings = mutableListOf<Double>()
        val allFrames = mutableListOf<Double>()
        val decodeTimes = mutableListOf<Double>()
        val poseTimes = mutableListOf<Double>()
        val featureTimes = mutableListOf<Double>()
        val inferenceTimes = mutableListOf<Double>()
        val cold = mutableListOf<Double>()
        val decoderCold = mutableListOf<Double>()
        var submitted = 0; var emitted = 0; var gpu = false
        var lastWindow = JSONObject()
        var liveDetector: MediaPipePoseDetector? = null
        try {
            retriever.setDataSource(video.fileDescriptor, video.startOffset, video.length)
            val duration = requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
            val width = requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)).toInt()
            val height = requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)).toInt()
            val scale = minOf(480.0 / width, 640.0 / height, 1.0)
            val creation = SystemClock.elapsedRealtimeNanos()
            val detector = MediaPipePoseDetector.create(app)
            liveDetector = detector
            cold += (SystemClock.elapsedRealtimeNanos() - creation) / 1e6
            gpu = detector.usingGpu
            repeat(10) { round ->
                if (timings.size >= requiredInferences) return@repeat
                val decoderStart = SystemClock.elapsedRealtimeNanos()
                val sequential = if (useSequential) SequentialVideoDecoder.open(video.fileDescriptor,
                    video.startOffset, video.length, 1, 480, 640) else null
                decoderCold += (SystemClock.elapsedRealtimeNanos() - decoderStart) / 1e6
                val frames = mutableListOf<PoseFrame>()
                try {
                    var sourceMs = 0L
                    while (sourceMs < duration && timings.size < requiredInferences) {
                        val tick = SystemClock.elapsedRealtimeNanos()
                        val sequentialFrame = if (useSequential) sequential!!.nextFrame() ?: break else null
                        val inputMs = sequentialFrame?.timestampMs ?: sourceMs
                        val bitmap = sequentialFrame?.bitmap ?: requireNotNull(if (Build.VERSION.SDK_INT >= 27)
                            retriever.getScaledFrameAtTime(sourceMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST,
                                (width * scale).toInt(), (height * scale).toInt())
                            else retriever.getFrameAtTime(sourceMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST))
                        val decodedAt = SystemClock.elapsedRealtimeNanos()
                        decodeTimes += (decodedAt - tick) / 1e6
                        val timestamp = 1_000_000L + round * 10_000L + inputMs
                        val pending = async(start = CoroutineStart.UNDISPATCHED) {
                            withTimeoutOrNull(1500) { detector.completions.first { it.cameraTimestampMs == timestamp } }
                        }
                        submitted++
                        detector.submit(bitmap, timestamp, false)
                        assertTrue("Detector must release copied bitmap input",bitmap.isRecycled)
                        val result = requireNotNull(pending.await()) { "Pose completion timed out" }
                        val frame = result.pose
                        val poseAt = SystemClock.elapsedRealtimeNanos()
                        poseTimes += (poseAt - decodedAt) / 1e6
                        sourceMs = if (useSequential) inputMs + 1 else sourceMs + 67L
                        if (frame == null) {
                            featureTimes += 0.0; inferenceTimes += 0.0
                            allFrames += (SystemClock.elapsedRealtimeNanos() - tick) / 1e6
                            continue
                        }
                        emitted++
                        frames += frame
                        val selected = frames.takeLast(32)
                        val window = BlockingArmFeatures.build(selected)
                        if (window == null) {
                            featureTimes += (SystemClock.elapsedRealtimeNanos() - poseAt) / 1e6
                            inferenceTimes += 0.0
                            allFrames += (SystemClock.elapsedRealtimeNanos() - tick) / 1e6
                            continue
                        }
                        val normalized = FloatArray(32 * 7)
                        window.values.forEachIndexed { row, values ->
                            for (col in 0..6) {
                                val off = (0 until disabled.length()).any { disabled.getString(it) == names.getString(col) }
                                normalized[row * 7 + col] = if (!values[col].isFinite() || off) 0f else
                                    ((values[col] - mean.getDouble(col)) / std.getDouble(col)).toFloat()
                            }
                        }
                        val preparedAt = SystemClock.elapsedRealtimeNanos()
                        featureTimes += (preparedAt - poseAt) / 1e6
                        inputs.single().writeFloat(normalized); model.run(inputs, outputs)
                        val probabilities = outputs.single().readFloat()
                        inferenceTimes += (SystemClock.elapsedRealtimeNanos() - preparedAt) / 1e6
                        val elapsed = (SystemClock.elapsedRealtimeNanos() - tick) / 1e6
                        assertEquals(2, probabilities.size)
                        assertTrue(probabilities.all { it.isFinite() && it in 0f..1f })
                        assertTrue(abs(probabilities.sum() - 1f) < 1e-4f)
                        timings += elapsed
                        allFrames += elapsed
                        lastWindow = JSONObject().put("frames", JSONArray(selected.map(::poseJson)))
                            .put("arm", window.observedArm).put("quality", window.reliableFraction.toDouble())
                            .put("raw_features", JSONArray(window.values.map { row -> JSONArray(row.map { if (it.isFinite()) it.toDouble() else JSONObject.NULL }) }))
                            .put("normalized_input", JSONArray(normalized.map { it.toDouble() }))
                            .put("probabilities", JSONArray(probabilities.map { it.toDouble() }))
                    }
                } finally {
                    sequential?.close()
                }
            }
            assertEquals("Not enough valid replay windows for the stability benchmark", requiredInferences, timings.size)
            timings.sort()
            allFrames.sort()
            assertEquals(submitted, allFrames.size)
            assertTrue("Initial tracker confirmation frames must remain in latency accounting", submitted > emitted)
            val emulator = Build.FINGERPRINT.startsWith("generic") || Build.HARDWARE in listOf("goldfish", "ranchu") || Build.MODEL.contains("Emulator")
            val report = JSONObject().put("model_sha256", digest).put("feature_recipe", "blocking_arm_time_v2")
                .put("pose_model_sha256", poseDigest)
                .put("physical_device", !emulator).put("platform", "android").put("device_model", Build.MODEL)
                .put("os_version", Build.VERSION.RELEASE).put("inferences", requiredInferences).put("successful_inferences", requiredInferences)
                .put("invalid_outputs", 0).put("submitted_frames", submitted).put("emitted_poses", emitted)
                .put("pipeline_p50_ms", allFrames[((allFrames.size - 1) * .5).toInt()])
                .put("pipeline_p95_ms", allFrames[kotlin.math.ceil(allFrames.size * .95).toInt() - 1])
                .put("successful_window_p95_ms", percentile95(timings)).put("frames_without_pose", submitted - emitted)
                .put("decode_p95_ms", percentile95(decodeTimes)).put("pose_p95_ms", percentile95(poseTimes))
                .put("feature_p95_ms", percentile95(featureTimes)).put("inference_p95_ms", percentile95(inferenceTimes))
                .put("diagnostic_run", requiredInferences != 100)
                .put("latency_includes_empty_detections", true)
                .put("pose_cold_start_ms", JSONArray(cold)).put("using_gpu", gpu)
                .put("decoder", if (useSequential) "sequential_mediacodec" else "retriever_seek")
                .put("decoder_cold_start_ms", JSONArray(decoderCold))
                .put("detector_instances", 1)
                .put("pose_running_mode", "LIVE_STREAM").put("max_decode_width", 480).put("max_decode_height", 640)
                .put("includes_pose_estimation", true).put("includes_feature_extraction", true).put("includes_video_decode", true)
                .put("preprocessing_parity_cases", JSONArray(cases)).put("preprocessing_parity_verified", false)
                .put("replay_window", lastWindow).put("deployment_approved", false)
                .put("measurement_scope", "All decoded frames including empty detections; successful sliding windows from repeated public footage, not independent actions; detector construction measured separately")
                .put("source_video_sha256", videoDigest)
            app.filesDir.resolve("blocking-arm-pipeline.json").writeText(report.toString(2))
            InstrumentationRegistry.getArguments().getString("pipelineBudgetMs")?.toDouble()?.let { budget ->
                assertTrue("Full pipeline p95 ${report.getDouble("pipeline_p95_ms")}ms exceeds ${budget}ms", report.getDouble("pipeline_p95_ms") <= budget)
            }
        } finally {
            liveDetector?.close()
            retriever.release(); video.close()
            inputs.forEach { it.close() }; outputs.forEach { it.close() }; model.close()
        }
    }

    private fun percentile95(values: List<Double>): Double = values.sorted()[kotlin.math.ceil(values.size * .95).toInt() - 1]

    private fun poseJson(frame: PoseFrame): JSONObject = JSONObject().put("timestamp_ms", frame.timestampMs)
        .put("image", JSONArray(frame.image.map { JSONArray(listOf(it.x, it.y, it.visibility, it.presence)) }))
        .put("world", JSONArray(frame.world.map { JSONArray(listOf(it.x, it.y, it.z)) }))

    private fun checkSyntheticParity(): List<String> {
        val context = InstrumentationRegistry.getInstrumentation().context
        val fixture = context.assets.open("blocking_candidate/parity.json").bufferedReader().use { JSONObject(it.readText()) }
        val cases = fixture.getJSONArray("cases")
        val names = mutableListOf<String>()
        repeat(cases.length()) { caseIndex ->
            val case = cases.getJSONObject(caseIndex)
            val frames = (0 until 32).filter { index ->
                (0 until case.getJSONArray("missing_frames").length()).none { case.getJSONArray("missing_frames").getInt(it) == index }
            }.map { index ->
                val visibility = FloatArray(33) { 1f }; val presence = FloatArray(33) { 1f }
                val changes = case.getJSONArray("changes")
                repeat(changes.length()) { i ->
                    val change = changes.getJSONObject(i)
                    if (change.getInt("frame") == index) {
                        if (change.has("visibility")) visibility[change.getInt("joint")] = change.getDouble("visibility").toFloat()
                        if (change.has("presence")) presence[change.getInt("joint")] = change.getDouble("presence").toFloat()
                    }
                }
                val image = (0 until 33).map { joint ->
                    val p = fixture.getJSONArray("image").getJSONArray(joint)
                    Landmark(p.getDouble(0).toFloat(), p.getDouble(1).toFloat() + if (joint in 15..16) index * .001f else 0f,
                        0f, visibility[joint], presence[joint])
                }
                val world = (0 until 33).map { joint ->
                    val p = fixture.getJSONArray("world").getJSONArray(joint)
                    Landmark(p.getDouble(0).toFloat(), p.getDouble(1).toFloat(),
                        p.getDouble(2).toFloat() + if (joint in 15..16) index * .001f else 0f, visibility[joint], presence[joint])
                }
                PoseFrame(case.getJSONArray("timestamps_ms").getLong(index), image, world, 640, 480, false)
            }
            val actual = requireNotNull(BlockingArmFeatures.build(frames))
            assertEquals(case.getString("arm"), actual.observedArm)
            assertEquals(case.getDouble("quality").toFloat(), actual.reliableFraction, 1e-5f)
            repeat(32) { row -> repeat(7) { col ->
                val expected = case.getJSONArray("expected").getJSONArray(row)
                if (expected.isNull(col)) assertTrue(actual.values[row][col].isNaN())
                else assertEquals(expected.getDouble(col).toFloat(), actual.values[row][col], 5e-4f)
            } }
            names += when (case.getString("name")) { "left" -> "left_arm"; "right" -> "right_arm"; else -> case.getString("name") }
        }
        return names
    }
}
