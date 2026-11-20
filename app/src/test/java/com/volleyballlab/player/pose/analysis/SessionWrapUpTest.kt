package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class SessionWrapUpTest {
    private val maya = LiveCoachingPlan(athleteName = "Maya")
    private fun note(resolved: Set<CueId> = emptySet(), open: CueId? = null, angle: CameraAngle = CameraAngle.Front) =
        StageNote(angle, 5, 4, 3, resolved, open)

    private fun session(id: String, skill: Skill, drill: String, at: Long, weakest: CueId? = null) = SessionResult(
        id, skill, drill, at, 60_000,
        reps = if (weakest == null) emptyList() else (0 until 3).map {
            RepResult(it, 0, 1, 2, listOf(CueScore(weakest, 100f, 0.4f)))
        },
    )

    @Test fun closingStatesTheCountsAndClaimsAWinOnlyForAResolvedCue() {
        val plain = SessionWrapUp.closing(maya, 15, 11, 4, listOf(note()), endedEarly = false)
        assertEquals(listOf("That's fifteen reps, Maya.", "Eleven were in range, with a best run of four.",
            "Let's look at your review."), plain)
        assertTrue(plain.none { "came good" in it || "keep working" in it })

        val earned = SessionWrapUp.closing(maya, 15, 11, 4,
            listOf(note(resolved = setOf(CueId.PassPlatformStraight)), note(open = CueId.PassKneeFlexion, angle = CameraAngle.Side)), false)
        assertTrue("Your forearm platform came good." in earned)
        assertTrue("Next time, keep working the knee bend." in earned)
        assertEquals("Let's look at your review.", earned.last())
    }

    @Test fun aCueStillOpenIsNeverAlsoCalledAWin() {
        val closing = SessionWrapUp.closing(LiveCoachingPlan(), 10, 3, 1,
            listOf(note(resolved = setOf(CueId.PassKneeFlexion)), note(open = CueId.PassKneeFlexion, angle = CameraAngle.Side)), false)
        assertTrue(closing.none { "came good" in it })
        assertEquals("That's ten reps.", closing.first())
        assertTrue("Three were in range." in closing)
    }

    @Test fun anEarlyOrEmptyFinishIsHonestAndShort() {
        val early = SessionWrapUp.closing(maya, 7, 6, 5,
            listOf(note(resolved = setOf(CueId.PassPlatformStraight), open = CueId.PassKneeFlexion)), endedEarly = true)
        assertEquals(listOf("That's seven reps, Maya.", "We stopped early, so this one is saved as incomplete.",
            "Next time, keep working the knee bend."), early)
        assertEquals(listOf("No reps counted this time."),
            SessionWrapUp.closing(LiveCoachingPlan(), 0, 0, 0, emptyList(), false))
        assertEquals("That's one rep.", SessionWrapUp.closing(LiveCoachingPlan(), 1, 0, 0, emptyList(), false).first())
        assertTrue("One was in range." in SessionWrapUp.closing(LiveCoachingPlan(), 1, 1, 1, emptyList(), false))
    }

    @Test fun debriefUsesCountedFactsAndOneDirectionCorrectTakeaway() {
        val takeaway = CoachPhrasebook.instructions(CueId.PassKneeFlexion, 180f).first()
        assertEquals("Front view done, four of five in range. Your knees are staying a bit straight. Bend a little more.",
            SessionWrapUp.debrief(CameraAngle.Front, 5, 4, true, takeaway, CueId.PassPlatformStraight))
        assertEquals("Side view done, five of five in range, and your forearm platform held the whole way.",
            SessionWrapUp.debrief(CameraAngle.Side, 5, 5, true, null, CueId.PassPlatformStraight))
        // A held cue is not mentioned unless every rep of the view was in range.
        assertEquals("Side view done, four of five in range.",
            SessionWrapUp.debrief(CameraAngle.Side, 5, 4, true, null, CueId.PassPlatformStraight))
        assertEquals("Rear view done, five reps.", SessionWrapUp.debrief(CameraAngle.Rear, 5, 0, true))
        assertEquals("Rear view done. Some of your form couldn't be assessed from here.",
            SessionWrapUp.debrief(CameraAngle.Rear, 5, 0, false))
    }

    @Test fun openingMentionsHistoryOnlyWhenAPriorSessionOnThisDrillExists() {
        val first = maya.withHistory(listOf(session("a", Skill.Setting, "drill", 1), session("b", Skill.Receiving, "other", 2)),
            Skill.Receiving, "drill")
        assertEquals(0, first.sessionsOnSkill)
        assertEquals("Hi Maya.", first.opening(Skill.Receiving).first())
        assertTrue(first.opening(Skill.Receiving).none { "Last time" in it || "Welcome back" in it })

        val returning = maya.withHistory(listOf(
            session("old", Skill.Receiving, "drill", 1, CueId.PassPlatformStraight),
            session("new", Skill.Receiving, "drill", 5, CueId.PassKneeFlexion),
            session("other", Skill.Receiving, "elsewhere", 9, CueId.PassTrunkAngle)), Skill.Receiving, "drill")
        assertEquals(2, returning.sessionsOnSkill)
        assertEquals(CueId.PassKneeFlexion, returning.lastSessionFocus)
        val opening = returning.opening(Skill.Receiving, repsPerAngle = 5)
        assertEquals("Welcome back, Maya.", opening[0])
        assertEquals("Last time your knee bend needed the most work, so let's see it early.", opening[1])
        assertTrue("Face forward for the first five reps, then I'll turn you side-on." in opening)
        assertEquals("I'll watch the first couple before I say anything.", opening.last())

        // A prior session with nothing measurable is remembered, but no cue is invented for it.
        val unmeasured = maya.withHistory(listOf(session("x", Skill.Receiving, "drill", 1)), Skill.Receiving, "drill")
        assertEquals(listOf("Welcome back, Maya."), unmeasured.opening(Skill.Receiving).take(1))
        assertTrue(unmeasured.opening(Skill.Receiving).none { "Last time" in it })
    }

    @Test fun onlyAShortDrillTitleAndFirstNameComeFromTheLinkedCoach() {
        val assigned = maya.withCoach("Ana Reyes", "  Platform <b>basics</b>:  set 2 ")
        assertEquals("Ana", assigned.humanCoachName)
        assertEquals("Platform bbasicsb set 2", assigned.assignmentTitle)
        assertTrue("Coach Ana set this one: Platform bbasicsb set 2." in assigned.opening(Skill.Receiving))
        assertTrue("Your coach set this one: Passing." in maya.withCoach(null, "Passing").opening(Skill.Receiving))
        assertTrue(maya.withCoach("Ana", null).opening(Skill.Receiving).none { "set this one" in it })
        assertTrue(maya.withCoach("Ana", "x".repeat(200)).assignmentTitle!!.length <= 60)
    }
}
