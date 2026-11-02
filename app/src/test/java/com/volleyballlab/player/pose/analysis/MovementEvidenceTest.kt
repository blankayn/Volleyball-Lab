package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.pose.TestPoses
import com.volleyballlab.player.pose.model.BodyPoint
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class MovementEvidenceTest {
    private fun window(): RepWindow {
        val frames = (0..30).map { TestPoses.wellFramedStanding(it * 33L) }
        return RepWindow(0, frames, mapOf(RepPhase.Preparation to frames.first(), RepPhase.Loading to frames[5],
            RepPhase.Contact to frames[20], RepPhase.FollowThrough to frames.last()), .5f)
    }

    @Test fun `visible stance can be observed without inventing a timing grade from a static pose`() {
        val evidence = MovementEvidence.observe(window())
        assertNotNull(evidence)
        assertNotNull(evidence!!.stanceWidthShoulderRatio)
        assertNull(evidence.kneeLoadToArmPeakMs)
    }

    @Test fun `missing ankles prevent foot positioning and temporal claims`() {
        val original = window()
        val frames = original.frames.map { frame -> frame.copy(image = frame.image.mapIndexed { i, point ->
            if (i == BodyPoint.LEFT_ANKLE.ordinal) point.copy(visibility = 0f) else point
        }) }
        assertNull(MovementEvidence.observe(original.copy(frames = frames)))
    }

    @Test fun `discontinuous capture cannot produce a coordination observation`() {
        val original = window()
        val frames = original.frames.mapIndexed { i, frame -> if (i >= 15) frame.copy(timestampMs = frame.timestampMs + 1000) else frame }
        assertNull(MovementEvidence.observe(original.copy(frames = frames)))
    }

    @Test fun `incomplete phases cannot be treated as a complete movement`() {
        val original = window()
        assertNull(MovementEvidence.observe(original.copy(phaseFrames = original.phaseFrames - RepPhase.FollowThrough)))
    }

    @Test fun `independently observed knee and arm peaks preserve their temporal interval`() {
        val original = window()
        val frames = original.frames.mapIndexed { index, frame ->
            val kneeBend = (1f - abs(index - 10) / 10f).coerceAtLeast(0f)
            val armReach = (1f - abs(index - 20) / 10f).coerceAtLeast(0f)
            frame.copy(world = frame.world.mapIndexed { pointIndex, point ->
                if (pointIndex == BodyPoint.LEFT_KNEE.ordinal || pointIndex == BodyPoint.RIGHT_KNEE.ordinal)
                    point.copy(z = .3f * kneeBend) else point
            }, image = frame.image.mapIndexed { pointIndex, point ->
                if (pointIndex == BodyPoint.LEFT_WRIST.ordinal || pointIndex == BodyPoint.RIGHT_WRIST.ordinal)
                    point.copy(y = .47f - .3f * armReach) else point
            })
        }
        val evidence = MovementEvidence.observe(original.copy(frames = frames))
        assertEquals(330L, evidence?.kneeLoadToArmPeakMs)
    }
}
