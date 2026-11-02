package com.volleyballlab.player.pose.analysis

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import com.volleyballlab.player.pose.model.PoseFrame
import com.volleyballlab.player.pose.MediaPipePoseDetector
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.abs

enum class BlockingArmDecision { Ok, Fault, Uncertain }
data class BlockingArmPrediction(val decision: BlockingArmDecision, val confidence: Float? = null)

/** Accepted only from a release bundle whose independent evidence was audited. */
@Serializable
data class BlockingArmReleaseContract(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("deployment_approved") val approved: Boolean,
    @SerialName("task_scope") val scope: String,
    @SerialName("feature_recipe") val recipe: String,
    @SerialName("feature_quality_profile") val profile: String,
    val timesteps: Int,
    val classes: List<String>,
    @SerialName("feature_names") val features: List<String>,
    @SerialName("model_sha256") val modelHash: String,
    @SerialName("pose_model_sha256") val poseHash: String,
    val normalisation: Normalization,
    @SerialName("disabled_features") val disabled: List<String>,
    @SerialName("validated_arms") val arms: List<String>,
    val temperature: Double,
    @SerialName("accept_above") val acceptAbove: Double,
    @SerialName("evaluation_report_sha256") val reportHash: String,
    @SerialName("independent_review_sha256") val reviewHash: String,
    @SerialName("device_evidence_sha256") val deviceHash: String,
) {
    @Serializable data class Normalization(val mean: List<Double>, val std: List<Double>)

    fun valid(): Boolean = approved && schemaVersion == 1 && scope == SCOPE &&
        recipe == "blocking_arm_time_v2" && profile == PROFILE && timesteps == 32 &&
        classes == listOf("fault", "ok") && features == FEATURES &&
        listOf(modelHash, poseHash, reportHash, reviewHash, deviceHash).all { it.matches(Regex("[a-f0-9]{64}")) } &&
        normalisation.mean.size == 7 && normalisation.std.size == 7 &&
        normalisation.mean.all(Double::isFinite) && normalisation.std.all { it.isFinite() && it > 0 } &&
        normalisation.mean.last() == 0.0 && normalisation.std.last() == 1.0 &&
        disabled.all { it in FEATURES && it != "observed_frame" } && arms.isNotEmpty() &&
        arms.all { it == "left" || it == "right" } && temperature.isFinite() && temperature > 0 &&
        acceptAbove.isFinite() && acceptAbove >= .5 && acceptAbove < 1

    /** Missing poses stay zero after normalization; their explicit mask stays zero. */
    fun normalize(raw: List<FloatArray>, arm: String): FloatArray? {
        if (!valid() || arm !in arms || raw.size != 32 || raw.any { it.size != 7 }) return null
        if (raw.count { it[6] == 1f } < 16) return null
        val result = FloatArray(32 * 7)
        for ((row, values) in raw.withIndex()) {
            val mask = values[6]
            if (mask != 0f && mask != 1f) return null
            if ((mask == 1f && values.take(6).any { !it.isFinite() }) ||
                (mask == 0f && values.take(6).any { !it.isNaN() })) return null
            for (col in 0..6) {
                val value = if (values[col].isFinite() && FEATURES[col] !in disabled)
                    ((values[col] - normalisation.mean[col]) / normalisation.std[col]).toFloat() else 0f
                if (!value.isFinite()) return null
                result[row * 7 + col] = value
            }
        }
        return result
    }

    fun decide(probabilities: FloatArray): BlockingArmPrediction {
        val uncertain = BlockingArmPrediction(BlockingArmDecision.Uncertain)
        if (!valid() || probabilities.size != 2 || probabilities.any { !it.isFinite() || it !in 0f..1f } ||
            abs(probabilities.sum() - 1f) > 1e-4f) return uncertain
        val logits = probabilities.map { ln(it.toDouble().coerceAtLeast(1e-12)) / temperature }
        val maximum = logits.max()
        val weights = logits.map { exp(it - maximum) }
        val calibrated = weights.map { it / weights.sum() }
        val index = if (calibrated[0] >= calibrated[1]) 0 else 1
        val confidence = calibrated[index]
        return if (confidence < acceptAbove) uncertain else BlockingArmPrediction(
            if (index == 0) BlockingArmDecision.Fault else BlockingArmDecision.Ok, confidence.toFloat())
    }

    companion object {
        const val SCOPE = "block_visible_arm_reach_hold"
        const val PROFILE = "blocking-visible-arm7-v2-visibility-presence0.5-time50ms-mask"
        val FEATURES = listOf("visible_elbow_angle", "visible_shoulder_angle", "visible_wrist_above_shoulder_m",
            "visible_wrist_above_nose_m", "visible_wrist_forward_m", "visible_wrist_rise_image", "observed_frame")
        fun decode(text: String): BlockingArmReleaseContract? = runCatching {
            Json.decodeFromString<BlockingArmReleaseContract>(text).takeIf { it.valid() }
        }.getOrNull()
    }
}

