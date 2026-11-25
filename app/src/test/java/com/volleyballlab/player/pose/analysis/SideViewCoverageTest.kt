package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.FormCue
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Side-on, the far arm and leg are behind the body. The cues read from one limb
 * must still be read from the near one, and cues a side view never reads must not
 * count as missing, or every side-on rep is "only part of your form was visible".
 */
class SideViewCoverageTest {

    private val side = GuidedPoseFixtures.side(0L)

    /** [frame] with the far (right) elbow, wrist, knee and ankle hidden, as side-on. */
    private fun farHidden(frame: PoseFrame): PoseFrame {
        val far = setOf(BodyPoint.RIGHT_ELBOW, BodyPoint.RIGHT_WRIST, BodyPoint.RIGHT_KNEE, BodyPoint.RIGHT_ANKLE).map { it.ordinal }
        fun dim(points: List<com.volleyballlab.player.pose.model.Landmark>) =
            points.mapIndexed { i, p -> if (i in far) p.copy(visibility = .1f, presence = .1f) else p }
        return frame.copy(image = dim(frame.image), world = dim(frame.world))
    }

    @Test
    fun `the fixture reads as a side view`() {
        assertEquals(CameraAngle.Side, CameraOrientationDetector().detect(farHidden(side)).angle)
    }

    @Test
    fun `one seen arm or leg is enough for the cues read from the limbs in view`() {
        val frame = farHidden(side)
        listOf(CueId.PassPlatformStraight, CueId.PassKneeFlexion, CueId.SetElbowFlexion, CueId.SetKneeFlexion,
            CueId.BlockArmExtension, CueId.BlockKneeLoad, CueId.SpikeApproachLoad).forEach { cue ->
            assertEquals("$cue side-on with the far limbs hidden",
                AngleCuePolicy.Support.Measurable, AngleCuePolicy.support(cue, CameraAngle.Side, frame))
        }
    }

    @Test
    fun `with both arms hidden an arm cue is not seen`() {
        val near = setOf(BodyPoint.LEFT_ELBOW, BodyPoint.LEFT_WRIST).map { it.ordinal }
        val frame = farHidden(side).let { f ->
            f.copy(image = f.image.mapIndexed { i, p -> if (i in near) p.copy(visibility = .1f, presence = .1f) else p },
                world = f.world.mapIndexed { i, p -> if (i in near) p.copy(visibility = .1f, presence = .1f) else p })
        }
        assertEquals(AngleCuePolicy.Support.NotSeen, AngleCuePolicy.support(CueId.BlockArmExtension, CameraAngle.Side, frame))
    }

    @Test
    fun `cues a side view never reads are not in this view`() {
        val frame = farHidden(side)
        assertEquals(AngleCuePolicy.Support.NotInThisView, AngleCuePolicy.support(CueId.PassShouldersLevel, CameraAngle.Side, frame))
        assertEquals(AngleCuePolicy.Support.NotInThisView, AngleCuePolicy.support(CueId.BlockHandSeparation, CameraAngle.Side, frame))
        assertEquals(AngleCuePolicy.Support.NotInThisView,
            AngleCuePolicy.support(CueId.SpikeElbowHeight, CameraAngle.Front, GuidedPoseFixtures.front(0L)))
    }

    @Test
    fun `knee angle side-on reads the leg in view, not the hidden one`() {
        val frame = farHidden(side)
        assertEquals(AnalyzerSupport.kneeAngle(frame, Side.Left)!!, AnalyzerSupport.visibleKneeAngle(frame)!!, 0.001f)
        // With both legs seen it is the same as the two-leg mean.
        assertEquals(AnalyzerSupport.meanKneeAngle(side)!!, AnalyzerSupport.visibleKneeAngle(side)!!, 0.001f)
    }

    @Test
    fun `a side-on rep with the near side in view is fully assessed`() {
        val frames = List(6) { farHidden(GuidedPoseFixtures.side(it * 33L)) }
        val window = RepWindow(0, frames, RepPhase.entries.associateWith { frames[if (it == RepPhase.Preparation) 0 else 3] }, 0.5f)
        val assessment = LivePostureCoach(Skill.Blocking, measuringEverything(Skill.Blocking)).assessCompletedRep(window, CameraAngle.Side)
        // Hand separation is not read side-on, so it is not part of what must be covered.
        assertEquals(0.75f, assessment.applicableWeight, 0.001f)
        assertTrue(assessment.isAssessable)
        assertFalse(assessment.viewLimited)
    }

    @Test
    fun `a rep whose view reads too little is view-limited, not partly seen`() {
        val frames = List(6) { farHidden(GuidedPoseFixtures.side(it * 33L)) }
        val window = RepWindow(0, frames, RepPhase.entries.associateWith { frames[3] }, 0.5f)
        // Only knee load is read, and it is all the view reads for this coach's cue list.
        val coach = LivePostureCoach(Skill.Blocking, measuringEverything(Skill.Blocking),
            cues = FormCueCatalog.forSkill(Skill.Blocking).filter { it.id in setOf(CueId.BlockKneeLoad, CueId.BlockHandSeparation) })
        val assessment = coach.assessCompletedRep(window, CameraAngle.Side)
        assertFalse(assessment.isAssessable)
        assertTrue(assessment.viewLimited)
        val feedback = PersonalizedLiveCoaching.feedback(assessment, CameraAngle.Side, LiveCoachingPlan(), 1, 1, 5, null)
        assertEquals(LiveRepVerdict.Partial, feedback.verdict)
        assertTrue(feedback.spokenText.contains("knee bend"))
    }

    @Test
    fun sideReceivingScoresTheVisibleKnee() {
        val frame = farHidden(side)
        val window = RepWindow(0, listOf(frame), RepPhase.entries.associateWith { frame }, 0.5f)
        val assessment = LivePostureCoach(Skill.Receiving).assessCompletedRep(window, CameraAngle.Side)
        assertTrue(assessment.statuses.single { it.cue.id == CueId.PassKneeFlexion }.isMeasurable)
    }

    /** The real analyzer, but every cue it is asked for reads a mid-range value. */
    private fun measuringEverything(skill: Skill): SkillAnalyzer = object : SkillAnalyzer by SkillAnalyzers.forSkill(skill) {
        override fun measure(cue: FormCue, window: RepWindow): Float = (cue.target.start + cue.target.endInclusive) / 2f
    }
}
