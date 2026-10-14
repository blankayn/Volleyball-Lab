package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.FormCue
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.TestPoses
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame
import org.junit.Assert.*
import org.junit.Test

internal object GuidedPoseFixtures {
    fun front(timestampMs: Long = 0L): PoseFrame = TestPoses.wellFramedStanding(timestampMs).let { frame ->
        // Anatomical left projects on image right when facing the lens.
        frame.copy(image = frame.image.map { it.copy(x = 1f - it.x) }, world = frame.world.map { it.copy(x = -it.x) })
    }
    fun rear(timestampMs: Long = 0L): PoseFrame = TestPoses.wellFramedStanding(timestampMs).let { frame ->
        val face = BodyPoint.entries.take(11).map { it.ordinal }.toSet()
        frame.copy(
            image = frame.image.mapIndexed { index, point -> if (index in face) point.copy(visibility = 0.1f, presence = 0.1f) else point },
            world = frame.world.mapIndexed { index, point -> if (index in face) point.copy(visibility = 0.1f, presence = 0.1f) else point },
        )
    }
    fun side(timestampMs: Long = 0L): PoseFrame = front(timestampMs).let { frame ->
        val image = frame.image.toMutableList()
        val world = frame.world.toMutableList()
        listOf(BodyPoint.LEFT_SHOULDER, BodyPoint.LEFT_HIP).forEach {
            image[it.ordinal] = image[it.ordinal].copy(x = 0.49f)
            world[it.ordinal] = world[it.ordinal].copy(x = 0f, z = -0.18f)
        }
        listOf(BodyPoint.RIGHT_SHOULDER, BodyPoint.RIGHT_HIP).forEach {
            image[it.ordinal] = image[it.ordinal].copy(x = 0.51f)
            world[it.ordinal] = world[it.ordinal].copy(x = 0f, z = 0.18f)
        }
        frame.copy(image = image, world = world)
    }
    fun window(frame: PoseFrame): RepWindow {
        val frames = listOf(frame.copy(timestampMs = 0), frame.copy(timestampMs = 400), frame.copy(timestampMs = 800))
        return RepWindow(0, frames, mapOf(RepPhase.Preparation to frames[0], RepPhase.Loading to frames[0], RepPhase.Contact to frames[1], RepPhase.FollowThrough to frames[2]), Angles.hipCentreImage(frame).y)
    }
}

class GuidedAngleCoachTest {
    private val detector = CameraOrientationDetector()

    @Test fun `front rear side classifications preserve anatomical signs with mirroring`() {
        listOf(GuidedPoseFixtures.front() to CameraAngle.Front, GuidedPoseFixtures.side() to CameraAngle.Side,
            GuidedPoseFixtures.rear() to CameraAngle.Rear).forEach { (frame, expected) ->
            assertEquals(expected, detector.detect(frame).angle)
            assertEquals(expected, detector.detect(frame.copy(mirrored = true)).angle)
            assertTrue(detector.detect(frame).fullBodyVisible)
            assertTrue(detector.detect(frame).confidence >= 0.7f)
        }
    }

    @Test fun `hidden nose alone never confirms rear`() {
        val frame = GuidedPoseFixtures.front()
        val image = frame.image.map { it.copy(visibility = if (it == frame.image(BodyPoint.NOSE)) 0.1f else it.visibility) }
        assertEquals(CameraAngle.Unknown, detector.detect(frame.copy(image = image)).angle)
    }

    @Test fun `turning shoulders alone never confirms a side view`() {
        val front = GuidedPoseFixtures.front()
        val side = GuidedPoseFixtures.side()
        val hips = setOf(BodyPoint.LEFT_HIP.ordinal, BodyPoint.RIGHT_HIP.ordinal)
        val twist = side.copy(
            image = side.image.mapIndexed { index, point -> if (index in hips) front.image[index] else point },
            world = side.world.mapIndexed { index, point -> if (index in hips) front.world[index] else point },
        )
        assertEquals(CameraAngle.Unknown, detector.detect(twist).angle)
        assertFalse(OrientationStabilityGate().update(detector.detect(twist), CameraAngle.Side, 0L))
    }

