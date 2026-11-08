package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class PoseGuideTest {
    private val cue = requireNotNull(FormCueCatalog.byId(CueId.PassPlatformStraight))
    private val posture = LivePostureUi(phase = RepPhase.Contact, statuses = listOf(CueLiveStatus(cue, 100f, 0f)))
    private fun bentArms(): PoseFrame {
        val frame = GuidedPoseFixtures.front()
        val image = frame.image.toMutableList(); val world = frame.world.toMutableList()
        Side.entries.forEach { side ->
            val a = AnalyzerSupport.shoulder(side); val b = AnalyzerSupport.elbow(side); val c = AnalyzerSupport.wrist(side)
            val anchor = image[a.ordinal]; val anchorWorld = world[a.ordinal]
            image[b.ordinal] = anchor.copy(x = anchor.x + .03f, y = anchor.y + .13f)
            image[c.ordinal] = anchor.copy(x = anchor.x + .18f, y = anchor.y + .15f)
            listOf(b, c).forEach { point ->
                world[point.ordinal] = anchorWorld.copy(x = anchorWorld.x + (image[point.ordinal].x - anchor.x) * frame.imageWidth / 500,
                    y = anchorWorld.y + (image[point.ordinal].y - anchor.y) * frame.imageHeight / 500)
            }
        }
        return frame.copy(image = image, world = world)
    }

    @Test fun `guide preserves limb length and targets the catalogue range in the camera plane`() {
        val frame = bentArms()
        val guide = PoseGuide.limbs(frame, posture, Skill.Receiving, CameraAngle.Front)
        assertEquals(2, guide.size)
        guide.forEachIndexed { index, limb ->
            val wrist = frame.image(AnalyzerSupport.wrist(Side.entries[index]))
            fun length(x: Float, y: Float) = hypot((x - limb.joint.x) * frame.imageWidth, (y - limb.joint.y) * frame.imageHeight)
            assertEquals(length(wrist.x, wrist.y), length(limb.target.x, limb.target.y), .001f)
            val a = limb.anchor.copy(x = limb.anchor.x * frame.imageWidth, y = limb.anchor.y * frame.imageHeight, z = 0f)
            val b = limb.joint.copy(x = limb.joint.x * frame.imageWidth, y = limb.joint.y * frame.imageHeight, z = 0f)
            val c = limb.target.copy(x = limb.target.x * frame.imageWidth, y = limb.target.y * frame.imageHeight, z = 0f)
            assertEquals(172.5f, requireNotNull(Angles.at(a, b, c)), .02f)
        }
        assertEquals(guide, PoseGuide.limbs(frame.copy(mirrored = true), posture, Skill.Receiving, CameraAngle.Front))
    }

    @Test fun `wrong phase skill view and tracking uncertainty suppress the reference`() {
        val frame = bentArms()
        assertTrue(PoseGuide.limbs(frame, posture.copy(phase = RepPhase.Loading), Skill.Receiving, CameraAngle.Front).isEmpty())
        assertTrue(PoseGuide.limbs(frame, posture, Skill.Serving, CameraAngle.Front).isEmpty())
        assertTrue(PoseGuide.limbs(frame, posture, Skill.Receiving, CameraAngle.Unknown).isEmpty())
        assertTrue(PoseGuide.limbs(frame, posture.copy(trackingLost = true), Skill.Receiving, CameraAngle.Front).isEmpty())
        assertTrue(PoseGuide.limbs(frame, posture.copy(modelUncertain = true), Skill.Receiving, CameraAngle.Front).isEmpty())
        val hidden = frame.copy(image = frame.image.mapIndexed { index, point -> if (index == BodyPoint.LEFT_WRIST.ordinal) point.copy(visibility = .1f) else point })
        assertTrue(PoseGuide.limbs(hidden, posture, Skill.Receiving, CameraAngle.Front).isEmpty())
    }
}