/** Optional release adapter. Research metadata cannot enable it. No prototype assets are shipped. */
class LiteRtBlockingArmModel private constructor(
    private val contract: BlockingArmReleaseContract,
    private val model: CompiledModel,
    private val inputs: List<TensorBuffer>,
    private val outputs: List<TensorBuffer>,
) : AutoCloseable {
    fun evaluate(frames: List<PoseFrame>): BlockingArmPrediction {
        val window = BlockingArmFeatures.build(frames)
            ?: return BlockingArmPrediction(BlockingArmDecision.Uncertain)
        if (!BlockingArmFeatures.hasArmCycle(window)) return BlockingArmPrediction(BlockingArmDecision.Uncertain)
        return evaluatePrepared(window.values, window.observedArm)
    }

    private fun evaluatePrepared(raw: List<FloatArray>, observedArm: String): BlockingArmPrediction {
        val normalized = contract.normalize(raw, observedArm)
            ?: return BlockingArmPrediction(BlockingArmDecision.Uncertain)
        return runCatching {
            inputs.single().writeFloat(normalized)
            model.run(inputs, outputs)
            contract.decide(outputs.single().readFloat())
        }.getOrElse { BlockingArmPrediction(BlockingArmDecision.Uncertain) }
    }

    override fun close() {
        inputs.forEach(TensorBuffer::close)
        outputs.forEach(TensorBuffer::close)
        model.close()
    }

    companion object {
        fun create(context: Context): LiteRtBlockingArmModel? {
            val contract = runCatching { context.assets.open("blocking_arm_release.json").bufferedReader().use {
                BlockingArmReleaseContract.decode(it.readText())
            } }.getOrNull() ?: return null
            val poseHash = runCatching { context.assets.open(MediaPipePoseDetector.DEFAULT_MODEL_ASSET).use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                while (true) { val size = stream.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
                digest.digest().joinToString("") { "%02x".format(it) }
            } }.getOrNull() ?: return null
            if (poseHash != contract.poseHash) return null
            val hash = runCatching { context.assets.open("blocking_arm_classifier.tflite").use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                while (true) { val size = stream.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
                digest.digest().joinToString("") { "%02x".format(it) }
            } }.getOrNull() ?: return null
            if (hash != contract.modelHash) return null
            val compiled = runCatching { CompiledModel.create(context.assets, "blocking_arm_classifier.tflite",
                CompiledModel.Options(Accelerator.CPU), null) }.getOrNull() ?: return null
            var inputs: List<TensorBuffer> = emptyList()
            var outputs: List<TensorBuffer> = emptyList()
            return try {
                inputs = compiled.createInputBuffers(); outputs = compiled.createOutputBuffers()
                if (inputs.size != 1 || outputs.size != 1) error("Unexpected model tensor count")
                LiteRtBlockingArmModel(contract, compiled, inputs, outputs)
            } catch (_: Exception) {
                inputs.forEach { runCatching { it.close() } }; outputs.forEach { runCatching { it.close() } }
                compiled.close(); null
            }
        }
    }
}
