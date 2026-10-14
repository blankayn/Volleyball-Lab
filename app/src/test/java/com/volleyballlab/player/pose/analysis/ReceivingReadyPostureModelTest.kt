package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.pose.TestPoses
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceivingReadyPostureModelTest {

    @Test
    fun `descriptor order and numpy linear quantiles stay frozen`() {
        val knees = (0..31).map(Int::toFloat)
        val trunks = (100..131).map(Int::toFloat)

        val actual = ReceivingReadyPostureEvaluator.descriptors(knees, trunks)

        assertArrayEquals(
            floatArrayOf(15.5f, 3.1f, 27.9f, 115.5f, 103.1f, 127.9f),
            actual!!,
            0.0001f,
        )
    }

    @Test
    fun `live evaluator waits for all 32 frames`() {
        val evaluator = ReceivingReadyPostureEvaluator(FaultProbabilityModel { 0.7f })
        repeat(31) { index ->
            assertEquals(
                ReceivingReadyDecision.WarmingUp,
                evaluator.onFrame(TestPoses.wellFramedStanding(index * 33L)).decision,
            )
        }
        assertEquals(
            ReceivingReadyDecision.Fault,
            evaluator.onFrame(TestPoses.wellFramedStanding(31 * 33L)).decision,
        )
    }

    @Test
    fun `deployment policy abstains from 040 through below 060`() {
        fun decision(probability: Float): ReceivingReadyDecision {
            val evaluator = ReceivingReadyPostureEvaluator(FaultProbabilityModel { probability })
            return evaluator.evaluate(
                List(32) { TestPoses.wellFramedStanding(it * 33L) },
                requireFullWindow = true,
            ).decision
        }

        assertEquals(ReceivingReadyDecision.Ok, decision(0.3999f))
        assertEquals(ReceivingReadyDecision.Uncertain, decision(0.40f))
        assertEquals(ReceivingReadyDecision.Uncertain, decision(0.5999f))
        assertEquals(ReceivingReadyDecision.Fault, decision(0.60f))
    }

    @Test
    fun `quality gate rejects a mostly unreliable lower body`() {
        val clean = TestPoses.wellFramedStanding()
        val poor = TestPoses.withVisibility(clean, 0.1f)
        val evaluator = ReceivingReadyPostureEvaluator(FaultProbabilityModel { 0.01f })

        val result = evaluator.evaluate(List(20) { poor } + List(12) { clean })

        assertEquals(ReceivingReadyDecision.InsufficientQuality, result.decision)
        assertTrue(result.reliableFrameFraction < 0.5f)
    }

    @Test
    fun `a pose gap clears the live temporal window`() {
        val evaluator = ReceivingReadyPostureEvaluator(FaultProbabilityModel { 0.7f })
        var result = ReceivingReadyEvaluation(ReceivingReadyDecision.WarmingUp)
        repeat(32) { index ->
            result = evaluator.onFrame(TestPoses.wellFramedStanding(index * 33L))
        }
        assertEquals(ReceivingReadyDecision.Fault, result.decision)

        val firstAfterGap = evaluator.onFrame(
            TestPoses.wellFramedStanding(31 * 33L + 501L)
        )

        assertEquals(ReceivingReadyDecision.WarmingUp, firstAfterGap.decision)
        assertEquals(1f, firstAfterGap.reliableFrameFraction, 0.0001f)
    }

    @Test
    fun `quality gate covers every trunk landmark used by descriptors`() {
        val clean = TestPoses.wellFramedStanding()
        val requiredTorsoPoints = listOf(
            BodyPoint.NOSE,
            BodyPoint.LEFT_SHOULDER,
            BodyPoint.RIGHT_SHOULDER,
            BodyPoint.LEFT_HIP,
            BodyPoint.RIGHT_HIP,
        )

        requiredTorsoPoints.forEach { point ->
            val poor = clean.withPointVisibility(point, 0.1f)
            val evaluator = ReceivingReadyPostureEvaluator(FaultProbabilityModel { 0.01f })
            val result = evaluator.evaluate(List(20) { poor } + List(12) { clean })

            assertEquals(
                "$point must participate in the posture quality gate",
                ReceivingReadyDecision.InsufficientQuality,
                result.decision,
            )
        }
    }

    private fun PoseFrame.withPointVisibility(point: BodyPoint, visibility: Float): PoseFrame =
        copy(
            image = image.mapIndexed { index, landmark ->
                if (index == point.ordinal) {
                    landmark.copy(visibility = visibility, presence = visibility)
                } else {
                    landmark
                }
            },
        )
}
