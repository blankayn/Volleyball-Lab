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
}
