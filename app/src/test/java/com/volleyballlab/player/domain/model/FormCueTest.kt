package com.volleyballlab.player.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FormCueTest {

    private val cue = FormCue(
        id = CueId.ServeContactArmExtension,
        phase = RepPhase.Contact,
        unit = CueUnit.Degrees,
        target = 155f..180f,
        warnTolerance = 30f,
        weight = 1f,
        label = 0,
        coachingTip = 0,
    )

    @Test
    fun `a measurement inside the target scores full marks`() {
        assertEquals(1f, cue.score(170f)!!, TOLERANCE)
    }

    @Test
    fun `the target boundaries are inclusive`() {
        assertEquals(1f, cue.score(155f)!!, TOLERANCE)
        assertEquals(1f, cue.score(180f)!!, TOLERANCE)
    }

    @Test
    fun `score falls off linearly across the tolerance band`() {
        // 15 degrees below the target start, half of the 30 degree tolerance.
        assertEquals(0.5f, cue.score(140f)!!, TOLERANCE)
    }

    @Test
    fun `beyond the tolerance the score bottoms out rather than going negative`() {
        assertEquals(0f, cue.score(120f)!!, TOLERANCE)
        assertEquals(0f, cue.score(0f)!!, TOLERANCE)
    }

    @Test
    fun `overshooting the target is penalised the same as undershooting`() {
        // 15 degrees past 180 is as wrong as 15 degrees short of 155.
        assertEquals(cue.score(140f)!!, cue.score(195f)!!, TOLERANCE)
    }

    /**
     * The single most important behaviour in this class: an unmeasurable cue is
     * not a failed cue. Scoring a null as zero would blame the athlete for the
     * camera angle, and would silently drag every session average down.
     */
    @Test
    fun `an unmeasurable cue scores null rather than zero`() {
        assertNull(cue.score(null))
    }

    @Test
    fun `every skill has a cue set whose weights form a weighted average`() {
        Skill.All.forEach { skill ->
            val cues = FormCueCatalog.forSkill(skill)
            assertTrue("$skill has no cues", cues.isNotEmpty())

            val total = cues.map { it.weight }.sum()
            assertTrue(
                "$skill weights sum to $total, expected 1.0",
                abs(total - 1f) < 1e-3f,
            )
        }
    }

    @Test
    fun `cue ids are unique across the whole catalogue`() {
        val all = Skill.All.flatMap { FormCueCatalog.forSkill(it) }
        val duplicates = all.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue("Duplicated cue ids: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `every declared cue id is reachable from the catalogue`() {
        // Guards against adding a CueId that no skill actually uses, which would
        // compile fine and then simply never be measured.
        CueId.entries.forEach { id ->
            assertNotNull("$id belongs to no skill", FormCueCatalog.byId(id))
        }
    }

    private companion object {
        const val TOLERANCE = 1e-4f
    }
}
