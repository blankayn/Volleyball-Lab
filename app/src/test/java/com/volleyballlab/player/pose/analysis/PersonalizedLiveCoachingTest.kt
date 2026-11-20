package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class PersonalizedLiveCoachingTest {
    private fun status(value: Float?, id: CueId = CueId.PassPlatformStraight): CueLiveStatus {
        val cue = requireNotNull(FormCueCatalog.byId(id))
        return CueLiveStatus(cue, value, cue.score(value))
    }

    private fun assessment(vararg statuses: CueLiveStatus, assessable: Boolean = true, good: Boolean = false) =
        LiveRepAssessment(if (good) 100f else 50f, 1f, assessable, good, statuses.toList())

    private fun feedback(assessment: LiveRepAssessment, total: Int = 1, stage: Int = 1,
        target: Int? = 5, angle: CameraAngle = CameraAngle.Front, direction: CoachingEvent? = null,
        previous: LiveRepFeedback? = null, plan: LiveCoachingPlan = LiveCoachingPlan()) =
        PersonalizedLiveCoaching.feedback(assessment, angle, plan, total, stage, target, direction, previous)

    private fun profile(skill: Skill, question: String, option: String) = AthleteProfile(
        name = "Maya Santos", firstName = "Maya",
        trainingSetup = SavedTrainingSetup(plan = TrainingSetup(skill, 3, "Practice", ExperienceLevel.New,
            "", "Practice", "local", TrainingSetup.starterFor(skill), 0, questionId = question, optionId = option)),
    )

    @Test fun firstCompletedRepGetsACountAndSpecificMeasuredVerdict() {
        val result = feedback(assessment(status(130f)))
        assertEquals(LiveRepVerdict.Adjust, result.verdict)
        assertEquals("One. Your forearm platform needs work.", result.spokenText)
    }

    @Test fun goodRepIsJudgedWithoutInventingImprovementOrPerfectForm() {
        val result = feedback(assessment(status(175f), good = true))
        assertEquals(LiveRepVerdict.Good, result.verdict)
        assertEquals("Form target met.", result.detail)
        assertTrue(result.spokenText.startsWith("One. "))
        assertFalse(result.spokenText.contains("target met"))
        assertFalse(result.spokenText.contains("improv", true))
        assertFalse(result.spokenText.contains("better", true))
    }

    @Test fun partialCoverageCountsMovementWithoutCallingItAGoodRep() {
        val result = feedback(assessment(status(175f), status(null, CueId.PassKneeFlexion), assessable = false, good = true))
        assertEquals(LiveRepVerdict.Partial, result.verdict)
        assertTrue(result.spokenText.contains("Only part"))
        assertTrue(result.spokenText.startsWith("One."))
        assertEquals("Rep 1 of 5", result.countText)
    }

    @Test fun unavailableAndNonfiniteMeasurementsCannotEarnAJudgment() {
        val invalid = listOf(status(null), status(Float.NaN), status(Float.POSITIVE_INFINITY),
            status(175f).copy(score = Float.NaN), status(175f).copy(score = -1f), status(175f).copy(score = 1.1f))
        invalid.forEach { value ->
            val result = feedback(assessment(value, good = true))
            assertEquals(LiveRepVerdict.Unrated, result.verdict)
            assertTrue(result.spokenText.contains("couldn't judge"))
        }
        assertEquals(LiveRepVerdict.Unrated,
            feedback(assessment(status(175f), good = true), angle = CameraAngle.Unknown).verdict)
        assertEquals(LiveRepVerdict.Unrated,
            feedback(assessment(status(0.5f, CueId.SpikeJumpHeight), good = true)).verdict)
    }

    @Test fun aMeasuredFaultIsNotHiddenByAnOtherwisePassingAverage() {
        assertEquals(LiveRepVerdict.Adjust, feedback(assessment(status(130f), good = true)).verdict)
    }

    @Test fun countsMatchTheCurrentViewAndFreePracticeUsesTheSessionTotal() {
        val rep = assessment(status(175f), good = true)
        assertEquals("Rep 1 of 5", feedback(rep, total = 6).countText)
        assertEquals("Rep 10 of 10", feedback(rep, total = 20, stage = 10, target = 10).countText)
        assertEquals("Rep 6", feedback(rep, total = 6, target = null).countText)
    }

    @Test fun theCountAndOneDirectorCorrectionShareOneSentenceTurn() {
        val correction = CoachingEvent(7, CoachingEventType.Correction, "Straighten both elbows and join your forearms.", 10_000, 1)
        val result = feedback(assessment(status(130f)), total = 3, stage = 3, direction = correction)
        assertEquals("Three. ${correction.text}", result.spokenText)
        assertEquals(CoachingEventType.Correction, result.type)
        assertFalse(result.spokenText.contains("needs work"))
    }

    @Test fun confirmedFocusAndNamePersonalizeTheOpeningWithoutChangingEvidence() {
        val plan = LiveCoachingPlan.forAthlete(Skill.Receiving, profile(Skill.Receiving, "receiving_focus", "platform"))
        assertEquals(setOf(CueId.PassPlatformStraight, CueId.PassShouldersLevel), plan.focusCueIds)
        assertEquals("Maya’s live coach", plan.title)
        val opening = plan.opening(Skill.Receiving)
        assertEquals("Hi Maya.", opening.first())
        assertEquals("Today's about your forearm platform.", opening[1])
        assertEquals("I'll watch the first couple before I say anything.", opening.last())
        // Name, focus, setup, which way to face, then the promise to watch first.
        assertEquals(FacingGuide.opening(null), opening[3])
        assertEquals(5, opening.size)
        assertEquals(opening.joinToString(" "), plan.openingText(Skill.Receiving))
        assertFalse(plan.openingText(Skill.Receiving).contains("Santos"))
    }

    @Test fun anotherSkillsGoalOrAnUnknownQuestionCannotInventAPriority() {
        assertTrue(LiveCoachingPlan.forAthlete(Skill.Blocking,
            profile(Skill.Receiving, "receiving_focus", "platform")).focusCueIds.isEmpty())
        assertTrue(LiveCoachingPlan.forAthlete(Skill.Receiving,
            profile(Skill.Receiving, "unrecognized", "platform")).focusCueIds.isEmpty())
        assertTrue(LiveCoachingPlan.forAthlete(Skill.Receiving,
            profile(Skill.Receiving, "receiving_focus", "not_sure")).focusCueIds.isEmpty())
    }

    @Test fun everySkillsSupportedChoiceMapsToItsOwnObservableCues() {
        val choices = listOf(Skill.Serving to "toss", Skill.Receiving to "platform", Skill.Setting to "hand_position",
            Skill.Spiking to "approach", Skill.Blocking to "hand_position")
        choices.forEach { (skill, option) ->
            val plan = LiveCoachingPlan.forAthlete(skill, profile(skill, TrainingSetupQuestions.forSkill(skill).id, option))
            assertTrue(plan.focusCueIds.isNotEmpty())
            assertTrue(plan.focusCueIds.all { id -> FormCueCatalog.forSkill(skill).any { it.id == id } })
        }
        val variant = LiveCoachingPlan.forAthlete(Skill.Serving, profile(Skill.Serving, "serving_consistency", "toss"))
        assertEquals(setOf(CueId.ServeTossArmLift), variant.focusCueIds)
    }

    @Test fun prioritiesSelectRepeatedVisibleFaultsAndDoNotFabricateHiddenMeasurements() {
        val coach = CoachDirector()
        val priority = setOf(CueId.PassKneeFlexion)
        var result: CoachingEvent? = null
        repeat(3) { index -> result = coach.onRep(assessment(status(100f), status(175f, CueId.PassKneeFlexion)),
            index * 5_000L, CameraAngle.Side, priority) }
        assertTrue(result!!.text.contains("knees"))

        coach.reset()
        repeat(3) { index -> result = coach.onRep(assessment(status(100f), status(null, CueId.PassKneeFlexion)),
            index * 5_000L, CameraAngle.Front, priority) }
        assertTrue(result!!.text.contains("forearms"))
    }

    @Test fun anEmptyProfileStillProvidesACompleteLocalSession() {
        Skill.All.forEach { skill ->
            val plan = LiveCoachingPlan.forAthlete(skill, AthleteProfile.Anonymous)
            assertEquals("Your live coach", plan.title)
            assertEquals(listOf(CoachPhrasebook.opening(skill), FacingGuide.opening(null),
                "I'll watch the first couple before I say anything."), plan.opening(skill))
        }
    }

    @Test fun spokenCountsFollowTheCoachTable() {
        fun counts(target: Int?) = (1..(target ?: 4)).map { CoachPhrasebook.repCount(it, target, it + 20) }
        assertEquals(listOf("One.", "Two.", "Three.", "One to go.", "Five."), counts(5))
        assertEquals(listOf("One.", "Two.", "Three.", "Four.", "Halfway.", "Six.", "Seven.", "Eight.", "One to go.", "Ten."), counts(10))
        assertEquals(listOf("Twenty-one.", "Twenty-two.", "Twenty-three.", "Twenty-four."), counts(null))
        assertEquals("Thirty", CoachPhrasebook.numberWord(30))
        assertEquals("31", CoachPhrasebook.numberWord(31))
    }

    @Test fun reactionsRotateWithoutRepeatingBackToBackOrImplyingImprovement() {
        val good = assessment(status(175f), good = true)
        var previous: LiveRepFeedback? = null
        val spoken = (2..12).map { rep ->
            feedback(good, total = rep, stage = rep, target = null, previous = previous).also { previous = it }
        }
        assertTrue(spoken.zipWithNext().all { (a, b) -> a.ack != b.ack })
        assertTrue(spoken.map { it.ack }.distinct().size >= 5)
        assertTrue(spoken.none { Regex("better|improv|target met", RegexOption.IGNORE_CASE).containsMatchIn(it.spokenText) })
        LiveRepVerdict.entries.forEach { verdict ->
            val first = CoachPhrasebook.repAck(verdict, null, 0)
            assertNotEquals(first, CoachPhrasebook.repAck(verdict, null, 0, first))
        }
    }

    @Test fun theRepAfterADirectorTurnIsAQuietCount() {
        val correction = CoachingEvent(7, CoachingEventType.Correction, "Straighten both elbows.", 10_000, 1)
        val turn = feedback(assessment(status(130f)), total = 3, stage = 3, direction = correction)
        assertTrue(turn.directed)
        val quiet = feedback(assessment(status(130f)), total = 4, stage = 4, previous = turn)
        assertEquals("One to go.", quiet.spokenText)
        assertEquals("Form needs work.", quiet.detail)
        val next = feedback(assessment(status(130f)), total = 5, stage = 5, previous = quiet)
        assertTrue(next.spokenText.startsWith("Five. "))
    }

    @Test fun aMeasuredFaultOnTheAthletesFocusCanBeNamedInTheReaction() {
        val plan = LiveCoachingPlan(focusCueIds = setOf(CueId.PassPlatformStraight))
        val spoken = (2..9).map { feedback(assessment(status(130f)), total = it, stage = it, target = null, plan = plan).spokenText }
        assertTrue(spoken.any { it.endsWith("Stay with your forearm platform.") })
        assertTrue((2..9).none { feedback(assessment(status(130f)), total = it, stage = it, target = null).spokenText.contains("Stay with") })
    }
}
