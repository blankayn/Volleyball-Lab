package com.volleyballlab.player.domain.model

import com.volleyballlab.player.data.skill.StaticDrillRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DrillPostureSamplesTest {
    @Test fun everyRealDrillHasReviewedFramesAndMatchingSkill() = runTest {
        val repository = StaticDrillRepository()
        val drills = Skill.All.flatMap { repository.drillsFor(it) }
        assertEquals(15, drills.size)
        drills.forEach { drill ->
            val sample = requireNotNull(DrillPostureSamples.forDrill(drill.id))
            assertEquals(drill.skill, sample.sheet.skill)
            assertEquals(4, sample.steps.size)
            assertTrue(sample.steps.all { it.label.isNotBlank() && it.cue.isNotBlank() })
            assertTrue(sample.row in 0..2)
        }
        assertNull(DrillPostureSamples.forDrill("unknown-drill"))
    }

    @Test fun framesStayInsideAtlasAndPreserveAllPixelsAtColumnEdges() {
        for (skill in Skill.All) {
            val sample = requireNotNull(DrillPostureSamples.forDrill(DrillPostureSamples.starterFor(skill)))
            val frames = (0..3).map { sample.frameRegion(it, 1086, 1448) }
            assertEquals(0, frames.first().x)
            assertEquals(1086, frames.last().x + frames.last().width)
            frames.zipWithNext().forEach { (left, right) -> assertEquals(left.x + left.width, right.x) }
            frames.forEach {
                assertEquals(0, it.y)
                assertEquals(sample.sheet.firstRowEnd, it.height)
                assertTrue(it.width > 0 && it.height > 0)
            }
            assertEquals(frames.first(), sample.frameRegion(-1, 1086, 1448))
            assertEquals(frames.last(), sample.frameRegion(99, 1086, 1448))
        }
    }

    @Test fun starterExamplesStayFreeAndFinalRowsEndAtBottomOfSheet() = runTest {
        val repository = StaticDrillRepository()
        for (skill in Skill.All) {
            val starter = DrillPostureSamples.starterFor(skill)
            assertTrue(starter in DrillAccessPolicy.starterIds)
            assertEquals(0, requireNotNull(DrillPostureSamples.forDrill(starter)).row)
            for (drill in repository.drillsFor(skill)) {
                val sample = requireNotNull(DrillPostureSamples.forDrill(drill.id))
                for (phase in 0..3) {
                    val frame = sample.frameRegion(phase, 1086, 1448)
                    assertTrue(frame.y >= 0 && frame.y + frame.height <= 1448)
                    if (sample.row == 2) assertEquals(1448, frame.y + frame.height)
                }
                assertEquals(skill == Skill.Serving || skill == Skill.Spiking, sample.supportsHandedness)
            }
        }
    }
}
