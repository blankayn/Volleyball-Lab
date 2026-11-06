package com.volleyballlab.player.pose

import org.json.JSONObject

/** Offline dataset evidence; never a measurement of the current player's recording. */
internal data class PoseBenchmark(
    val mpjpeMm: Double,
    val referenceFrames: Int,
    val jointPairs: Int,
) {
    companion object {
        const val ASSET = "pose_benchmark.json"

        fun parse(text: String): PoseBenchmark {
            val data = JSONObject(text)
            require(data.getInt("schema_version") == 1 && data.getString("dataset") == "MADS")
            require(data.getString("scope") == "dataset_benchmark_not_session_measurement")
            require(data.getString("metric") == "3D_MPJPE" && data.getString("running_mode") == "VIDEO")
            require(!data.getBoolean("five_skill_validated"))
            val error = data.getDouble("mpjpe_mm")
            val frames = data.getInt("valid_reference_frames")
            val pairs = data.getInt("joint_pairs")
            require(error.isFinite() && error >= 0 && frames > 0 && pairs > 0)
            return PoseBenchmark(error, frames, pairs)
        }
    }
}
