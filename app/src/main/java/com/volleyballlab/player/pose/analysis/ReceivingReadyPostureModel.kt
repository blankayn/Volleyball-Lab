package com.volleyballlab.player.pose.analysis

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame
import kotlin.math.ceil
import kotlin.math.floor

/** The three decisions exposed by the deployed receiving-ready classifier. */
enum class ReceivingReadyDecision { Ok, Fault, Uncertain, WarmingUp, InsufficientQuality }

data class ReceivingReadyEvaluation(
    val decision: ReceivingReadyDecision,
    val faultProbability: Float? = null,
    val features: FloatArray? = null,
    val reliableFrameFraction: Float = 0f,
)

/** Small seam that keeps descriptor and policy tests independent of Android native code. */
fun interface FaultProbabilityModel : AutoCloseable {
    fun predict(features: FloatArray): Float

    override fun close() = Unit
}

/**
 * LiteRT execution of the TensorFlow model exported by train_receiving_ready_tf.py.
 * Buffers are reused because this runs on every pose frame after warm-up.
 */
class LiteRtReceivingReadyModel private constructor(
    private val model: CompiledModel,
    private val inputs: List<TensorBuffer>,
    private val outputs: List<TensorBuffer>,
) : FaultProbabilityModel {

    override fun predict(features: FloatArray): Float {
        require(features.size == ReceivingReadyPostureEvaluator.FEATURE_COUNT)
        inputs[0].writeFloat(features)
        model.run(inputs, outputs)
        return outputs[0].readFloat().single().coerceIn(0f, 1f)
    }

    override fun close() {
        inputs.forEach(TensorBuffer::close)
        outputs.forEach(TensorBuffer::close)
        model.close()
    }

    companion object {
        const val ASSET_NAME = "receiving_ready_posture.tflite"

        fun create(context: Context): LiteRtReceivingReadyModel {
            val compiled = CompiledModel.create(
                context.assets,
                ASSET_NAME,
                CompiledModel.Options(Accelerator.CPU),
                null,
            )
            return LiteRtReceivingReadyModel(
                model = compiled,
                inputs = compiled.createInputBuffers(),
                outputs = compiled.createOutputBuffers(),
            )
        }
    }
}

/**
 * Builds the same six temporal descriptors used during training and applies the
 * deployment policy. The window is 32 frames because every training clip was
 * resampled to 32 steps before its median, q10 and q90 were calculated.
 */
