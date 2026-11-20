package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.ExperienceLevel
import com.volleyballlab.player.domain.model.Skill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveInstructionsTest {
    private val cue = SpokenCue("Turn to your left, so your right shoulder points at the camera.", "Turn left.")

    @Test fun `an instruction is taught in full once, then given as its short cue`() {
        val coach = ProgressiveInstructions().apply { reset(ExperienceLevel.Developing) }
        assertEquals(cue.full, coach.speak(cue, 0L, 1))
        assertEquals(cue.short, coach.speak(cue, 10_000L, 2))
        assertEquals(cue.short, coach.speak(cue, 20_000L, 3))
    }

    @Test fun `a new athlete hears the full version twice`() {
        val coach = ProgressiveInstructions().apply { reset(ExperienceLevel.New) }
        assertEquals(cue.full, coach.speak(cue, 0L, 1))
        assertEquals(cue.full, coach.speak(cue, 10_000L, 2))
        assertEquals(cue.short, coach.speak(cue, 20_000L, 3))
    }

    @Test fun `each instruction is taught separately`() {
        val coach = ProgressiveInstructions().apply { reset(null) }
        val other = SpokenCue("Not counted. Bring both hands up together.", "Not counted. Both hands up.")
        coach.speak(cue, 0L, 1)
        assertEquals(other.full, coach.speak(other, 1_000L, 2))
        assertEquals(cue.short, coach.speak(cue, 2_000L, 3))
    }

    @Test fun `a long gap or a new session teaches it again`() {
        val coach = ProgressiveInstructions(relearnAfterMs = 120_000L).apply { reset(null) }
        coach.speak(cue, 0L, 1)
        assertEquals(cue.full, coach.speak(cue, 130_000L, 2))
        assertEquals(cue.short, coach.speak(cue, 140_000L, 3))
        coach.reset(null)
        assertEquals(cue.full, coach.speak(cue, 150_000L, 4))
    }

    @Test fun `a full line that was never heard is still owed`() {
        val coach = ProgressiveInstructions().apply { reset(null) }
        coach.speak(cue, 0L, 1)
        coach.dropped(1)
        assertEquals(cue.full, coach.speak(cue, 5_000L, 2))
        assertEquals(cue.short, coach.speak(cue, 10_000L, 3))
    }

    @Test fun `every short cue is shorter and every live line has one`() {
        val cues = MovementFault.entries.map(CoachPhrasebook::notCountedCue) +
            Skill.All.map(CoachPhrasebook::idleCue) +
            listOf(FacingGuide.freePracticeCue(OrientationEstimate())!!)
        cues.forEach { assertTrue("${it.short} should be shorter than ${it.full}", it.short.length < it.full.length) }
        assertTrue(cues.filter { it.full.startsWith(CoachPhrasebook.NOT_COUNTED_PREFIX) }
            .all { it.short.startsWith(CoachPhrasebook.NOT_COUNTED_PREFIX) })
    }
}
