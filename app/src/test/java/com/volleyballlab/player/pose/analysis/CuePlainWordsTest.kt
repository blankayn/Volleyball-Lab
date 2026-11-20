package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.pose.analysis.CuePlainWords.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CuePlainWordsTest {

    /** The whole point: what a player reads carries no number and no unit. */
    @Test
    fun `no cue ever shows a number or a unit`() {
        for (id in CueId.entries) {
            val texts = listOf(CuePlainWords.name(id)) + Zone.entries.map { CuePlainWords.verdict(id, it) }
            for (text in texts) {
                assertTrue("$id: '$text' is empty", text.isNotBlank())
                assertFalse("$id: '$text' has a digit", text.any(Char::isDigit))
                assertFalse("$id: '$text' has a unit", text.contains("°") || Regex("\\b(deg|m|cm|%)\\b").containsMatchIn(text))
            }
        }
    }

    @Test
    fun `every cue in the catalog can be read`() {
        for (id in CueId.entries) {
            val cue = assertNotNullCue(id)
            val mid = (cue.target.start + cue.target.endInclusive) / 2f
            assertEquals(Zone.InRange, CuePlainWords.read(cue, mid).zone)
        }
    }

    @Test
    fun `the range edges count as in range`() {
        val cue = assertNotNullCue(CueId.SpikeArmExtension)
        assertEquals(Zone.InRange, CuePlainWords.zone(cue, cue.target.start))
        assertEquals(Zone.InRange, CuePlainWords.zone(cue, cue.target.endInclusive))
        assertEquals(Zone.Below, CuePlainWords.zone(cue, cue.target.start - 0.5f))
        assertEquals(Zone.Above, CuePlainWords.zone(cue, cue.target.endInclusive + 0.5f))
    }

    /** Directions must agree with what the coach says aloud (CoachPhrasebook). */
    @Test
    fun `directions match the spoken coaching`() {
        val arm = assertNotNullCue(CueId.SpikeArmExtension)
        assertEquals("Arm extension: a bit bent", CuePlainWords.read(arm, arm.target.start - 15f).line)
        assertEquals("Arm extension: straight", CuePlainWords.read(arm, 170f).line)

        // A smaller knee angle is a deeper crouch.
        val knees = assertNotNullCue(CueId.PassKneeFlexion)
        assertEquals("a bit deep", CuePlainWords.read(knees, knees.target.start - 10f).verdict)
        assertEquals("a bit straight", CuePlainWords.read(knees, knees.target.endInclusive + 10f).verdict)

        // Less trunk lean is more upright.
        val trunk = assertNotNullCue(CueId.PassTrunkAngle)
        assertEquals("a bit upright", CuePlainWords.read(trunk, trunk.target.start - 5f).verdict)
        assertEquals("leaning a bit far", CuePlainWords.read(trunk, trunk.target.endInclusive + 5f).verdict)

        val reach = assertNotNullCue(CueId.BlockPenetration)
        assertEquals("staying back", CuePlainWords.read(reach, 0f).verdict)
    }

    @Test
    fun `the gauge places the range and the athlete between zero and one`() {
        val cue = assertNotNullCue(CueId.SpikeArmExtension)
        val inside = CuePlainWords.read(cue, 170f)
        assertTrue(inside.rangeStart > 0f && inside.rangeEnd <= 1f && inside.rangeStart < inside.rangeEnd)
        assertTrue(inside.position in inside.rangeStart..inside.rangeEnd)

        val below = CuePlainWords.read(cue, cue.target.start - 10f)
        assertTrue(below.position < below.rangeStart)

        // Far outside still lands on the bar, pinned to its end.
        assertEquals(0f, CuePlainWords.read(cue, 0f).position)
        assertEquals(1f, CuePlainWords.read(cue, 999f).position)
    }

    private fun assertNotNullCue(id: CueId) = FormCueCatalog.byId(id).also { assertNotNull("$id missing from catalog", it) }!!
}
