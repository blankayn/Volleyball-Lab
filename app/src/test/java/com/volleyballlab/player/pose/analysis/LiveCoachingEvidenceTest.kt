package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class LiveCoachingEvidenceTest {
    private val cue = requireNotNull(FormCueCatalog.byId(CueId.ServeContactArmExtension))
    private fun rep(value: Float?, score: Float? = cue.score(value)) = LiveRepAssessment(null, .35f, false, false,
        listOf(CueLiveStatus(cue, value, score)))

    @Test fun `a single poor rep and hidden cues cannot become detailed coaching findings`() {
        val buffer = LiveCoachingEvidenceBuffer()
        buffer.add(rep(100f)); buffer.add(rep(null)); buffer.add(rep(120f, null))
        assertNull(buffer.snapshot(Skill.Serving, CameraAngle.Front).focus())
        buffer.add(rep(Float.NaN, 0f))
        assertNull(buffer.snapshot(Skill.Serving, CameraAngle.Front).focus())
    }

    @Test fun `a repeated observed cue gets one focus without requiring a full body score`() {
        val buffer = LiveCoachingEvidenceBuffer()
        repeat(5) { buffer.add(rep(120f)) }
        val snapshot = buffer.snapshot(Skill.Serving, CameraAngle.Front)
        assertEquals(CueId.ServeContactArmExtension, snapshot.focus()?.cueId)
        assertEquals("below", snapshot.focus()?.position)
        buffer.reset()
        assertNull(buffer.snapshot(Skill.Serving, CameraAngle.Side).focus())
        assertEquals(5, snapshot.reps.size)
    }

    @Test fun `in range measurements and wrong skill cues are not a correction`() {
        val buffer = LiveCoachingEvidenceBuffer()
        repeat(5) { buffer.add(rep(170f)) }
        assertNull(buffer.snapshot(Skill.Serving, CameraAngle.Front).focus())
        assertNull(buffer.snapshot(Skill.Setting, CameraAngle.Front).focus())
    }

    private fun evidence(value: Float, count: Int = 5, skill: Skill = Skill.Serving) = LiveCoachingEvidence(skill, CameraAngle.Front,
        List(count) { listOf(LiveCueMeasurement(CueId.ServeContactArmExtension, value)) })

    @Test fun `the app and the worker choose the same focus from the shared parity fixture`() {
        val file = java.io.File("../ai-worker/test/fixtures/live-coaching-parity.json")
        assertTrue("Run from the app module so the shared fixture resolves: ${file.absolutePath}", file.isFile)
        val cases = kotlinx.serialization.json.Json.parseToJsonElement(file.readText())
            .let { it as kotlinx.serialization.json.JsonObject }["cases"] as kotlinx.serialization.json.JsonArray
        assertTrue(cases.size >= 6)
        cases.map { it as kotlinx.serialization.json.JsonObject }.forEach { case ->
            fun text(element: kotlinx.serialization.json.JsonElement?) = (element as kotlinx.serialization.json.JsonPrimitive).content
            val reps = (case["reps"] as kotlinx.serialization.json.JsonArray).map { rep ->
                (rep as kotlinx.serialization.json.JsonArray).map { it as kotlinx.serialization.json.JsonObject }
                    .map { LiveCueMeasurement(CueId.valueOf(text(it["cueId"])), text(it["measured"]).toFloat()) }
            }
            val focus = LiveCoachingEvidence(Skill.valueOf(text(case["skill"])), CameraAngle.valueOf(text(case["angle"])), reps).focus()
            val expected = case["expect"] as? kotlinx.serialization.json.JsonObject
            assertEquals(text(case["name"]), expected?.let { text(it["cueId"]) to text(it["position"]) },
                focus?.let { it.cueId.name to it.position })
        }
    }

    @Test fun `the request carries only measurements and closed context values`() {
        assertNull("A legacy request needs a local focus", LiveCoachingWire.request(evidence(170f), false))
        assertNull(LiveCoachingWire.request(evidence(120f, count = 2), false, LiveCoachingTurnContext()))
        assertEquals(setOf("skill", "angle", "voice", "reps"), LiveCoachingWire.request(evidence(120f), true)!!.keys)
        val context = LiveCoachingTurnContext(LiveCoachingTurnContext.Turn.Closing, 7, ExperienceLevel.New, true,
            setOf(CueId.ServeContactArmExtension, CueId.PassKneeFlexion))
        val request = LiveCoachingWire.request(evidence(170f), false, context)!!
        assertEquals(setOf("skill", "angle", "voice", "reps", "context"), request.keys)
        assertEquals(mapOf("v" to 1, "turn" to "closing", "breakIndex" to 2, "experience" to "New",
            "isAthleteFocus" to true, "corrected" to listOf("ServeContactArmExtension")), request["context"])
    }

    @Test fun `a server cue is accepted only when this device measured it and praise needs local agreement`() {
        fun decode(evidence: LiveCoachingEvidence, vararg fields: Pair<String, Any?>) =
            LiveCoachingWire.decode(mapOf("status" to "ready", "source" to "groq", *fields), evidence) { "ball" !in it }
        val missed = evidence(120f)
        val advice = decode(missed, "cueId" to "ServeContactArmExtension", "text" to "Reach a little taller.")!!
        assertEquals(LiveAdviceKind.Correction, advice.kind); assertTrue(advice.aiGenerated)
        assertEquals(LiveAdviceKind.Correction, decode(missed, "cueId" to "ServeContactArmExtension", "kind" to "correction", "text" to "Reach.")!!.kind)
        // Not measured here, another skill's cue, unknown ids and unknown kinds are all refused.
        assertNull(decode(missed, "cueId" to "ServeReachHeight", "text" to "Reach."))
        assertNull(decode(missed, "cueId" to "PassKneeFlexion", "text" to "Bend."))
        assertNull(decode(missed, "cueId" to "Invented", "text" to "Reach."))
        assertNull(decode(missed, "cueId" to "ServeContactArmExtension", "kind" to "celebration", "text" to "Reach."))
        assertNull(decode(missed, "cueId" to "ServeContactArmExtension", "kind" to "praise", "text" to "Your arm held."))
        assertNull(decode(missed, "cueId" to "ServeContactArmExtension", "text" to "Meet the ball higher."))
        assertNull(decode(missed, "cueId" to "ServeContactArmExtension", "text" to "Score 9."))
        assertNull(LiveCoachingWire.decode(mapOf("status" to "insufficient"), missed) { true })
        val clean = evidence(170f)
        assertEquals(LiveAdviceKind.Praise, decode(clean, "cueId" to "ServeContactArmExtension", "kind" to "praise", "text" to "Your arm held.")!!.kind)
        assertNull(decode(clean, "cueId" to "ServeContactArmExtension", "text" to "Reach a little taller."))
        assertNull(decode(evidence(170f, count = 2), "cueId" to "ServeContactArmExtension", "kind" to "praise", "text" to "Your arm held."))
    }
}