class ReceivingReadyPostureEvaluator(
    private val probabilityModel: FaultProbabilityModel,
    private val windowSize: Int = WINDOW_SIZE,
    private val minimumReliableFraction: Float = MIN_RELIABLE_FRAME_FRACTION,
) : AutoCloseable {

    private val recentFrames = ArrayDeque<PoseFrame>(windowSize)
    private var lastFrameTimestampMs: Long? = null

    fun onFrame(frame: PoseFrame): ReceivingReadyEvaluation {
        val previousTimestampMs = lastFrameTimestampMs
        if (
            previousTimestampMs != null &&
            frame.timestampMs - previousTimestampMs > MAX_CONTIGUOUS_FRAME_GAP_MS
        ) {
            // A window must describe one continuous observation. PoseLandmarker
            // emits nothing when tracking is lost, so without this reset two
            // groups of detections separated by seconds would be stitched into a
            // seemingly complete 32-frame stance.
            recentFrames.clear()
        }
        lastFrameTimestampMs = frame.timestampMs
        if (recentFrames.size == windowSize) recentFrames.removeFirst()
        recentFrames.addLast(frame)
        if (recentFrames.size < windowSize) {
            return ReceivingReadyEvaluation(
                decision = ReceivingReadyDecision.WarmingUp,
                reliableFrameFraction = recentFrames.count(::hasReliablePostureLandmarks).toFloat() /
                    recentFrames.size,
            )
        }
        return evaluate(recentFrames.toList(), requireFullWindow = true)
    }

    /** Used by post-session rep scoring; unlike live mode it accepts any non-trivial rep length. */
    fun evaluate(frames: List<PoseFrame>, requireFullWindow: Boolean = false): ReceivingReadyEvaluation {
        if (frames.isEmpty() || (requireFullWindow && frames.size < windowSize)) {
            return ReceivingReadyEvaluation(ReceivingReadyDecision.WarmingUp)
        }

        val reliableFraction = frames.count(::hasReliablePostureLandmarks).toFloat() / frames.size
        val ankleVisibility = frames.flatMap { frame ->
            listOf(
                frame.image(BodyPoint.LEFT_ANKLE).visibility,
                frame.image(BodyPoint.RIGHT_ANKLE).visibility,
            )
        }
        if (
            reliableFraction < minimumReliableFraction ||
            percentile(ankleVisibility, 0.5f) < MIN_LANDMARK_VISIBILITY
        ) {
            return ReceivingReadyEvaluation(
                decision = ReceivingReadyDecision.InsufficientQuality,
                reliableFrameFraction = reliableFraction,
            )
        }

        val knees = frames.map(::meanKneeAngle)
        val trunks = frames.map { Angles.trunkForwardLean(it) }
        val features = descriptors(knees, trunks) ?: return ReceivingReadyEvaluation(
            decision = ReceivingReadyDecision.InsufficientQuality,
            reliableFrameFraction = reliableFraction,
        )
        val probability = probabilityModel.predict(features)
        val decision = when {
            probability < OK_BELOW -> ReceivingReadyDecision.Ok
            probability >= FAULT_AT_OR_ABOVE -> ReceivingReadyDecision.Fault
            else -> ReceivingReadyDecision.Uncertain
        }
        return ReceivingReadyEvaluation(decision, probability, features, reliableFraction)
    }

    fun reset() {
        recentFrames.clear()
        lastFrameTimestampMs = null
    }

    override fun close() = probabilityModel.close()

    companion object {
        const val WINDOW_SIZE = 32
        const val FEATURE_COUNT = 6
        const val OK_BELOW = 0.40f
        const val FAULT_AT_OR_ABOVE = 0.60f
        const val MIN_RELIABLE_FRAME_FRACTION = 0.50f
        const val MIN_LANDMARK_VISIBILITY = 0.40f
        const val MAX_CONTIGUOUS_FRAME_GAP_MS = 500L

        private val REQUIRED_POSTURE_LANDMARKS = listOf(
            // trunkForwardLean needs these five points. In particular, the nose
            // resolves which side of the shoulder line is the athlete's front.
            BodyPoint.NOSE,
            BodyPoint.LEFT_SHOULDER,
            BodyPoint.RIGHT_SHOULDER,
            BodyPoint.LEFT_HIP,
            BodyPoint.RIGHT_HIP,
            // Retain the frozen full-leg deployment gate.
            BodyPoint.LEFT_KNEE,
            BodyPoint.RIGHT_KNEE,
            BodyPoint.LEFT_ANKLE,
            BodyPoint.RIGHT_ANKLE,
            BodyPoint.LEFT_HEEL,
            BodyPoint.RIGHT_HEEL,
            BodyPoint.LEFT_FOOT_INDEX,
            BodyPoint.RIGHT_FOOT_INDEX,
        )

        /** Order must match ready_posture_tf/deployment_metadata.json exactly. */
        internal fun descriptors(knees: List<Float?>, trunks: List<Float?>): FloatArray? {
            val knee32 = resampleFinite(knees, WINDOW_SIZE) ?: return null
            val trunk32 = resampleFinite(trunks, WINDOW_SIZE) ?: return null
            return floatArrayOf(
                percentile(knee32, 0.50f),
                percentile(knee32, 0.10f),
                percentile(knee32, 0.90f),
                percentile(trunk32, 0.50f),
                percentile(trunk32, 0.10f),
                percentile(trunk32, 0.90f),
            )
        }

        /** Matches numpy.interp over a 0..1 time axis, skipping non-finite values. */
        private fun resampleFinite(values: List<Float?>, size: Int): List<Float>? {
            val finite = values.mapIndexedNotNull { index, value ->
                value?.takeIf(Float::isFinite)?.let { index to it }
            }
            if (finite.isEmpty()) return null
            if (values.size == 1 || finite.size == 1) return List(size) { finite.first().second }

            val lastSource = values.lastIndex.toFloat()
            return List(size) { destinationIndex ->
                val sourcePosition = destinationIndex.toFloat() * lastSource / (size - 1)
                val upperPosition = finite.indexOfFirst { it.first >= sourcePosition }
                when {
                    upperPosition < 0 -> finite.last().second
                    upperPosition == 0 -> finite.first().second
                    else -> {
                        val (rightIndex, rightValue) = finite[upperPosition]
                        val (leftIndex, leftValue) = finite[upperPosition - 1]
                        val weight = (sourcePosition - leftIndex) / (rightIndex - leftIndex)
                        leftValue + (rightValue - leftValue) * weight
                    }
                }
            }
        }

        /** NumPy's default linear quantile: interpolate at q * (n - 1). */
        internal fun percentile(values: List<Float>, q: Float): Float {
            require(values.isNotEmpty())
            val sorted = values.sorted()
            val position = q * (sorted.size - 1)
            val lower = floor(position).toInt()
            val upper = ceil(position).toInt()
            if (lower == upper) return sorted[lower]
            val weight = position - lower
            return sorted[lower] + (sorted[upper] - sorted[lower]) * weight
        }

        /**
         * Retains the curated full-leg gate and also covers every landmark used
         * by the deployed knee/trunk descriptors. This changes only whether the
         * model abstains; the six input features and their order stay frozen.
         */
        internal fun hasReliablePostureLandmarks(frame: PoseFrame): Boolean =
            REQUIRED_POSTURE_LANDMARKS.all { point ->
                val landmark = frame.image(point)
                landmark.visibility >= MIN_LANDMARK_VISIBILITY &&
                    landmark.x in 0f..1f && landmark.y in 0f..1f
            }

        private fun meanKneeAngle(frame: PoseFrame): Float? {
            val left = Angles.at(
                frame, BodyPoint.LEFT_HIP, BodyPoint.LEFT_KNEE, BodyPoint.LEFT_ANKLE
            )
            val right = Angles.at(
                frame, BodyPoint.RIGHT_HIP, BodyPoint.RIGHT_KNEE, BodyPoint.RIGHT_ANKLE
            )
            return when {
                left != null && right != null -> (left + right) / 2f
                else -> left ?: right
            }
        }
    }
}
