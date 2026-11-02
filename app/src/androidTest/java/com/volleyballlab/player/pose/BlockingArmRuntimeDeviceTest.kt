package com.volleyballlab.player.pose

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import kotlin.math.abs

/** Candidate runtime verification only. Fixtures are never main/release assets. */
@RunWith(AndroidJUnit4::class)
class BlockingArmRuntimeDeviceTest {
    @Test fun candidateHasFiniteNativeOutputsAndMatchesDesktopProbes() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val fixture = context.assets.open("blocking_candidate/probes.json").bufferedReader().use { JSONObject(it.readText()) }
        assertFalse(fixture.getBoolean("deployment_approved"))
        assertEquals("abstain", fixture.getString("decision_policy"))
        val bytes = context.assets.open("blocking_candidate/candidate.tflite").use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(fixture.getString("model_sha256"), digest)
        val start = SystemClock.elapsedRealtimeNanos()
        val model = CompiledModel.create(context.assets, "blocking_candidate/candidate.tflite", CompiledModel.Options(Accelerator.CPU), null)
        val inputs = model.createInputBuffers(); val outputs = model.createOutputBuffers()
        val coldMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
        var difference = 0f
        val timings = mutableListOf<Double>()
        try {
            val probes = fixture.getJSONArray("probes")
            repeat(105) { iteration ->
                val probe = probes.getJSONObject(iteration % probes.length())
                val values = probe.getJSONArray("normalized_input")
                val input = FloatArray(values.length()) { values.getDouble(it).toFloat() }
                val expected = probe.getJSONArray("expected_probabilities")
                val tick = SystemClock.elapsedRealtimeNanos()
                inputs.single().writeFloat(input); model.run(inputs, outputs)
                val actual = outputs.single().readFloat()
                val elapsed = (SystemClock.elapsedRealtimeNanos() - tick) / 1e6
                assertEquals(2, actual.size)
                assertTrue(actual.all { it.isFinite() && it in 0f..1f })
                assertTrue(abs(actual.sum() - 1f) < 1e-4f)
                for (index in 0..1) difference = maxOf(difference, abs(actual[index] - expected.getDouble(index).toFloat()))
                if (iteration >= 5) timings += elapsed
            }
            assertTrue("Android runtime differs materially from desktop export", difference <= .03f)
            timings.sort()
            val emulator = Build.FINGERPRINT.startsWith("generic") || Build.HARDWARE in listOf("goldfish", "ranchu") || Build.MODEL.contains("Emulator")
            val report = JSONObject().put("model_sha256", digest).put("feature_recipe", fixture.getString("feature_recipe"))
                .put("physical_device", !emulator).put("platform", "android").put("device_model", Build.MODEL)
                .put("os_version", Build.VERSION.RELEASE).put("inferences", 100).put("successful_inferences", 100)
                .put("invalid_outputs", 0).put("warmups", 5).put("classifier_p50_ms", timings[49])
                .put("classifier_p95_ms", timings[94]).put("cold_start_ms", coldMs)
                .put("max_probability_difference", difference.toDouble())
                .put("includes_pose_estimation", false).put("includes_feature_extraction", false).put("includes_video_decode", false)
                .put("deployment_approved", false)
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir
                .resolve("blocking-arm-runtime.json").writeText(report.toString(2))
            Log.i("BlockingArmRuntime", report.toString())
        } finally {
            inputs.forEach { it.close() }; outputs.forEach { it.close() }; model.close()
        }
    }
}
