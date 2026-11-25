package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.Rig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FacingGuideTest {
    private val detector = CameraOrientationDetector()
    private fun seen(view: Facing) = detector.detect(Rig.Standing.frame(0L, view))

    @Test fun `every skill faces forward for the front view and no live view turns the back to the camera`() {
        Skill.All.forEach { skill ->
            assertEquals(Facing.Forward, FacingGuide.stance(skill, CameraAngle.Front).facing)
            CameraAngle.entries.forEach { angle ->
                val stance = FacingGuide.stance(skill, angle)
                assertTrue(stance.facing != Facing.Away)
                val words = FacingGuide.instruction(skill, stance) + FacingGuide.label(stance.facing)
                assertFalse(words, Regex("\\b(back to|your back|away|rear)\\b", RegexOption.IGNORE_CASE).containsMatchIn(words))
            }
            assertTrue(FacingGuide.instruction(skill, FacingGuide.stance(skill, CameraAngle.Front)).startsWith("Face forward"))
        }
    }

    @Test fun `a one-armed skill turns so the hitting arm is nearest the camera`() {
        listOf(Skill.Serving, Skill.Spiking).forEach { skill ->
            val right = FacingGuide.stance(skill, CameraAngle.Side, Side.Right)
            assertEquals(Facing.Left, right.facing)
            assertEquals(Side.Right, right.requiredNearSide)
            assertTrue(FacingGuide.instruction(skill, right).startsWith("Turn to your left, so your right shoulder"))

            val left = FacingGuide.stance(skill, CameraAngle.Side, Side.Left)
            assertEquals(Facing.Right, left.facing)
            assertEquals(Side.Left, left.requiredNearSide)
            assertTrue(FacingGuide.instruction(skill, left).startsWith("Turn to your right, so your left shoulder"))
        }
    }

    @Test fun `an unknown hitting arm is never guessed into a requirement`() {
        val stance = FacingGuide.stance(Skill.Serving, CameraAngle.Side, hittingSide = null)
        assertNull(stance.requiredNearSide)
        val text = FacingGuide.instruction(Skill.Serving, stance)
        assertTrue(text.contains("hitting arm is nearest the camera"))
        assertTrue(text.contains("left if you hit right-handed") && text.contains("right if you hit left-handed"))
    }

    @Test fun `a two-handed skill is told which way to turn but accepts either profile`() {
        listOf(Skill.Receiving, Skill.Setting, Skill.Blocking).forEach { skill ->
            val stance = FacingGuide.stance(skill, CameraAngle.Side)
            assertEquals(Facing.Left, stance.facing)
            assertNull(stance.requiredNearSide)
            assertEquals("Turn to your left, so your right shoulder points at the camera.",
                FacingGuide.instruction(skill, stance))
            assertNull(FacingGuide.correction(skill, stance, seen(Facing.Left)))
            assertNull(FacingGuide.correction(skill, stance, seen(Facing.Right)))
        }
    }

    @Test fun `the detector agrees with the instruction about which shoulder a turn exposes`() {
        // Turning to your right puts your left shoulder nearest the lens, and the reverse.
        assertEquals(Side.Left, seen(Facing.Right).exposedSide)
        assertEquals(Side.Right, seen(Facing.Left).exposedSide)
        assertEquals(CameraAngle.Front, seen(Facing.Forward).angle)
        assertEquals(CameraAngle.Rear, seen(Facing.Away).angle)
    }

    @Test fun `facing the wrong way is corrected with the direction to turn`() {
        val front = FacingGuide.stance(Skill.Setting, CameraAngle.Front)
        assertNull(FacingGuide.correction(Skill.Setting, front, seen(Facing.Forward)))
        assertTrue(FacingGuide.correction(Skill.Setting, front, seen(Facing.Left))!!.startsWith("Face forward again"))
        assertTrue(FacingGuide.correction(Skill.Setting, front, seen(Facing.Away))!!.startsWith("Turn around and face forward"))

        val serveSide = FacingGuide.stance(Skill.Serving, CameraAngle.Side, Side.Right)
        assertNull(FacingGuide.correction(Skill.Serving, serveSide, seen(Facing.Left)))
        assertEquals("Turn around to face the other way, so your right shoulder points at the camera.",
            FacingGuide.correction(Skill.Serving, serveSide, seen(Facing.Right)))
        assertTrue(FacingGuide.correction(Skill.Serving, serveSide, seen(Facing.Forward))!!
            .startsWith("Turn side-on again. Turn to your left"))
    }

    @Test fun `free practice accepts the front and either side and refuses the back and a half turn`() {
        listOf(Facing.Forward, Facing.Left, Facing.Right).forEach { assertNull(FacingGuide.freePracticeCorrection(seen(it))) }
        assertTrue(FacingGuide.freePracticeCorrection(seen(Facing.Away))!!.startsWith("Turn around and face forward"))
        val halfTurned = OrientationEstimate(CameraAngle.Unknown)
        assertTrue(FacingGuide.freePracticeCorrection(halfTurned)!!.startsWith("Square up"))
    }

    @Test fun `the opening says where to face before anything is counted`() {
        assertEquals("Face forward for the first five reps, then I'll turn you side-on.", FacingGuide.opening(5))
        assertFalse(FacingGuide.opening(null).contains("reps"))
        assertTrue(FacingGuide.opening(null).startsWith("Face forward"))
    }
}
