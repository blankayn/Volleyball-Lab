package com.volleyballlab.player.domain.model

import org.junit.Assert.*
import org.junit.Test

class CoachAssessmentTest {
    private fun session(skill: Skill = Skill.Serving, count: Int = 3, values: Map<CueId, Float> = emptyMap(),
                        id: String = "current", time: Long = 200, view: CameraAngle? = null): SessionResult {
        val reps = (0 until count).map { index ->
            RepResult(index, index * 1000L, index * 1000L + 500, index * 1000L + 900,
                FormCueCatalog.forSkill(skill).map { cue ->
                    val value = values[cue.id] ?: (cue.target.start + cue.target.endInclusive) / 2
                    CueScore(cue.id, value, cue.score(value))
                })
        }
        val stages = view?.let { listOf(SessionAngleStage(0, it, 0, 10_000, count, count, count, 100)) }.orEmpty()
        return SessionResult(id, skill, "same-drill", time, count * 1000L, reps, angleStages = stages)
    }

    @Test fun `all five skills expose a bounded explainable estimate without invented certainty`() {
        Skill.entries.forEach { skill ->
            val result = CoachAssessmentEngine.assess(session(skill))
            assertEquals(100, result.overallScore)
            assertEquals(1f, result.evidenceCoverage, 1e-5f)
            assertNull(result.calibratedModelConfidence)
            assertNull(result.timingScore)
            assertNull(result.coordinationScore)
            assertTrue(result.corrections.isEmpty())
        }
    }

    @Test fun `one perfect visible cue cannot produce a perfect overall score`() {
        val input = session().let { s -> s.copy(reps = s.reps.map { rep ->
            rep.copy(cueScores = rep.cueScores.take(1))
        }) }
        val result = CoachAssessmentEngine.assess(input)
        assertNull(result.overallScore)
        assertTrue(result.evidenceCoverage < CoachAssessmentEngine.MIN_COVERAGE)
    }

    @Test fun `isolated error is not enough evidence for a technical diagnosis`() {
        val healthy = session()
        val bad = session(values = mapOf(CueId.ServeContactArmExtension to 60f))
        val input = healthy.copy(reps = listOf(bad.reps.first()) + healthy.reps.drop(1))
        assertTrue(CoachAssessmentEngine.assess(input).corrections.isEmpty())
    }

    @Test fun `deep knee bend gets a correction in the right direction`() {
        val result = CoachAssessmentEngine.assess(session(Skill.Receiving,
            values = mapOf(CueId.PassKneeFlexion to 60f)))
        val correction = result.corrections.single { it.evidence.cue.id == CueId.PassKneeFlexion }
        assertTrue(correction.instruction.startsWith("Rise"))
        assertEquals("receive-wall-platform", correction.drillId)
        assertEquals(3, correction.evidence.measuredReps)
    }

    @Test fun `jump height alone cannot diagnose a spiking technique fault`() {
        val result = CoachAssessmentEngine.assess(session(Skill.Spiking,
            values = mapOf(CueId.SpikeJumpHeight to .01f)))
        assertEquals(100, result.overallScore)
        assertTrue(result.corrections.isEmpty())
    }

    @Test fun `duplicate cue records cannot manufacture three observed reps`() {
        val input = session().let { s -> s.copy(reps = s.reps.map { rep ->
            rep.copy(cueScores = List(3) { rep.cueScores.first() })
        }) }
        assertTrue(CoachAssessmentEngine.assess(input).observedCues.isEmpty())
    }

    @Test fun `unmeasurable and nonfinite evidence cannot produce feedback`() {
        val input = session().let { s -> s.copy(reps = s.reps.map { rep ->
            rep.copy(cueScores = rep.cueScores.map { it.copy(measured = Float.NaN, score = null) })
        }) }
        val result = CoachAssessmentEngine.assess(input)
        assertNull(result.overallScore)
        assertTrue(result.corrections.isEmpty())
    }

    @Test fun `comparable recorded views support raw measurement improvements`() {
        val old = session(values = mapOf(CueId.ServeContactArmExtension to 100f), id = "old", time = 100, view = CameraAngle.Front)
        val current = session(view = CameraAngle.Front)
        val result = CoachAssessmentEngine.assess(current, listOf(old))
        assertTrue(result.improvements.any { it.cue.id == CueId.ServeContactArmExtension && it.previousSessionId == "old" })
    }

    @Test fun `unknown or different camera views cannot be presented as improvement`() {
        val old = session(values = mapOf(CueId.ServeContactArmExtension to 100f), id = "old", time = 100, view = CameraAngle.Side)
        assertTrue(CoachAssessmentEngine.assess(session(view = CameraAngle.Front), listOf(old)).improvements.isEmpty())
        assertTrue(CoachAssessmentEngine.assess(session(), listOf(old)).improvements.isEmpty())
    }
}
