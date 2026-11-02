package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.pose.model.Landmark
import com.volleyballlab.player.pose.model.PoseFrame
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BlockingArmFeaturesTest {
    private val fixture = Json.parseToJsonElement(requireNotNull(javaClass.classLoader!!
        .getResourceAsStream("blocking_arm_parity.json")).bufferedReader().use { it.readText() }).jsonObject

    private fun frames(case: JsonObject): List<PoseFrame> = (0 until 32).map { frame ->
        val visibility = FloatArray(33) { 1f }
        val presence = FloatArray(33) { 1f }
        case.getValue("changes").jsonArray.forEach { change ->
            val row = change.jsonObject
            if (row.getValue("frame").jsonPrimitive.int == frame) {
                val joint = row.getValue("joint").jsonPrimitive.int
                row["visibility"]?.let { visibility[joint] = it.jsonPrimitive.float }
                row["presence"]?.let { presence[joint] = it.jsonPrimitive.float }
            }
        }
        val image = fixture.getValue("image").jsonArray.mapIndexed { i, element ->
            val p = element.jsonArray
            val motion = if (i == 15 || i == 16) frame * .001f else 0f
            Landmark(p[0].jsonPrimitive.float, p[1].jsonPrimitive.float + motion, 0f, visibility[i], presence[i])
        }
        val world = fixture.getValue("world").jsonArray.mapIndexed { i, element ->
            val p = element.jsonArray
            val motion = if (i == 15 || i == 16) frame * .001f else 0f
            Landmark(p[0].jsonPrimitive.float, p[1].jsonPrimitive.float, p[2].jsonPrimitive.float + motion, visibility[i], presence[i])
        }
        PoseFrame(case.getValue("timestamps_ms").jsonArray[frame].jsonPrimitive.long, image, world, 640, 480, false)
    }.filterIndexed { index, _ -> case.getValue("missing_frames").jsonArray.none { it.jsonPrimitive.int == index } }

    @Test fun `left right and missing observations match the Python recipe`() {
        fixture.getValue("cases").jsonArray.forEach { value ->
            val case = value.jsonObject
            val built = requireNotNull(BlockingArmFeatures.build(frames(case)))
            assertEquals(case.getValue("arm").jsonPrimitive.content, built.observedArm)
            assertEquals(case.getValue("quality").jsonPrimitive.float, built.reliableFraction, 1e-5f)
            val expected = case.getValue("expected").jsonArray
            for (row in 0 until 32) for (col in 0 until 7) {
                val item = expected[row].jsonArray[col]
                if (item == JsonNull) assertTrue(built.values[row][col].isNaN())
                else assertEquals(item.jsonPrimitive.float, built.values[row][col], 5e-4f)
            }
        }
    }

    @Test fun `tracking gaps and timestamp resets cannot become complete arm windows`() {
        val input = frames(fixture.getValue("cases").jsonArray[0].jsonObject)
        assertNull(BlockingArmFeatures.build(input.take(9)))
        assertNull(BlockingArmFeatures.build(input.mapIndexed { i, frame ->
            if (i >= 16) frame.copy(timestampMs = frame.timestampMs + 1000) else frame }))
        assertNull(BlockingArmFeatures.build(input.mapIndexed { i, frame ->
            if (i == 16) frame.copy(timestampMs = 0) else frame }))
    }

    @Test fun `static and cropped arm phases cannot become a complete action`() {
        val rows = (0 until 32).map { i -> FloatArray(7).also { it[6] = 1f; it[5] = .3f - kotlin.math.abs(i - 16) * .015f } }
        assertTrue(BlockingArmFeatures.hasArmCycle(BlockingArmWindow("left", rows, 1f)))
        assertFalse(BlockingArmFeatures.hasArmCycle(BlockingArmWindow("left", List(32) { rows[16] }, 1f)))
        assertFalse(BlockingArmFeatures.hasArmCycle(BlockingArmWindow("left", rows.take(17), 1f)))
    }
}
