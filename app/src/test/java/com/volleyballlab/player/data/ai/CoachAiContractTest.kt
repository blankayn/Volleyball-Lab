package com.volleyballlab.player.data.ai

import com.volleyballlab.player.domain.model.AiCoachRequest
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.Skill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachAiContractTest {
    @Test fun `generated copy cannot reverse the evidence selected correction`() {
        val constrained = request.copy(approvedNextAction = "Rise slightly from the deep crouch.")
        val reversed = """{"focusCueId":"PassKneeFlexion","headline":"Ready position","explanation":"Keep a controlled base.","nextAction":"Bend your knees more."}"""
        assertNull(CoachAiContract.parseAndValidate(reversed, constrained))
        val supported = reversed.replace("Bend your knees more.", constrained.approvedNextAction!!)
        assertEquals(constrained.approvedNextAction, CoachAiContract.parseAndValidate(supported, constrained)?.nextAction)
    }

    @Test fun `posture evidence cannot justify invented timing or session improvement claims`() {
        for (claim in listOf("Your timing is early.", "Your landing improved.", "You improved since last session.")) {
            val raw = """{"focusCueId":"PassKneeFlexion","headline":"Ready position","explanation":"$claim","nextAction":"Keep a controlled base."}"""
            assertNull(CoachAiContract.parseAndValidate(raw, request))
        }
    }

    private val request = AiCoachRequest(
        focusCueId = CueId.PassKneeFlexion,
        skill = Skill.Receiving,
        scoreOutOfHundred = 62,
        measuredReps = 18,
        sessionCount = 3,
        unmeasuredPercent = 5,
    )

    @Test
    fun `accepts concise prose that keeps the verified focus cue`() {
        val result = CoachAiContract.parseAndValidate(
            raw = """
                {
                  "focusCueId":"PassKneeFlexion",
                  "headline":"Build a lower receiving base",
                  "explanation":"A steady knee bend helps you stay balanced while guiding the pass.",
                  "nextAction":"Sit your hips back and keep your knees softly bent."
                }
            """.trimIndent(),
            request = request,
        )

        assertEquals("Build a lower receiving base", result?.headline)
    }

    @Test
    fun `rejects a response that changes the pose engine focus`() {
        val raw = """
            {
              "focusCueId":"PassTrunkAngle",
              "headline":"Lean forward",
              "explanation":"Keep a steady base.",
              "nextAction":"Move with control."
            }
        """.trimIndent()

        assertNull(CoachAiContract.parseAndValidate(raw, request))
    }

    @Test
    fun `rejects invented numbers in coaching prose`() {
        val raw = """
            {
              "focusCueId":"PassKneeFlexion",
              "headline":"Bend to 90 degrees",
              "explanation":"Stay balanced.",
              "nextAction":"Lower your hips."
            }
        """.trimIndent()

        assertNull(CoachAiContract.parseAndValidate(raw, request))
    }

    @Test
    fun `prompt contains exact evidence and approved tip`() {
        val prompt = CoachAiContract.prompt(
            request = request,
            cueTitle = "Knee flexion",
            approvedTip = "Sit your hips back and bend your knees.",
        )

        assertTrue(prompt.contains("focusCueId=PassKneeFlexion"))
        assertTrue(prompt.contains("scoreOutOfHundred=62"))
        assertTrue(prompt.contains("measuredReps=18"))
        assertTrue(prompt.contains("approvedCoachingTip=Sit your hips back and bend your knees."))
    }
}