    @Test fun `side framing accepts a hidden far leg but rejects both missing ankles`() {
        val side = GuidedPoseFixtures.side()
        val farLeg = setOf(BodyPoint.RIGHT_KNEE.ordinal, BodyPoint.RIGHT_ANKLE.ordinal)
        val occluded = side.copy(image = side.image.mapIndexed { index, point -> if (index in farLeg) point.copy(visibility = 0.1f) else point })
        assertTrue(detector.detect(occluded).fullBodyVisible)
        val ankles = setOf(BodyPoint.LEFT_ANKLE.ordinal, BodyPoint.RIGHT_ANKLE.ordinal)
        val missingFeet = side.copy(image = side.image.mapIndexed { index, point -> if (index in ankles) point.copy(visibility = 0.1f) else point })
        assertFalse(detector.detect(missingFeet).fullBodyVisible)
    }

    @Test fun `stable gate needs full second and resets on uncertainty gap wrong side`() {
        val gate = OrientationStabilityGate()
        val side = detector.detect(GuidedPoseFixtures.side())
        assertFalse(gate.update(side, CameraAngle.Side, 0, Side.Left))
        assertFalse(gate.update(side, CameraAngle.Side, 500, Side.Left))
        assertTrue(gate.update(side, CameraAngle.Side, 1000, Side.Left))
        assertFalse(gate.update(side, CameraAngle.Side, 1100, Side.Right))
        assertFalse(gate.update(side, CameraAngle.Side, 1200, Side.Left))
        assertFalse(gate.update(side, CameraAngle.Side, 1801, Side.Left))
        assertFalse(gate.update(OrientationEstimate(), CameraAngle.Side, 1900))
        assertFalse(gate.update(side, CameraAngle.Side, 2000, Side.Left))
        assertFalse(gate.update(side.copy(fullBodyVisible = false), CameraAngle.Side, 2400, Side.Left))
    }

    @Test fun `all skills mask face dependent cues on rear and all cues on unknown`() {
        val rear = GuidedPoseFixtures.rear()
        val faceCues = setOf(CueId.ServeTrunkLean, CueId.PassTrunkAngle, CueId.SpikeTrunkDrive, CueId.BlockPenetration, CueId.SetHandsAboveForehead)
        Skill.All.forEach { skill ->
            FormCueCatalog.forSkill(skill).forEach { cue ->
                assertFalse(AngleCuePolicy.isSupported(cue.id, CameraAngle.Unknown, rear))
                if (cue.id in faceCues) assertFalse(AngleCuePolicy.isSupported(cue.id, CameraAngle.Rear, rear))
            }
        }
        assertTrue(AngleCuePolicy.isSupported(CueId.PassShouldersLevel, CameraAngle.Rear, rear))
        assertTrue(AngleCuePolicy.isSupported(CueId.PassShouldersLevel, CameraAngle.Front, rear))
        assertFalse(AngleCuePolicy.isSupported(CueId.PassTrunkAngle, CameraAngle.Front, rear))
        assertFalse(AngleCuePolicy.isSupported(CueId.PassShouldersLevel, CameraAngle.Side, GuidedPoseFixtures.side()))
    }

    @Test fun `completed rear receiving never calls the front trained model`() {
        var calls = 0
        val evaluator = ReceivingReadyPostureEvaluator(FaultProbabilityModel { calls++; 0.01f })
        val scorer = SessionScorer()
        val result = scorer.scoreRep(GuidedPoseFixtures.window(GuidedPoseFixtures.rear()), Skill.Receiving, receivingPostureEvaluator = evaluator, angle = CameraAngle.Rear)
        assertEquals(0, calls)
        assertNull(result.cueScores.first { it.cueId == CueId.PassTrunkAngle }.score)
        assertNotNull(result.cueScores.first { it.cueId == CueId.PassShouldersLevel }.score)
    }

