package com.volleyballlab.player.pose.analysis

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BlockingArmModelTest {
    private fun contract() = BlockingArmReleaseContract(
        schemaVersion = 1, approved = true, scope = BlockingArmReleaseContract.SCOPE,
        recipe = "blocking_arm_time_v2", profile = BlockingArmReleaseContract.PROFILE,
        timesteps = 32, classes = listOf("fault", "ok"), features = BlockingArmReleaseContract.FEATURES,
        modelHash = "a".repeat(64), poseHash = "e".repeat(64), normalisation = BlockingArmReleaseContract.Normalization(
            List(7) { 0.0 }, List(7) { 1.0 }), disabled = emptyList(), arms = listOf("left"),
        temperature = 1.0, acceptAbove = .8, reportHash = "b".repeat(64), reviewHash = "c".repeat(64), deviceHash = "d".repeat(64))

    @Test fun `research metadata or unapproved manifest cannot enable model`() {
        assertNull(BlockingArmReleaseContract.decode("""{"schema_version":2,"deployment_approved":false}"""))
        assertNull(BlockingArmReleaseContract.decode(Json.encodeToString(contract().copy(approved = false))))
        assertNull(BlockingArmReleaseContract.decode(Json.encodeToString(contract().copy(scope = "whole_block_execution"))))
        assertNotNull(BlockingArmReleaseContract.decode(Json.encodeToString(contract())))
        assertNull(BlockingArmReleaseContract.decode(Json.encodeToString(contract().copy(recipe = "blocking_arm_dataset_v1"))))
    }

    @Test fun `unknown frames stay masked and too little evidence cannot predict`() {
        val rows = MutableList(32) { floatArrayOf(10f, 20f, .1f, .2f, .3f, .4f, 1f) }
        rows[3] = floatArrayOf(Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, 0f)
        val prepared = contract().normalize(rows, "left")!!
        assertTrue(prepared.slice(21 until 28).all { it == 0f })
        assertEquals(1f, prepared[6])
        assertNull(contract().normalize(rows, "right"))
        repeat(20) { rows[it] = rows[3].copyOf() }
        assertNull(contract().normalize(rows, "left"))
    }

    @Test fun `invalid probabilities and low confidence always abstain`() {
        val release = contract()
        assertEquals(BlockingArmDecision.Fault, release.decide(floatArrayOf(.95f, .05f)).decision)
        assertEquals(BlockingArmDecision.Ok, release.decide(floatArrayOf(.05f, .95f)).decision)
        for (p in listOf(floatArrayOf(.55f, .45f), floatArrayOf(Float.NaN, .5f), floatArrayOf(1.1f, -.1f))) {
            assertEquals(BlockingArmDecision.Uncertain, release.decide(p).decision)
        }
    }
}
