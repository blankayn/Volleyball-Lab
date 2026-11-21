package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class CoachDirectorTest {
    private fun status(value: Float?, id: CueId = CueId.PassPlatformStraight, side: Side? = null): CueLiveStatus {
        val cue = requireNotNull(FormCueCatalog.byId(id))
        return CueLiveStatus(cue, value, cue.score(value), side = side)
    }

    private class Session {
        val coach = CoachDirector()
        val events = mutableListOf<CoachingEvent>()
        private var timestampMs = -5_000L

        var timestamp: Long get() = timestampMs; private set(value) { timestampMs = value }
        private var stageReps = 0
        private var streak = 0
        private var valid = 0

        fun rep(vararg statuses: CueLiveStatus, angle: CameraAngle = CameraAngle.Front, elapsedMs: Long = 5_000): CoachingEvent? {
            timestampMs += elapsedMs
            val measured = statuses.filter { it.isMeasurable }
            val weight = measured.sumOf { it.cue.weight.toDouble() }.toFloat()
            val assessment = LiveRepAssessment(null, weight, false, false, statuses.toList())
            return coach.onRep(assessment, timestampMs, angle)?.also { events += it }
        }

        /** A rep with the screen's counted context, as the capture view model supplies it. */
        fun counted(vararg statuses: CueLiveStatus, score: Float? = null, assessable: Boolean = true,
            angle: CameraAngle = CameraAngle.Front, elapsedMs: Long = 5_000, focus: Set<CueId> = emptySet()): CoachingEvent? {
            timestampMs += elapsedMs
            val value = score ?: (statuses.mapNotNull { it.score }.average() * 100).toFloat()
            val good = assessable && value >= 80f
            stageReps++; streak = if (good) streak + 1 else 0; if (good) valid++
            val assessment = LiveRepAssessment(value, 1f, assessable, good, statuses.toList())
            return coach.onRep(assessment, timestampMs, angle, focus,
                RepContext(stageReps, 10, 0, streak, streak, valid))?.also { events += it }
        }

        fun nextStage() { coach.beginStage(); stageReps = 0; streak = 0 }
    }

    @Test fun observesTwoRepsThenConfirmsImprovementAcrossBothRetries() {
        val session = Session()
        assertNull(session.rep(status(130f)))
        assertNull(session.rep(status(130f)))
        assertEquals(CoachingEventType.Correction, session.rep(status(130f))?.type)
        assertNull(session.rep(status(175f)))
        val improvement = session.rep(status(175f))
        assertEquals(CoachingEventType.Improvement, improvement?.type)
        assertTrue(improvement!!.text.contains("forearm platform"))
        assertTrue(improvement.text.contains("both attempts"))
    }

    @Test fun singleOutlierDoesNotTriggerCorrection() {
        val session = Session()
        listOf(175f, 175f, 130f, 175f, 175f).forEach { session.rep(status(it)) }
        assertTrue(session.events.isEmpty())
    }

    @Test fun twoMissesInThreeComparableRepsTriggerCorrection() {
        val session = Session()
        session.rep(status(130f))
        session.rep(status(175f))
        assertEquals(CoachingEventType.Correction, session.rep(status(130f))?.type)
    }

    @Test fun lastRepMustStillNeedTheCorrection() {
        val session = Session()
        session.rep(status(130f))
        session.rep(status(130f))
        assertNull(session.rep(status(175f)))
    }

    @Test fun oppositeMissDirectionsDoNotFormARepeatedMistake() {
        val session = Session()
        listOf(80f, 140f, 175f).forEach { session.rep(status(it, CueId.PassKneeFlexion)) }
        assertTrue(session.events.isEmpty())
        assertEquals(CoachingEventType.Correction, session.rep(status(175f, CueId.PassKneeFlexion))?.type)
    }

    @Test fun focusPersistsEvenWhenAnotherCueBecomesWorse() {
        val session = Session()
        repeat(3) { session.rep(status(150f), status(140f, CueId.PassKneeFlexion)) }
        repeat(3) { session.rep(status(150f), status(180f, CueId.PassKneeFlexion)) }
        assertEquals(2, session.events.size)
        assertTrue(session.events.all { it.type == CoachingEventType.Correction && "forearms" in it.text })
        assertTrue(session.events.last().text.startsWith("Slowly extend"))
    }

    @Test fun resolvedFocusAllowsAnotherRepeatedCueAfterAQuietRetry() {
        val session = Session()
        repeat(3) { session.rep(status(130f), status(140f, CueId.PassKneeFlexion)) }
        repeat(2) { session.rep(status(175f), status(180f, CueId.PassKneeFlexion)) }
        assertEquals(CoachingEventType.Improvement, session.events.last().type)
        assertNull(session.rep(status(175f), status(180f, CueId.PassKneeFlexion)))
        val next = session.rep(status(175f), status(180f, CueId.PassKneeFlexion))
        assertEquals(CoachingEventType.Correction, next?.type)
        assertTrue(next!!.text.contains("knees"))
    }

    @Test fun oneGoodAttemptCannotEarnSustainedPraise() {
        val session = Session()
        repeat(3) { session.rep(status(130f)) }
        listOf(175f, 130f, 175f, 130f).forEach { session.rep(status(it)) }
        assertFalse(session.events.any { it.type == CoachingEventType.Improvement })
    }

    @Test fun partialProgressIsAcknowledgedOnceAndKeepsTheFocus() {
        val session = Session()
        repeat(3) { session.rep(status(130f), status(140f, CueId.PassKneeFlexion)) }
        repeat(6) { session.rep(status(155f), status(180f, CueId.PassKneeFlexion)) }
        val praise = session.events.filter { it.type == CoachingEventType.Improvement }
        assertEquals(1, praise.size)
        assertTrue(praise.single().text.contains("closer to the reference"))
        assertFalse(praise.single().text.contains("in range"))
        assertTrue(session.events.last().text.contains("forearms"))
        assertFalse(session.events.any { "knees" in it.text })
    }

    @Test fun smallFluctuationsDoNotEarnProgressPraise() {
        val session = Session()
        repeat(3) { session.rep(status(130f)) }
        repeat(4) { session.rep(status(132f)) }
        assertFalse(session.events.any { it.type == CoachingEventType.Improvement })
    }

    @Test fun aHighScoreOutsideTheActualRangeCannotClaimMatchedReference() {
        val session = Session()
        repeat(3) { session.rep(status(130f)) }
        repeat(2) { session.rep(status(163f).copy(score = 0.96f)) }
        assertTrue(session.events.last().text.contains("closer"))
        assertFalse(session.events.any { "matched" in it.text || "in the reference range" in it.text })
    }

    @Test fun missingEvidenceBreaksThePatternAndCannotCountAsSuccess() {
        val session = Session()
        repeat(2) { session.rep(status(130f)) }
        session.rep(status(null))
        repeat(2) { session.rep(status(130f)) }
        assertFalse(session.events.any { it.type == CoachingEventType.Correction })
        assertEquals(CoachingEventType.Correction, session.rep(status(130f))?.type)
        session.rep(status(175f))
        session.rep(status(null))
        session.rep(status(175f))
        assertFalse(session.events.any { it.type == CoachingEventType.Improvement })
        assertEquals(CoachingEventType.Improvement, session.rep(status(175f))?.type)
    }

    @Test fun unavailableFocusGetsSpecificGuidanceAndEventuallyReleasesOtherCues() {
        val session = Session()
        repeat(3) { session.rep(status(130f), status(140f, CueId.PassKneeFlexion)) }
        repeat(3) { session.rep(status(null), status(180f, CueId.PassKneeFlexion)) }
        val hidden = session.events.last()
        assertEquals(CoachingEventType.RepMilestone, hidden.type)
        assertTrue(hidden.text.contains("forearm platform"))
        assertTrue(hidden.text.contains("Reps are still recording"))
        session.rep(status(null), status(180f, CueId.PassKneeFlexion))
        val next = session.rep(status(null), status(180f, CueId.PassKneeFlexion))
        assertEquals(CoachingEventType.Correction, next?.type)
        assertTrue(next!!.text.contains("knees"))
    }

    @Test fun changingViewsCannotCombineEvidenceOrEarnPraiseFromThePreviousView() {
        val session = Session()
        repeat(2) { session.rep(status(130f)) }
        repeat(2) { assertNull(session.rep(status(130f), angle = CameraAngle.Side)) }
        assertEquals(CoachingEventType.Correction, session.rep(status(130f), angle = CameraAngle.Side)?.type)
        repeat(3) { session.rep(status(175f), angle = CameraAngle.Front) }
        assertFalse(session.events.any { it.type == CoachingEventType.Improvement })
    }

    @Test fun changingArmsOrPhasesCannotCombineEvidence() {
        for (changePhase in listOf(false, true)) {
            val session = Session()
            val left = status(120f, CueId.SpikeArmExtension, Side.Left)
            val changed = if (changePhase) left.copy(cue = left.cue.copy(phase = RepPhase.Loading))
                else left.copy(side = Side.Right)
            repeat(2) { session.rep(left) }
            repeat(2) { assertNull(session.rep(changed)) }
            assertEquals(CoachingEventType.Correction, session.rep(changed)?.type)
            repeat(2) { session.rep(left.copy(measured = 175f, score = 1f)) }
            assertFalse(session.events.any { it.type == CoachingEventType.Improvement })
        }
    }

    @Test fun stageChangesAndTrackingInterruptionsRequireFreshEvidence() {
        for (beginStage in listOf(false, true)) {
            val session = Session()
            repeat(3) { session.rep(status(130f)) }
            if (beginStage) session.coach.beginStage() else session.coach.interruptObservation()
            repeat(2) { assertNull(session.rep(status(130f), elapsedMs = 10_000)) }
            assertEquals(CoachingEventType.Correction, session.rep(status(130f))?.type)
            assertEquals(2, session.events.size)
            assertNotEquals(session.events.first().text, session.events.last().text)
        }
    }

    @Test fun unknownViewsAndInvalidScoresNeverProduceFormAdvice() {
        val invalid = listOf(status(null), status(Float.NaN), status(Float.POSITIVE_INFINITY),
            status(130f).copy(score = Float.NaN), status(130f).copy(score = 1.1f),
            status(130f).copy(score = -0.1f))
        for (value in invalid) {
            val session = Session()
            repeat(6) { session.rep(value) }
            assertTrue(session.events.all { it.type == CoachingEventType.RepMilestone })
        }
        val session = Session()
        repeat(6) { session.rep(status(130f), angle = CameraAngle.Unknown) }
        assertTrue(session.events.all { it.type == CoachingEventType.RepMilestone })
    }

    @Test fun repeatedCorrectionsTeachDifferentActionsWithoutNagging() {
        val session = Session()
        repeat(30) { session.rep(status(130f)) }
        // The whole ladder is taught once, in order, before any sentence returns.
        val ladder = CoachPhrasebook.instructions(CueId.PassPlatformStraight, 130f)
        assertEquals(5, ladder.size)
        assertEquals(ladder, session.events.take(5).map { it.text })
        assertTrue(session.events.size > 5)
        assertTrue(session.events.all { it.text in ladder })
        assertTrue(session.events.zipWithNext().all { (a, b) -> b.timestampMs - a.timestampMs >= 15_000 })
        // A sentence returns only after it has rested for eight reps and forty-five seconds.
        session.events.groupBy { it.text }.values.forEach { same ->
            assertTrue(same.zipWithNext().all { (a, b) -> b.timestampMs - a.timestampMs >= 45_000 })
        }
        assertTrue((0..20).all { start ->
            session.events.count { (it.timestampMs / 5_000).toInt() in start until start + 10 } <= 4
        })
    }

    @Test fun overshootingUsesTheNewDirectionAfterRepeatedEvidence() {
        val session = Session()
        repeat(3) { session.rep(status(80f, CueId.PassKneeFlexion)) }
        assertEquals("You're sitting a bit deep. Rise slightly.", session.events.single().text)
        repeat(3) { session.rep(status(180f, CueId.PassKneeFlexion)) }
        assertEquals("Your knees are staying a bit straight. Bend a little more.", session.events.last().text)
        assertEquals(2, session.events.size)
    }

    @Test fun aStubbornFocusDoesNotBlockANewFocusInTheNextStage() {
        val session = Session()
        repeat(9) { session.rep(status(130f)) }
        assertEquals(3, session.events.size)
        session.coach.beginStage()
        repeat(3) { session.rep(status(175f), status(180f, CueId.PassKneeFlexion)) }
        assertEquals(4, session.events.size)
        assertTrue(session.events.last().text.contains("knees"))
    }

    @Test fun aSentenceCanReturnAfterItsCooldownButNotBefore() {
        val session = Session()
        repeat(3) { session.rep(status(130f)) }
        val first = session.events.single().text
        // A tracking gap restarts the ladder at the direct cue, which is still resting.
        session.coach.interruptObservation()
        repeat(3) { session.rep(status(130f)) }
        assertNotEquals(first, session.events.last().text)
        session.coach.interruptObservation()
        repeat(3) { session.rep(status(130f)) }
        assertEquals(3, session.events.map { it.text }.distinct().size)
        session.coach.interruptObservation()
        repeat(2) { session.rep(status(130f)) }
        session.rep(status(130f), elapsedMs = 60_000)
        assertEquals(4, session.events.size)
        assertEquals(first, session.events.last().text)
    }

    @Test fun rungZeroNamesTheRightDirectionAndSideForEveryCue() {
        val directional = mapOf(
            CueId.PassKneeFlexion to ("Rise" to "Bend"), CueId.SetKneeFlexion to ("Rise" to "Bend"),
            CueId.SpikeApproachLoad to ("Rise" to "Bend"), CueId.BlockKneeLoad to ("Rise" to "Bend"),
            CueId.PassTrunkAngle to ("Lean slightly forward" to "upright"), CueId.ServeTrunkLean to ("Lean slightly forward" to "upright"),
            CueId.SpikeTrunkDrive to ("Lean slightly forward" to "upright"), CueId.SetElbowFlexion to ("Open" to "Bend"),
            CueId.SetHandsAboveForehead to ("above your forehead" to "closer"), CueId.BlockHandSeparation to ("apart" to "closer"),
            CueId.ServeTossArmLift to ("higher" to "Lower"), CueId.BlockPenetration to ("farther forward" to "back"),
        )
        val sided = setOf(CueId.ServeContactArmExtension, CueId.ServeReachHeight, CueId.SpikeArmExtension,
            CueId.SpikeElbowHeight, CueId.ServeTossArmLift)
        Skill.All.flatMap { FormCueCatalog.forSkill(it) }.forEach { cue ->
            val below = CoachPhrasebook.instructions(cue.id, cue.target.start - 1).first()
            val above = CoachPhrasebook.instructions(cue.id, cue.target.endInclusive + 1).first()
            assertEquals("Observation then action for ${cue.id}", 2, below.count { it == '.' })
            directional[cue.id]?.let { (low, high) ->
                assertTrue("$below", below.contains(low)); assertTrue("$above", above.contains(high))
                assertNotEquals(below, above)
            }
            Side.entries.forEach { side ->
                val text = CoachPhrasebook.instructions(cue.id, cue.target.start - 1, side).first()
                assertEquals("${cue.id}", cue.id in sided, text.contains("Your ${side.name.lowercase()} "))
            }
            // A new athlete starts at the slow rehearsal; nothing else about the ladder changes.
            val fresh = CoachPhrasebook.instructions(cue.id, cue.target.start - 1, experience = ExperienceLevel.New)
            val usual = CoachPhrasebook.instructions(cue.id, cue.target.start - 1)
            assertEquals(usual[1], fresh[0]); assertEquals(usual.toSet(), fresh.toSet())
        }
    }

    @Test fun aSideResolvedCorrectionSpeaksTheMeasuredArm() {
        val session = Session()
        repeat(3) { session.rep(status(120f, CueId.SpikeArmExtension, Side.Left)) }
        assertTrue(session.events.single().text.startsWith("Your left arm"))
    }

    @Test fun improvementAndMissingEvidenceWordingKeepsItsGroundingWords() {
        Skill.All.flatMap { FormCueCatalog.forSkill(it) }.forEach { cue ->
            val inRange = CoachPhrasebook.improvement(cue.id, true)
            val closer = CoachPhrasebook.improvement(cue.id, false)
            assertEquals(5, inRange.distinct().size); assertEquals(5, closer.distinct().size)
            assertTrue(inRange.first().contains("both attempts"))
            assertTrue(closer.all { it.contains("closer", true) && !it.contains("matched") && !it.contains("in range") })
            assertTrue((inRange + closer).none { it.contains("better", true) || it.contains("improv", true) })
            CameraAngle.entries.forEach { angle ->
                assertTrue(CoachPhrasebook.unavailable(cue.id, angle).first().contains("Reps are still recording"))
            }
        }
        assertTrue(CoachPhrasebook.unavailable(CueId.PassKneeFlexion, CameraAngle.Front)[1].startsWith("I'm losing your legs"))
        assertTrue(CoachPhrasebook.unavailable(CueId.BlockArmExtension, CameraAngle.Front)[1].contains("top of the frame"))
    }

    // Session-arc turns. Each needs counted context and never replaces a form turn.

    @Test fun anEarnedStreakNamesOnlyACueThatHeldOnEveryRep() {
        val session = Session()
        repeat(3) { session.counted(status(175f), status(140f, CueId.PassKneeFlexion)) }
        val streak = session.events.last()
        assertEquals(CoachingEventType.RepMilestone, streak.type)
        assertTrue(streak.text.startsWith("Three in a row."))
        assertTrue(streak.text.contains("is holding"))

        val loose = Session()
        // In the practice band but outside the target on one rep: counted, never named.
        loose.counted(status(175f)); loose.counted(status(163f).copy(score = 0.9f)); loose.counted(status(175f))
        assertEquals("Three in a row in range.", loose.events.last().text)
    }

    @Test fun aStreakNeverFiresOnPartialUnratedOrUnknownViewReps() {
        val partial = Session()
        repeat(5) { partial.counted(status(175f), assessable = false) }
        val unrated = Session()
        repeat(5) { unrated.counted(status(null), score = 100f) }
        val unknown = Session()
        repeat(5) { unknown.counted(status(175f), angle = CameraAngle.Unknown) }
        val jump = Session()
        repeat(5) { jump.counted(status(0.5f, CueId.SpikeJumpHeight), score = 100f) }
        listOf(partial, unrated, unknown, jump).forEach { session ->
            assertTrue(session.events.none { "in a row" in it.text })
        }
    }

    @Test fun aStreakCannotBridgeATrackingGapOrAnOpenFocus() {
        val session = Session()
        repeat(2) { session.counted(status(175f)) }
        session.coach.interruptObservation()
        session.counted(status(175f))
        assertTrue(session.events.none { "in a row" in it.text })

        val focused = Session()
        repeat(3) { focused.counted(status(130f), status(140f, CueId.PassKneeFlexion), score = 85f) }
        assertEquals(CoachingEventType.Correction, focused.events.last().type)
        assertTrue(focused.events.none { "in a row" in it.text })
    }

    @Test fun regroupNeedsAMeasuredDropAndFiresOncePerStage() {
        val steady = Session()
        repeat(9) { steady.counted(status(175f), score = 70f) }
        assertTrue(steady.events.none { "reset, then go" in it.text })

        val session = Session()
        repeat(3) { session.counted(status(175f), score = 90f) }
        repeat(9) { session.counted(status(175f), score = 70f) }
        val regroups = session.events.filter { "reset, then go" in it.text }
        assertEquals(1, regroups.size)
        assertEquals(CoachingEventType.RepMilestone, regroups.single().type)
        assertFalse(regroups.single().text.contains("in range"))
    }

    @Test fun aConfirmedCueThatSlipsIsNamedBeforeItBecomesACorrection() {
        val session = Session()
        // Quick reps: the slip is noticed before another correction for this cue is due.
        repeat(3) { session.counted(status(130f), elapsedMs = 2_000) }
        repeat(2) { session.counted(status(175f), elapsedMs = 2_000) }
        assertEquals(CoachingEventType.Improvement, session.events.last().type)
        session.counted(status(175f), elapsedMs = 2_000)
        repeat(2) { session.counted(status(130f), score = 50f, elapsedMs = 2_000) }
        val regroup = session.events.last()
        assertEquals(CoachingEventType.RepMilestone, regroup.type)
        assertTrue(regroup.text.startsWith("Your forearm platform was in range earlier"))
    }

    @Test fun theWatchWindowMakesNoClaimAndKeepsTheFirstCorrectionOnRepThree() {
        val session = Session()
        val focus = setOf(CueId.PassPlatformStraight)
        assertEquals("I'm watching your forearm platform.", session.counted(status(175f), focus = focus)?.text)
        assertNull(session.counted(status(175f), focus = focus))

        val faulty = Session()
        repeat(2) { assertNull(faulty.counted(status(130f))) }
        assertEquals(CoachingEventType.Correction, faulty.counted(status(130f))?.type)
    }

    @Test fun aSpentCorrectionBudgetIsSaidOnceInsteadOfSilence() {
        val session = Session()
        // Slow reps: every second rep is due, so four corrections land inside ten reps.
        repeat(14) { session.counted(status(130f), elapsedMs = 8_000) }
        assertEquals(1, session.events.count { it.text == CoachPhrasebook.BUDGET_SPENT })
        (1..14).forEach { start ->
            assertTrue(session.events.count { it.type == CoachingEventType.Correction &&
                (it.timestampMs / 8_000).toInt() in start until start + 10 } <= 4)
        }
    }

    @Test fun aCarriedFocusNeedsThreeFreshRepsAndThenSaysItIsTheSameThing() {
        val session = Session()
        repeat(3) { session.counted(status(130f)) }
        val first = session.events.single()
        session.coach.endStage(CameraAngle.Front, 3, 0, 0)
        session.nextStage()
        repeat(2) { assertNull(session.counted(status(130f), angle = CameraAngle.Side, elapsedMs = 10_000)) }
        val carried = session.counted(status(130f), angle = CameraAngle.Side)!!
        assertEquals(CoachingEventType.Correction, carried.type)
        assertTrue(carried.text.startsWith("Same thing from this side: Slowly extend"))
        assertNotEquals(first.text, carried.text)
        assertEquals(carried.text, session.coach.lastCorrection())

        val resolved = Session()
        repeat(3) { resolved.counted(status(130f)) }
        repeat(2) { resolved.counted(status(175f)) }
        resolved.nextStage()
        repeat(3) { resolved.counted(status(130f), angle = CameraAngle.Side, elapsedMs = 20_000) }
        assertFalse(resolved.events.last().text.startsWith("Same thing"))
    }

    @Test fun stageNotesSurviveTheViewChangeAndRecordOnlyEarnedFacts() {
        val session = Session()
        repeat(3) { session.counted(status(130f), status(140f, CueId.PassKneeFlexion)) }
        repeat(2) { session.counted(status(175f), status(140f, CueId.PassKneeFlexion)) }
        session.coach.endStage(CameraAngle.Front, 5, 2, 2)
        session.nextStage()
        repeat(3) { session.counted(status(175f), status(180f, CueId.PassKneeFlexion), angle = CameraAngle.Side) }
        session.coach.interruptObservation()
        session.coach.endStage(CameraAngle.Side, 3, 0, 0)
        val notes = session.coach.stageNotes()
        assertEquals(listOf(CameraAngle.Front, CameraAngle.Side), notes.map { it.angle })
        assertEquals(setOf(CueId.PassPlatformStraight), notes[0].resolvedCues)
        assertNull(notes[0].openCue)
        assertEquals(CueId.PassKneeFlexion, notes[1].openCue)
        assertTrue(notes[1].resolvedCues.isEmpty())
        session.coach.reset()
        assertTrue(session.coach.stageNotes().isEmpty())
    }

    @Test fun aDroppedCorrectionIsOfferedAgain() {
        val session = Session()
        repeat(3) { session.rep(status(130f)) }
        val dropped = session.events.single()
        session.coach.onSpeechDropped(dropped.id)
        session.rep(status(130f))
        val again = session.rep(status(130f))
        assertEquals(dropped.text, again?.text)
        // A heard correction is not repeated on the same schedule.
        val heard = Session()
        repeat(6) { heard.rep(status(130f)) }
        assertEquals(2, heard.events.map { it.text }.distinct().size)
    }

    @Test fun trackingRecoveryIsAcknowledgedOnceAndOnlySoonAfterAPrompt() {
        val coach = CoachDirector()
        assertNull(coach.onTrackingRestored(1_000))
        coach.onTracking(5_000)
        assertEquals(CoachPhrasebook.TRACKING_RESTORED, coach.onTrackingRestored(8_000)?.text)
        assertNull(coach.onTrackingRestored(9_000))
        coach.onTracking(20_000)
        assertNull(coach.onTrackingRestored(45_000))
    }

    @Test fun trackingPromptMatchesWrongViewAndRemainsPaced() {
        val coach = CoachDirector()
        assertTrue(coach.onTracking(0, CameraAngle.Side)!!.text.startsWith("Turn sideways"))
        assertNull(coach.onTracking(1_000, CameraAngle.Side))
        assertTrue(coach.onTracking(10_000)!!.text.contains("whole body"))
    }

    @Test fun resetStartsANewSessionWithoutReusingEventIds() {
        val session = Session()
        repeat(3) { session.rep(status(130f)) }
        val previous = session.events.single()
        session.coach.reset()
        repeat(3) { session.rep(status(130f)) }
        assertEquals(previous.text, session.events.last().text)
        assertTrue(session.events.last().id > previous.id)
    }

    @Test fun phrasebookCoversBothDirectionsWithoutEquipmentOrSuccessClaims() {
        val forbidden = Regex("\\b(ball|wall|toss|feed|rebound|always|perfect|video)\\b", RegexOption.IGNORE_CASE)
        Skill.All.forEach { skill ->
            assertTrue(CoachPhrasebook.opening(skill).split(" ").size in 4..10)
            FormCueCatalog.forSkill(skill).forEach { cue ->
                listOf(cue.target.start - 1, cue.target.endInclusive + 1).forEach { value ->
                    val instructions = CoachPhrasebook.instructions(cue.id, value)
                    assertEquals(5, instructions.distinct().size)
                    assertTrue(instructions.all { it.length <= 110 })
                    assertTrue(instructions.none { text -> text.any(Char::isDigit) || forbidden.containsMatchIn(text) })
                }
            }
        }
    }
}