    @Test fun `prep never measures unseen contact and absent evidence is not good`() {
        Skill.All.forEach { skill ->
            val analyzer = object : SkillAnalyzer {
                override val skill = skill
                override fun segmentationSignal(frame: PoseFrame) = 0f
                override fun measure(cue: FormCue, window: RepWindow): Float? {
                    assertNotNull(window.frameFor(cue.phase))
                    assertEquals(setOf(RepPhase.Preparation), window.phaseFrames.keys)
                    return (cue.target.start + cue.target.endInclusive) / 2f
                }
            }
            val coach = LivePostureCoach(skill, analyzer)
            val ui = coach.update(GuidedPoseFixtures.front())
            assertTrue(ui.statuses.all { it.cue.phase == RepPhase.Preparation })
            if (ui.statuses.isEmpty()) assertFalse(ui.isGood)
        }
    }

    @Test fun `excellent partial scores need original seventy five percent weight for a good rep`() {
        val skill = Skill.Serving
        val analyzer = object : SkillAnalyzer {
            override val skill = Skill.Serving
            override fun segmentationSignal(frame: PoseFrame) = 0f
            override fun measure(cue: FormCue, window: RepWindow): Float? =
                if (cue.id == CueId.ServeTrunkLean || cue.id == CueId.ServeTossArmLift) null
                else (cue.target.start + cue.target.endInclusive) / 2f
        }
        val result = LivePostureCoach(skill, analyzer).assessCompletedRep(GuidedPoseFixtures.window(GuidedPoseFixtures.front()))
        assertEquals(100f, result.score!!, 0.01f)
        assertEquals(0.65f, result.measurableWeight, 0.01f)
        assertFalse(result.isAssessable)
        assertFalse(result.isGood)
    }

    @Test fun `toss cue uses opposite of contact hitter and checks actual preparation arm`() {
        fun raised(frame: PoseFrame, side: Side): PoseFrame {
            val wrist = AnalyzerSupport.wrist(side)
            val elbow = AnalyzerSupport.elbow(side)
            return frame.copy(
                image = frame.image.mapIndexed { index, point -> if (index == wrist.ordinal) point.copy(y = 0.10f) else point },
                world = frame.world.mapIndexed { index, point -> when (index) {
                    wrist.ordinal -> point.copy(y = -0.85f)
                    elbow.ordinal -> point.copy(y = -0.65f)
                    else -> point
                } },
            )
        }
        val preparation = raised(GuidedPoseFixtures.front(0L), Side.Left)
        val contact = raised(GuidedPoseFixtures.front(400L), Side.Right)
        val finish = GuidedPoseFixtures.front(800L)
        val window = RepWindow(0, listOf(preparation, contact, finish), mapOf(
            RepPhase.Preparation to preparation, RepPhase.Loading to preparation,
            RepPhase.Contact to contact, RepPhase.FollowThrough to finish,
        ), Angles.hipCentreImage(preparation).y)
        val coach = LivePostureCoach(Skill.Serving)
        val toss = coach.assessCompletedRep(window).statuses.first { it.cue.id == CueId.ServeTossArmLift }
        assertNotNull(toss.measured)
        assertEquals(Side.Left, toss.side)
        assertTrue(toss.affectedPoints.contains(BodyPoint.LEFT_WRIST))
        assertTrue(toss.affectedPoints.contains(BodyPoint.LEFT_SHOULDER))
        assertFalse(toss.affectedPoints.contains(BodyPoint.RIGHT_WRIST))
        fun hiddenPrep(wrist: BodyPoint): RepWindow {
            val hidden = preparation.copy(image = preparation.image.mapIndexed { index, point ->
                if (index == wrist.ordinal) point.copy(visibility = 0.1f) else point
            })
            return window.copy(frames = listOf(hidden, contact, finish),
                phaseFrames = window.phaseFrames + (RepPhase.Preparation to hidden))
        }
        val hiddenToss = coach.assessCompletedRep(hiddenPrep(BodyPoint.LEFT_WRIST)).statuses.first { it.cue.id == CueId.ServeTossArmLift }
        assertNull(hiddenToss.measured)
        val hiddenOther = coach.assessCompletedRep(hiddenPrep(BodyPoint.RIGHT_WRIST)).statuses.first { it.cue.id == CueId.ServeTossArmLift }
        assertNotNull(hiddenOther.measured)
        assertNull(coach.measureLive(preparation).first { it.cue.id == CueId.ServeTossArmLift }.measured)
    }
}
