package com.volleyballlab.player.feature.train.capture

import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.repository.SessionRepository
import com.volleyballlab.player.pose.PoseDetector
import com.volleyballlab.player.pose.analysis.GuidedPoseFixtures
import com.volleyballlab.player.pose.analysis.SessionScorer
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame
import com.volleyballlab.player.pose.review.CapturedReviewInput
import com.volleyballlab.player.pose.review.CapturedReviewWriter
import com.volleyballlab.player.data.ai.LiveCoachingRepository
import com.volleyballlab.player.domain.repository.CoachingPreferences
import com.volleyballlab.player.domain.repository.CoachingPreferencesRepository
import com.volleyballlab.player.pose.analysis.LiveCoachingAdvice
import com.volleyballlab.player.pose.analysis.LiveCoachingEvidence
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuidedCaptureIntegrationTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `every completed rep gets spoken count and measured feedback from the first rep`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            val events = mutableListOf<com.volleyballlab.player.pose.analysis.CoachingEvent>()
            val speech = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.coachingEvents.collect { events += it }
            }
            try {
                feed.stand(180); feed.stand(35)
                assertTrue(events.none { it.repNumber != null })
                repeat(5) { index ->
                    feed.rep()
                    val feedback = requireNotNull(vm.repFeedback.value)
                    assertEquals(index + 1, feedback.totalReps)
                    assertTrue(feedback.spokenText.startsWith(listOf("One.", "Two.", "Three.", "One to go.", "Five.")[index]))
                    assertEquals("Rep ${index + 1} of 5", feedback.countText)
                    assertFalse(feedback.spokenText.contains("target met"))
                    if (index < 4) assertEquals(feedback.spokenText, events.last { it.repNumber != null }.text)
                }
                assertEquals("The target count belongs only to the angle turn", (1..4).toList(), events.mapNotNull { it.repNumber })
                val turn = (vm.uiState.value as CaptureUiState.Recording).rotation!!
                assertTrue("The final count survives the angle transition", turn.message.startsWith("Five."))
                feed.stand(60, CameraAngle.Side)
                assertNull(vm.repFeedback.value)
                feed.stand(35, CameraAngle.Side, occluded = true)
                feed.rep(CameraAngle.Side, occluded = true)
                assertEquals(6, vm.repFeedback.value?.totalReps)
                assertEquals("Rep 1 of 5", vm.repFeedback.value?.countText)
                assertNotEquals(com.volleyballlab.player.pose.analysis.LiveRepVerdict.Good, vm.repFeedback.value?.verdict)
            } finally { speech.cancel() }
        }
    }

    @Test fun `a movement that is not the skill earns nothing and is explained`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            val events = mutableListOf<com.volleyballlab.player.pose.analysis.CoachingEvent>()
            val speech = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.coachingEvents.collect { events += it }
            }
            try {
                feed.stand(180); feed.stand(35)
                events.clear()
                val reason = com.volleyballlab.player.pose.analysis.CoachPhrasebook.notCounted(
                    com.volleyballlab.player.pose.analysis.MovementFault.UseBothHands)
                // One hand goes up: a real movement, but not a set.
                repeat(2) { feed.rep(hands = setOf(BodyPoint.LEFT_WRIST)) }
                val refused = vm.uiState.value as CaptureUiState.Recording
                assertEquals(0, refused.totalMetrics.reps)
                assertEquals(0, refused.totalMetrics.validReps)
                assertEquals(0, refused.totalMetrics.bestStreak)
                assertNull(refused.totalMetrics.estimatedFormScore)
                assertNull("No rep verdict for a movement that was not a rep", vm.repFeedback.value)
                assertEquals(com.volleyballlab.player.pose.analysis.CoachingEventType.NotCounted, vm.coachingMessage.value?.type)
                assertEquals(reason, vm.coachingMessage.value?.text)
                assertNull(vm.coachingMessage.value?.repNumber)
                assertEquals("The same reason is shown each time but spoken once", 1, events.count { it.text == reason })
                assertTrue(events.none { it.repNumber != null })

                // The correct movement straight afterwards is counted as the first rep.
                feed.rep()
                assertEquals(1, (vm.uiState.value as CaptureUiState.Recording).totalMetrics.reps)
                assertEquals("Rep 1 of 5", vm.repFeedback.value?.countText)
            } finally { speech.cancel() }
        }
    }

    @Test fun `a repeated reason is taught in full first, then spoken as a short cue with the full one on the card`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            val events = mutableListOf<com.volleyballlab.player.pose.analysis.CoachingEvent>()
            val speech = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.coachingEvents.collect { events += it }
            }
            try {
                vm.setVoiceGuidanceEnabled(true)
                feed.stand(180); feed.stand(35)
                events.clear()
                val cue = com.volleyballlab.player.pose.analysis.CoachPhrasebook.notCountedCue(
                    com.volleyballlab.player.pose.analysis.MovementFault.UseBothHands)
                feed.rep(hands = setOf(BodyPoint.LEFT_WRIST))
                feed.stand(110)
                feed.rep(hands = setOf(BodyPoint.LEFT_WRIST))
                val spoken = events.filter { it.type == com.volleyballlab.player.pose.analysis.CoachingEventType.NotCounted }
                assertEquals(listOf(cue.full, cue.short), spoken.map { it.speech })
                assertTrue("The card keeps the full sentence", spoken.all { it.text == cue.full })
                assertEquals(cue.full, vm.coachingMessage.value?.text)
            } finally { speech.cancel() }
        }
    }

    @Test fun `with voice off nothing is shortened, because nothing was heard`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            val events = mutableListOf<com.volleyballlab.player.pose.analysis.CoachingEvent>()
            val speech = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.coachingEvents.collect { events += it }
            }
            try {
                feed.stand(180); feed.stand(35)
                feed.rep(hands = setOf(BodyPoint.LEFT_WRIST))
                feed.stand(110)
                feed.rep(hands = setOf(BodyPoint.LEFT_WRIST))
                val spoken = events.filter { it.type == com.volleyballlab.player.pose.analysis.CoachingEventType.NotCounted }
                assertEquals(2, spoken.size)
                assertTrue(spoken.all { it.speech == it.text })
            } finally { speech.cancel() }
        }
    }

    @Test fun `standing still through a view counts nothing and is told what a rep is`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            val events = mutableListOf<com.volleyballlab.player.pose.analysis.CoachingEvent>()
            val speech = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.coachingEvents.collect { events += it }
            }
            try {
                feed.stand(180); feed.stand(35)
                events.clear()
                feed.stand(220)
                val idle = vm.uiState.value as CaptureUiState.Recording
                assertEquals(0, idle.totalMetrics.reps)
                assertNull(vm.repFeedback.value)
                assertEquals(listOf(com.volleyballlab.player.pose.analysis.CoachPhrasebook.idle(Skill.Setting)), events.map { it.text })
            } finally { speech.cancel() }
        }
    }

    @Test fun `the side turn says which way to face and a two-handed skill accepts either profile`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            feed.stand(180)
            val front = vm.uiState.value as CaptureUiState.Recording
            assertEquals(com.volleyballlab.player.pose.analysis.Facing.Forward, front.displayedProgress(vm.guidedOptions.value).facing)
            feed.stand(35); repeat(5) { feed.rep() }
            val turn = vm.uiState.value as CaptureUiState.Recording
            assertEquals("Turn to your left, so your right shoulder points at the camera.", turn.rotation!!.instruction)
            assertEquals(com.volleyballlab.player.pose.analysis.Facing.Left, turn.displayedProgress(vm.guidedOptions.value).facing)
            // This fixture stands the other way round. A set reads the same from either side.
            feed.stand(60, CameraAngle.Side)
            val side = vm.uiState.value as CaptureUiState.Recording
            assertNull(side.rotation)
            assertEquals(CameraAngle.Side, side.angle)
        }
    }

    @Test fun `a serve must clear the head and its side view needs the hitting arm nearest the camera`() = runTest(dispatcher) {
        withCapture(skill = Skill.Serving) { vm, feed ->
            feed.stand(180); feed.stand(35)
            // Hands raised to the face count as a set, but not as a serve.
            feed.rep()
            assertEquals(0, (vm.uiState.value as CaptureUiState.Recording).totalMetrics.reps)
            assertEquals(com.volleyballlab.player.pose.analysis.CoachPhrasebook.notCounted(
                com.volleyballlab.player.pose.analysis.MovementFault.ReachOverhead), vm.coachingMessage.value?.text)
            // Both hands going up together is a set or a stretch, however high it goes.
            feed.rep(peak = .42f)
            assertEquals(0, (vm.uiState.value as CaptureUiState.Recording).totalMetrics.reps)
            repeat(5) { feed.serve() }
            val turn = vm.uiState.value as CaptureUiState.Recording
            assertEquals(5, turn.totalMetrics.reps)
            assertTrue(turn.rotation!!.instruction.startsWith("Turn to your left, so your right shoulder points at the camera."))
            // Side-on, but with the hitting arm on the far side: the view does not begin.
            feed.stand(60, CameraAngle.Side)
            val waiting = vm.uiState.value as CaptureUiState.Recording
            assertEquals(CameraAngle.Side, waiting.rotation?.requestedAngle)
            assertEquals(CameraAngle.Front, waiting.angle)
        }
    }

    @Test fun `the reference figure shows a rep before recording, the finish after a refusal, and nothing mid-movement`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            val framing = mutableListOf<Any?>()
            val countdown = mutableSetOf<com.volleyballlab.player.pose.analysis.ReferenceMoment>()
            feed.afterFrame = {
                val figure = vm.referenceFigure.value
                when (vm.uiState.value) {
                    is CaptureUiState.Framing -> framing += figure
                    is CaptureUiState.CountingDown -> figure?.let { countdown += it.moment }
                    else -> Unit
                }
            }
            feed.stand(180)
            feed.afterFrame = null
            assertTrue("Setup is left uncluttered", framing.isNotEmpty() && framing.all { it == null })
            assertEquals("Both positions are shown before the first rep",
                com.volleyballlab.player.pose.analysis.ReferenceMoment.entries.toSet(), countdown)

            feed.stand(35)
            assertNull("Ready and waiting: there is nothing to point at", vm.referenceFigure.value)

            // One hand goes up. Not counted, and the finish position goes up with the reason.
            feed.rep(hands = setOf(BodyPoint.LEFT_WRIST))
            val shown = requireNotNull(vm.referenceFigure.value)
            assertEquals(com.volleyballlab.player.pose.analysis.ReferenceMoment.Finish, shown.moment)
            assertEquals(com.volleyballlab.player.pose.analysis.Facing.Forward, shown.facing)
            // It stands on the athlete's own feet, at their size, inside the picture.
            assertEquals(0.80f, shown.points.getValue(BodyPoint.LEFT_ANKLE).y, 0.01f)
            assertEquals(0.24f, shown.points.getValue(BodyPoint.LEFT_SHOULDER).y, 0.03f)
            assertEquals(0.50f, (shown.points.getValue(BodyPoint.LEFT_HIP).x + shown.points.getValue(BodyPoint.RIGHT_HIP).x) / 2f, 0.01f)
            assertTrue(shown.points.values.all { it.x in 0f..1f && it.y in 0f..1f })

            // The next attempt: the figure is there as they set, and gone once they move.
            val during = mutableListOf<Any?>()
            feed.afterFrame = { during += vm.referenceFigure.value }
            feed.rep()
            feed.afterFrame = null
            assertNotNull(during.first())
            assertTrue("No target is drawn while a movement is under way", during.drop(3).all { it == null })
            assertEquals(1, (vm.uiState.value as CaptureUiState.Recording).totalMetrics.reps)
        }
    }

    @Test fun `a turn shows the stance to turn into and then follows the profile the athlete took`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            feed.stand(180); feed.stand(35); repeat(5) { feed.rep() }
            // Asked to turn, still facing the camera: the figure is already side-on.
            feed.stand(2)
            val turnInto = requireNotNull(vm.referenceFigure.value)
            assertEquals(com.volleyballlab.player.pose.analysis.ReferenceMoment.Start, turnInto.moment)
            assertEquals(com.volleyballlab.player.pose.analysis.Facing.Left, turnInto.facing)

            // This fixture turns the other way, which a set accepts. The figure turns with them
            // rather than standing back to front, and shows the rep from this side before it begins.
            val turning = mutableSetOf<Pair<com.volleyballlab.player.pose.analysis.Facing, com.volleyballlab.player.pose.analysis.ReferenceMoment>>()
            feed.afterFrame = {
                if ((vm.uiState.value as? CaptureUiState.Recording)?.rotation != null) {
                    vm.referenceFigure.value?.let { turning += it.facing to it.moment }
                }
            }
            feed.stand(60, CameraAngle.Side)
            feed.afterFrame = null
            assertEquals(setOf(com.volleyballlab.player.pose.analysis.Facing.Right), turning.map { it.first }.toSet())
            assertEquals(com.volleyballlab.player.pose.analysis.ReferenceMoment.entries.toSet(), turning.map { it.second }.toSet())
            assertNull((vm.uiState.value as CaptureUiState.Recording).rotation)
            assertNull("The view has begun; the figure steps aside", vm.referenceFigure.value)
        }
    }

    @Test fun `standing the wrong way or waiting too long brings the figure back`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            feed.stand(180); feed.stand(35)
            // Side-on during the front view: the figure faces forward, where they should be.
            feed.stand(20, CameraAngle.Side)
            val wrongWay = requireNotNull(vm.referenceFigure.value)
            assertEquals(com.volleyballlab.player.pose.analysis.Facing.Forward, wrongWay.facing)
            assertEquals(com.volleyballlab.player.pose.analysis.ReferenceMoment.Start, wrongWay.moment)

            // Back the right way and idle: after the wait, the rep is demonstrated again.
            feed.stand(40)
            assertNull(vm.referenceFigure.value)
            val idle = mutableSetOf<com.volleyballlab.player.pose.analysis.ReferenceMoment>()
            feed.afterFrame = { vm.referenceFigure.value?.let { idle += it.moment } }
            feed.stand(220)
            feed.afterFrame = null
            assertEquals(com.volleyballlab.player.pose.analysis.ReferenceMoment.entries.toSet(), idle)
            assertEquals(0, (vm.uiState.value as CaptureUiState.Recording).totalMetrics.reps)
        }
    }

    @Test fun `no reference figure is drawn with Pose Guide switched off`() = runTest(dispatcher) {
        withCapture(poseGuide = false) { vm, feed ->
            val seen = mutableListOf<Any?>()
            feed.afterFrame = { seen += vm.referenceFigure.value }
            feed.stand(180); feed.stand(35)
            feed.rep(hands = setOf(BodyPoint.LEFT_WRIST))
            repeat(5) { feed.rep() }
            feed.stand(20)
            assertTrue(seen.isNotEmpty() && seen.all { it == null })
            // The words are unaffected: the refusal was still explained and the reps still counted.
            assertEquals(5, (vm.uiState.value as CaptureUiState.Recording).totalMetrics.reps)
        }
    }

    @Test fun `capture uses the saved athlete focus and reserves the name for the personalized opening`() = runTest(dispatcher) {
        val profile = com.volleyballlab.player.domain.model.AthleteProfile(firstName = "Maya", name = "Maya Santos",
            trainingSetup = com.volleyballlab.player.domain.model.SavedTrainingSetup(plan =
                com.volleyballlab.player.domain.model.TrainingSetup(Skill.Setting, 3, "Practice setting",
                    com.volleyballlab.player.domain.model.ExperienceLevel.New, "", "Practice", "local",
                    "set-wall-reps", 0, questionId = "setting_focus", optionId = "hand_position")))
        withCapture(profile = profile) { vm, feed ->
            assertEquals("Maya", vm.coachingPlan.value.athleteName)
            assertTrue(vm.coachingPlan.value.focusCueIds.contains(com.volleyballlab.player.domain.model.CueId.SetHandsAboveForehead))
            feed.stand(180); feed.stand(35); feed.rep()
            assertFalse(vm.repFeedback.value!!.spokenText.contains("Maya"))
            assertEquals("Maya’s live coach", vm.coachingPlan.value.title)
        }
    }

    @Test fun `tracking gaps clear the last verdict and cannot speak phantom counts`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            feed.stand(180); feed.stand(35); feed.rep()
            assertNotNull(vm.repFeedback.value)
            feed.hideFeet = true
            feed.stand(20); feed.rep()
            assertNull(vm.repFeedback.value)
            assertFalse(vm.speechWindowOpen.value)
            assertEquals(1, (vm.uiState.value as CaptureUiState.Recording).totalMetrics.reps)
        }
    }

    @Test fun `final rep waits for its voice turn then finishes without counting more movement`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            feed.stand(180); feed.stand(35); repeat(5) { feed.rep() }
            feed.stand(60, CameraAngle.Side); feed.stand(35, CameraAngle.Side)
            vm.setVoiceGuidanceEnabled(true)
            repeat(5) { feed.rep(CameraAngle.Side) }
            val finishing = vm.uiState.value as CaptureUiState.Recording
            assertTrue(finishing.finishingAfterRep)
            assertEquals(10, finishing.totalMetrics.reps)
            assertEquals("Rep 5 of 5", vm.repFeedback.value?.countText)
            feed.rep(CameraAngle.Side)
            assertEquals(10, (vm.uiState.value as CaptureUiState.Recording).totalMetrics.reps)
            vm.onFinalRepSpeechFinished("old-session")
            assertTrue(vm.uiState.value is CaptureUiState.Recording)
            vm.onFinalRepSpeechFinished(finishing.sessionId)
            assertEquals(CaptureUiState.Analysing, vm.uiState.value)
        }
    }

    @Test fun `a missing final speech callback has a bounded finish`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            feed.stand(180); feed.stand(35); repeat(5) { feed.rep() }
            feed.stand(60, CameraAngle.Side); feed.stand(35, CameraAngle.Side)
            vm.setVoiceGuidanceEnabled(true)
            repeat(5) { feed.rep(CameraAngle.Side) }
            val finishing = vm.uiState.value as CaptureUiState.Recording
            assertTrue(finishing.finishingAfterRep)
            // The bound follows the closing's own length, so it is never cut short.
            val bound = com.volleyballlab.player.ui.coaching.SpeechTiming.turnMs(finishing.closing) + 2_000
            // The fallback started on the target rep, a few frames before the feed stopped.
            advanceTimeBy(bound - 2_000)
            runCurrent()
            assertTrue(vm.uiState.value is CaptureUiState.Recording)
            advanceTimeBy(2_100)
            runCurrent()
            assertEquals(CaptureUiState.Analysing, vm.uiState.value)
        }
    }

    @Test fun `guided finish speaks the final rep then a closing built from counted facts`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            feed.stand(180); feed.stand(35); repeat(5) { feed.rep() }
            feed.stand(60, CameraAngle.Side); feed.stand(35, CameraAngle.Side)
            repeat(5) { feed.rep(CameraAngle.Side) }
            val finishing = vm.uiState.value as CaptureUiState.Recording
            assertTrue(finishing.closing.first().startsWith("Five."))
            assertEquals("That's ten reps.", finishing.closing[1])
            assertEquals("Let's look at your review.", finishing.closing.last())
            assertTrue(finishing.closing.none { "came good" in it })
            assertNull(finishing.rotation)
        }
    }

    @Test fun `finish, the side view ending the session and confirmed early end all close before saving`() = runTest(dispatcher) {
        // Finish in free practice.
        withCapture { vm, feed ->
            vm.setGuidedAngles(false)
            feed.stand(180); feed.stand(35); repeat(2) { feed.rep() }
            vm.onStopRequested()
            val finishing = vm.uiState.value as CaptureUiState.Recording
            assertTrue(finishing.finishingAfterRep)
            assertEquals("That's two reps.", finishing.closing.first())
            feed.rep()
            assertEquals(2, (vm.uiState.value as CaptureUiState.Recording).totalMetrics.reps)
            vm.onFinalRepSpeechFinished("another-session")
            assertTrue(vm.uiState.value is CaptureUiState.Recording)
        }
        // The side view is the last one: no rear view is requested, and it cannot be skipped.
        withCapture { vm, feed ->
            feed.stand(180); feed.stand(35); repeat(5) { feed.rep() }
            assertEquals(CameraAngle.Side, (vm.uiState.value as CaptureUiState.Recording).rotation?.requestedAngle)
            vm.onSkipAngle()
            assertEquals("Side view is required for this review.",
                (vm.uiState.value as CaptureUiState.Recording).rotation?.blockedReason)
            feed.stand(60, CameraAngle.Side); feed.stand(35, CameraAngle.Side)
            repeat(5) { feed.rep(CameraAngle.Side) }
            val finishing = vm.uiState.value as CaptureUiState.Recording
            assertTrue(finishing.finishingAfterRep)
            assertNull(finishing.rotation)
            // The final rep's own line comes first, then the total.
            assertEquals("That's ten reps.", finishing.closing[1])
        }
    }

    @Test fun `a confirmed early end closes honestly and still saves when speech never completes`() = runTest(dispatcher) {
        val detector = FakeDetector()
        val saved = CompletableDeferred<SessionResult>()
        val repository = object : SessionRepository by NoOpRepository {
            override suspend fun save(session: SessionResult) { saved.complete(session) }
        }
        val vm = CaptureViewModel(Skill.Setting, "test", { detector }, { detector }, repository, SessionScorer(), false)
        val store = ViewModelStore().apply { put("capture", vm) }
        runCurrent()
        try {
            vm.setVoiceGuidanceEnabled(true)
            val feed = Feed(this, detector, vm)
            feed.stand(180); feed.stand(35); feed.rep()
            val id = (vm.uiState.value as CaptureUiState.Recording).sessionId
            vm.onEndEarlyConfirmed()
            val finishing = vm.uiState.value as CaptureUiState.Recording
            assertEquals(listOf("That's one rep.", "We stopped early, so this one is saved as incomplete."), finishing.closing)
            // A second confirmation cannot restart the closing.
            vm.onEndEarlyConfirmed()
            assertEquals(finishing.closing, (vm.uiState.value as CaptureUiState.Recording).closing)
            vm.onVideoFinalized(id, null)
            advanceTimeBy(com.volleyballlab.player.ui.coaching.SpeechTiming.turnMs(finishing.closing) + 2_100)
            runCurrent()
            assertEquals(id, saved.await().id)
            runCurrent()
            assertTrue(vm.uiState.value is CaptureUiState.Finished)
        } finally { store.clear(); runCurrent() }
    }

    @Test fun `the break debrief carries a local takeaway without the AI switch`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            feed.stand(180); feed.stand(35); repeat(5) { feed.rep() }
            val turn = (vm.uiState.value as CaptureUiState.Recording).rotation!!
            assertEquals(2, turn.segments.size)
            assertTrue(turn.segments[0].startsWith("Five."))
            assertTrue(turn.segments[1].startsWith("Front view done"))
            assertEquals((turn.segments + turn.instruction).joinToString(" "), turn.message)
            assertFalse(turn.awaitingAdvice)
            turn.advice?.let { advice ->
                assertFalse(advice.aiGenerated)
                assertTrue(turn.segments[1].endsWith(advice.text))
            }
        }
    }

    @Test fun `the session starts and resumes with spoken non-rep lines and remembers earlier sessions`() = runTest(dispatcher) {
        val earlier = SessionResult("earlier", Skill.Setting, "test", 1, 60_000, emptyList())
        val repository = object : SessionRepository by NoOpRepository {
            override val sessions: Flow<List<SessionResult>> = flowOf(listOf(earlier))
        }
        val detector = FakeDetector()
        val vm = CaptureViewModel(Skill.Setting, "test", { detector }, { detector }, repository, SessionScorer(), false)
        val store = ViewModelStore().apply { put("capture", vm) }
        runCurrent()
        try {
            assertEquals(1, vm.coachingPlan.value.sessionsOnSkill)
            val events = mutableListOf<com.volleyballlab.player.pose.analysis.CoachingEvent>()
            val speech = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.coachingEvents.collect { events += it }
            }
            val feed = Feed(this, detector, vm)
            feed.stand(180)
            assertEquals(listOf("Go when you're ready."), events.map { it.text })
            feed.stand(35); repeat(5) { feed.rep() }
            events.clear()
            feed.stand(60, CameraAngle.Side); feed.stand(20, CameraAngle.Side)
            assertTrue(events.any { it.text == "Same again from this side." && it.repNumber == null })
            events.clear()
            feed.stand(200, CameraAngle.Side)
            // One nudge per idle spell, and it says what a rep of this skill is.
            assertEquals(1, events.count { it.text == com.volleyballlab.player.pose.analysis.CoachPhrasebook.idle(Skill.Setting) })
            speech.cancel()
        } finally { store.clear(); runCurrent() }
    }

    @Test fun `side progress never displays the completed front reps`() = runTest(dispatcher) {
        listOf(5, 10).forEach { target ->
            withCapture { vm, feed ->
                val options = GuidedCaptureOptions(repsPerAngle = target)
                vm.setRepsPerAngle(target)
                feed.stand(180)
                feed.stand(35)
                repeat(target) { feed.rep() }

                val turning = vm.uiState.value as CaptureUiState.Recording
                val progress = turning.displayedProgress(options)
                assertEquals(CameraAngle.Side, progress.angle)
                assertEquals("Side must start at 0 of $target while the athlete turns", 0, progress.metrics.reps)
                assertEquals("Front reps remain in the session total", target, turning.totalMetrics.reps)

                feed.stand(60, CameraAngle.Side)
                feed.stand(35, CameraAngle.Side, occluded = true)
                feed.rep(CameraAngle.Side, occluded = true)
                val recording = vm.uiState.value as CaptureUiState.Recording
                assertNull(recording.rotation)
                assertEquals(CameraAngle.Side, recording.displayedProgress(options).angle)
                assertEquals(1, recording.displayedProgress(options).metrics.reps)
                assertEquals(target + 1, recording.totalMetrics.reps)
            }
        }
    }

    /** The reps are swung with the right hand; only the chosen arm decides which way to turn. */
    @Test fun `the side view always turns the chosen arm toward the camera`() = runTest(dispatcher) {
        for (skill in listOf(Skill.Serving, Skill.Setting)) {
            for (arm in com.volleyballlab.player.domain.repository.DominantArm.entries) {
                withCapture(skill = skill, dominantArm = arm) { vm, feed ->
                    vm.setRepsPerAngle(5)
                    feed.stand(180)
                    feed.stand(35)
                    repeat(5) { if (skill == Skill.Serving) { feed.serve(); feed.stand(20) } else feed.rep() }
                    val turn = requireNotNull((vm.uiState.value as CaptureUiState.Recording).rotation)
                    val left = arm == com.volleyballlab.player.domain.repository.DominantArm.Left
                    assertEquals("$skill $arm", if (left) com.volleyballlab.player.pose.analysis.Facing.Right
                        else com.volleyballlab.player.pose.analysis.Facing.Left, turn.facing)
                    assertTrue(turn.instruction, turn.instruction.contains("your ${if (left) "left" else "right"} shoulder points at the camera"))
                }
            }
        }
    }

    @Test fun `five reps request side and turning motion cannot add reps`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            vm.setRepsPerAngle(5)
            feed.stand(180)
            assertTrue(vm.uiState.value is CaptureUiState.Recording)
            feed.stand(35)
            repeat(5) { feed.rep() }
            val turn = vm.uiState.value as CaptureUiState.Recording
            assertEquals(CameraAngle.Side, turn.rotation?.requestedAngle)
            assertEquals(5, turn.totalMetrics.reps)
            repeat(3) { feed.rep() }
            assertEquals(5, (vm.uiState.value as CaptureUiState.Recording).totalMetrics.reps)
            feed.stand(60, CameraAngle.Side)
            val next = vm.uiState.value as CaptureUiState.Recording
            assertNull(next.rotation)
            assertEquals(CameraAngle.Side, next.angle)
            assertEquals(0, next.stageMetrics.reps)
            assertEquals(5, next.totalMetrics.reps)
        }
    }

    @Test fun `side reps continue when the far arm and leg are occluded`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            feed.stand(180); feed.stand(35); repeat(5) { feed.rep() }
            feed.stand(60, CameraAngle.Side)
            assertEquals(CameraAngle.Side, (vm.uiState.value as CaptureUiState.Recording).angle)
            feed.stand(35, CameraAngle.Side, occluded = true)
            feed.rep(CameraAngle.Side, occluded = true)
            val state = vm.uiState.value as CaptureUiState.Recording
            assertEquals("Visible Side movement should count despite hidden far limbs", 1, state.stageMetrics.reps)
            assertEquals(6, state.totalMetrics.reps)
        }
    }

    @Test fun `coaching observes repeated front evidence and relearns after backgrounding`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            vm.setRepsPerAngle(10)
            val events = mutableListOf<com.volleyballlab.player.pose.analysis.CoachingEvent>()
            val speech = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.coachingEvents.collect { events += it }
            }
            try {
                feed.stand(180); feed.stand(35)
                repeat(2) { feed.rep() }
                assertFalse(events.any { it.type == com.volleyballlab.player.pose.analysis.CoachingEventType.Correction })
                feed.rep()
                assertEquals(1, events.count { it.type == com.volleyballlab.player.pose.analysis.CoachingEventType.Correction })
                feed.rep()
                assertEquals("The next rep is a quiet retry", 1,
                    events.count { it.type == com.volleyballlab.player.pose.analysis.CoachingEventType.Correction })

                vm.setCaptureForeground(false)
                vm.setCaptureForeground(true)
                events.clear()
                feed.stand(200)
                repeat(2) { feed.rep() }
                assertFalse("Old evidence cannot bridge backgrounding",
                    events.any { it.type == com.volleyballlab.player.pose.analysis.CoachingEventType.Correction })
                feed.rep()
                assertEquals(1, events.count { it.type == com.volleyballlab.player.pose.analysis.CoachingEventType.Correction })
            } finally { speech.cancel() }
        }
    }

    @Test fun `wrong view asks for the required angle instead of stepping back`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            val events = mutableListOf<com.volleyballlab.player.pose.analysis.CoachingEvent>()
            val speech = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.coachingEvents.collect { events += it }
            }
            try {
                feed.stand(180); feed.stand(35)
                events.clear()
                feed.stand(35, CameraAngle.Side)
                val tracking = events.filter { it.type == com.volleyballlab.player.pose.analysis.CoachingEventType.Tracking }
                assertEquals(1, tracking.size)
                assertTrue(tracking.single().text.startsWith("Face forward again"))
                val state = vm.uiState.value as CaptureUiState.Recording
                assertEquals("The card says which way to turn too", tracking.single().text, state.suspensionHint)
                assertEquals(0, state.stageMetrics.reps)
            } finally { speech.cancel() }
        }
    }

    @Test fun `missing body takes priority over a wrong view prompt`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            val events = mutableListOf<com.volleyballlab.player.pose.analysis.CoachingEvent>()
            val speech = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.coachingEvents.collect { events += it }
            }
            try {
                feed.stand(180); feed.stand(35)
                events.clear()
                feed.hideFeet = true
                feed.stand(35, CameraAngle.Side)
                val tracking = events.filter { it.type == com.volleyballlab.player.pose.analysis.CoachingEventType.Tracking }
                assertEquals(1, tracking.size)
                assertTrue(tracking.single().text.contains("whole body is visible"))
                assertFalse(tracking.single().text.contains("Face forward"))
                assertNull((vm.uiState.value as CaptureUiState.Recording).suspensionHint)
            } finally { speech.cancel() }
        }
    }

    @Test fun `side setting with the far arm and leg hidden is assessed from the near side`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            val events = mutableListOf<com.volleyballlab.player.pose.analysis.CoachingEvent>()
            val speech = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.coachingEvents.collect { events += it }
            }
            try {
                feed.stand(180); feed.stand(35); repeat(5) { feed.rep() }
                feed.stand(60, CameraAngle.Side)
                events.clear()
                feed.stand(35, CameraAngle.Side, occluded = true)
                repeat(3) { feed.rep(CameraAngle.Side, occluded = true) }
                assertEquals(3, (vm.uiState.value as CaptureUiState.Recording).stageMetrics.reps)
                assertTrue("Side reps must not leave speech silent when correction measurements are unavailable",
                    events.any { it.type == com.volleyballlab.player.pose.analysis.CoachingEventType.RepMilestone })
                // Side-on the far limbs are always behind the body. The near arm and leg
                // were in view, so no rep may be called only partly visible.
                assertFalse("A side view must not report a fully visible near side as partly visible",
                    events.any { "part of your form" in it.text || "part of that one" in it.text })
            } finally { speech.cancel() }
        }
    }

    @Test fun `side tracking still rejects missing feet and recovers without counting the gap`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            feed.stand(180); feed.stand(35); repeat(5) { feed.rep() }
            feed.stand(60, CameraAngle.Side)
            feed.hideFeet = true
            feed.stand(35, CameraAngle.Side, occluded = true)
            feed.rep(CameraAngle.Side, occluded = true)
            assertEquals(0, (vm.uiState.value as CaptureUiState.Recording).stageMetrics.reps)
            feed.hideFeet = false
            feed.stand(35, CameraAngle.Side, occluded = true)
            feed.rep(CameraAngle.Side, occluded = true)
            assertEquals(1, (vm.uiState.value as CaptureUiState.Recording).stageMetrics.reps)
        }
    }

    @Test fun `front capture keeps its visibility requirement`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            feed.stand(180)
            feed.stand(35, occluded = true)
            feed.rep(occluded = true)
            assertEquals(0, (vm.uiState.value as CaptureUiState.Recording).totalMetrics.reps)
        }
    }

    @Test fun `ten rep choice does not turn at five and required side cannot be skipped`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            vm.setRepsPerAngle(10)
            feed.stand(180)
            feed.stand(35)
            repeat(5) { feed.rep() }
            assertNull((vm.uiState.value as CaptureUiState.Recording).rotation)
            repeat(5) { feed.rep() }
            val turn = vm.uiState.value as CaptureUiState.Recording
            assertEquals(10, turn.totalMetrics.reps)
            assertEquals(CameraAngle.Side, turn.rotation?.requestedAngle)
            vm.onSkipAngle()
            assertEquals(CameraAngle.Side, (vm.uiState.value as CaptureUiState.Recording).rotation?.requestedAngle)
        }
    }

    @Test fun `angle confirmation and countdown wait for the complete spoken coaching turn`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            vm.setVoiceGuidanceEnabled(true)
            feed.stand(180); feed.stand(35); repeat(5) { feed.rep() }
            val turn = (vm.uiState.value as CaptureUiState.Recording).rotation!!
            assertTrue(turn.speechPending)
            feed.stand(80, CameraAngle.Side)
            assertNull((vm.uiState.value as CaptureUiState.Recording).rotation!!.secondsLeft)
            vm.onRotationSpeechFinished(turn.guidanceId + 1)
            assertTrue((vm.uiState.value as CaptureUiState.Recording).rotation!!.speechPending)
            vm.onRotationSpeechFinished(turn.guidanceId)
            feed.stand(10, CameraAngle.Side)
            assertNull((vm.uiState.value as CaptureUiState.Recording).rotation!!.secondsLeft)
            feed.stand(50, CameraAngle.Side)
            assertNull((vm.uiState.value as CaptureUiState.Recording).rotation)
            assertEquals(CameraAngle.Side, (vm.uiState.value as CaptureUiState.Recording).angle)
        }
    }

    @Test fun `muting coaching releases the speech gate without requiring a screen tap`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            vm.setVoiceGuidanceEnabled(true)
            feed.stand(180); feed.stand(35); repeat(5) { feed.rep() }
            assertTrue((vm.uiState.value as CaptureUiState.Recording).rotation!!.speechPending)
            vm.setVoiceGuidanceEnabled(false)
            feed.stand(60, CameraAngle.Side)
            assertNull((vm.uiState.value as CaptureUiState.Recording).rotation)
        }
    }

    @Test fun `background does not replay a started turn and explicit retry gets a new turn`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            vm.setVoiceGuidanceEnabled(true)
            feed.stand(180); feed.stand(35); repeat(5) { feed.rep() }
            val first = (vm.uiState.value as CaptureUiState.Recording).rotation!!.guidanceId
            vm.onRotationSpeechStarted(first)
            vm.setCaptureForeground(false)
            vm.onRotationSpeechFinished(first)
            vm.setCaptureForeground(true)
            vm.onRotationSpeechFinished(first)
            assertFalse((vm.uiState.value as CaptureUiState.Recording).rotation!!.speechPending)
            val resumed = (vm.uiState.value as CaptureUiState.Recording).rotation!!.guidanceId
            vm.onRetryAngle()
            vm.onRotationSpeechFinished(resumed)
            assertTrue((vm.uiState.value as CaptureUiState.Recording).rotation!!.speechPending)
            val retry = (vm.uiState.value as CaptureUiState.Recording).rotation!!.guidanceId
            vm.onRotationSpeechFinished(retry)
            feed.stand(60, CameraAngle.Side)
            assertNull((vm.uiState.value as CaptureUiState.Recording).rotation)
        }
    }

    @Test fun `late AI advice never gates a turn or restores speech after rotation`() = runTest(dispatcher) {
        val response = CompletableDeferred<LiveCoachingAdvice?>()
        var requests = 0
        val ai = object : LiveCoachingRepository {
            override suspend fun advise(evidence: LiveCoachingEvidence, voice: Boolean,
                context: com.volleyballlab.player.pose.analysis.LiveCoachingTurnContext?): LiveCoachingAdvice? {
                requests++
                return response.await()
            }
            override suspend fun previewVoice(): String? = null
        }
        withCapture(detailed = true, ai = ai) { vm, feed ->
            feed.stand(180); feed.stand(35); repeat(5) { feed.rep() }
            assertEquals(1, requests)
            assertFalse((vm.uiState.value as CaptureUiState.Recording).rotation!!.loadingCoaching)
            feed.stand(60, CameraAngle.Side)
            response.complete(LiveCoachingAdvice(com.volleyballlab.player.domain.model.CueId.SetElbowFlexion, "Old angle advice", true))
            runCurrent()
            val state = vm.uiState.value as CaptureUiState.Recording
            assertEquals(CameraAngle.Side, state.angle)
            assertNull(state.rotation)
        }
    }

    @Test fun `AI advice that arrives while the turn is still speaking joins it and sends only closed context`() = runTest(dispatcher) {
        val response = CompletableDeferred<LiveCoachingAdvice?>()
        val contexts = mutableListOf<com.volleyballlab.player.pose.analysis.LiveCoachingTurnContext?>()
        val ai = object : LiveCoachingRepository {
            override suspend fun advise(evidence: LiveCoachingEvidence, voice: Boolean,
                context: com.volleyballlab.player.pose.analysis.LiveCoachingTurnContext?): LiveCoachingAdvice? {
                contexts += context
                return response.await()
            }
            override suspend fun previewVoice(): String? = null
        }
        val profile = com.volleyballlab.player.domain.model.AthleteProfile(firstName = "Maya", name = "Maya Santos",
            goalText = "Make the varsity team")
        withCapture(detailed = true, ai = ai, profile = profile) { vm, feed ->
            val joined = mutableListOf<Pair<Long, String>>()
            val late = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.lateAdviceSpeech.collect { joined += it } }
            vm.setVoiceGuidanceEnabled(true)
            feed.stand(180); feed.stand(35); repeat(5) { feed.rep() }
            val turn = (vm.uiState.value as CaptureUiState.Recording).rotation!!
            assertTrue(turn.speechPending); assertTrue(turn.awaitingAdvice)
            val sent = contexts.single()!!
            assertEquals(com.volleyballlab.player.pose.analysis.LiveCoachingTurnContext.Turn.Break, sent.turn)
            assertEquals(0, sent.breakIndex)
            assertFalse("Nothing about the person is in the context", sent.toString().contains("Maya") || sent.toString().contains("varsity"))
            response.complete(LiveCoachingAdvice(com.volleyballlab.player.domain.model.CueId.SetElbowFlexion, "Think of a wide window between your arms.", true))
            runCurrent()
            val updated = (vm.uiState.value as CaptureUiState.Recording).rotation!!
            assertEquals(listOf(turn.guidanceId to "Think of a wide window between your arms."), joined)
            assertTrue(updated.advice!!.aiGenerated); assertFalse(updated.awaitingAdvice)
            // The turn is still gated only by its own speech, exactly as before.
            assertTrue(updated.speechPending)
            vm.onRotationSpeechFinished(turn.guidanceId)
            feed.stand(60, CameraAngle.Side)
            assertNull((vm.uiState.value as CaptureUiState.Recording).rotation)
            late.cancel()
        }
    }

    @Test fun `AI advice that arrives after the turn's speech stays on the card`() = runTest(dispatcher) {
        val response = CompletableDeferred<LiveCoachingAdvice?>()
        val ai = object : LiveCoachingRepository {
            override suspend fun advise(evidence: LiveCoachingEvidence, voice: Boolean,
                context: com.volleyballlab.player.pose.analysis.LiveCoachingTurnContext?): LiveCoachingAdvice? = response.await()
            override suspend fun previewVoice(): String? = null
        }
        withCapture(detailed = true, ai = ai) { vm, feed ->
            val joined = mutableListOf<Pair<Long, String>>()
            val late = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.lateAdviceSpeech.collect { joined += it } }
            vm.setVoiceGuidanceEnabled(true)
            feed.stand(180); feed.stand(35); repeat(5) { feed.rep() }
            val turn = (vm.uiState.value as CaptureUiState.Recording).rotation!!
            vm.onRotationSpeechFinished(turn.guidanceId)
            response.complete(LiveCoachingAdvice(com.volleyballlab.player.domain.model.CueId.SetElbowFlexion, "Card only.", true))
            runCurrent()
            assertTrue(joined.isEmpty())
            assertEquals("Card only.", (vm.uiState.value as CaptureUiState.Recording).rotation!!.advice!!.text)
            late.cancel()
        }
    }

    @Test fun `finished result already has captured replay saved without another detector pass`() = runTest(dispatcher) {
        val detector = FakeDetector()
        val feedback = CompletableDeferred<CapturedReviewInput>()
        val finishWrite = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<SessionResult>()
        val calls = mutableListOf<String>()
        val repository = object : SessionRepository by NoOpRepository {
            override suspend fun save(session: SessionResult) { calls += "session"; saved.complete(session) }
        }
        val writer = CapturedReviewWriter { path, skill, input ->
            assertEquals("recording.mp4", path)
            assertEquals(Skill.Setting, skill)
            feedback.complete(input)
            finishWrite.await()
            calls += "feedback"
        }
        val vm = CaptureViewModel(Skill.Setting, "test", { detector }, { detector }, repository, SessionScorer(), false,
            capturedReviewWriter = writer)
        val store = ViewModelStore().apply { put("capture", vm) }
        runCurrent()
        try {
            val feed = Feed(this, detector, vm)
            feed.stand(180)
            val id = (vm.uiState.value as CaptureUiState.Recording).sessionId
            val origin = feed.timestamp - 1_000
            vm.onVideoClock(id, feed.timestamp, 1_000, false)
            feed.stand(35)
            repeat(2) { feed.rep() }
            vm.onVideoFinalized(id, "recording.mp4")
            vm.onStopRequested(force = true)
            val input = feedback.await()
            assertTrue(input.frames.isNotEmpty())
            assertEquals(origin, input.videoOriginCameraMs)
            assertTrue(calls.isEmpty())
            assertEquals(CaptureUiState.Analysing, vm.uiState.value)
            finishWrite.complete(Unit)
            val result = saved.await()
            runCurrent()
            assertEquals(listOf("feedback", "session"), calls)
            assertEquals("recording.mp4", result.videoPath)
            assertTrue(result.repCount > 0)
            assertTrue(vm.uiState.value is CaptureUiState.Finished)
        } finally { store.clear(); runCurrent() }
    }

    @Test fun `failed session storage leaves analysis without reporting a saved result`() = runTest(dispatcher) {
        val detector = FakeDetector()
        val saveAttempted = CompletableDeferred<Unit>()
        val repository = object : SessionRepository by NoOpRepository {
            override suspend fun save(session: SessionResult) {
                saveAttempted.complete(Unit)
                throw java.io.IOException("Storage full")
            }
        }
        val vm = CaptureViewModel(Skill.Setting, "test", { detector }, { detector }, repository, SessionScorer(), false)
        val store = ViewModelStore().apply { put("capture", vm) }
        runCurrent()
        try {
            val feed = Feed(this, detector, vm)
            feed.stand(180)
            val id = (vm.uiState.value as CaptureUiState.Recording).sessionId
            vm.onVideoFinalized(id, null)
            vm.onStopRequested(force = true)
            saveAttempted.await()
            runCurrent()
            assertEquals(CaptureUiState.SaveFailed, vm.uiState.value)
            assertFalse("A failed save must end the busy state", vm.uiState.value is CaptureUiState.Analysing)
            assertFalse("A failed save must not navigate to a missing result", vm.uiState.value is CaptureUiState.Finished)
        } finally { store.clear(); runCurrent() }
    }

    @Test fun `retry saves the retained session and video once despite repeated taps`() = runTest(dispatcher) {
        val detector = FakeDetector()
        val firstAttempt = CompletableDeferred<Unit>()
        val finishRetry = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<SessionResult>()
        val attempts = mutableListOf<SessionResult>()
        var reviewWrites = 0
        val repository = object : SessionRepository by NoOpRepository {
            override suspend fun save(session: SessionResult) {
                attempts += session
                if (attempts.size == 1) {
                    firstAttempt.complete(Unit)
                    throw java.io.IOException("Storage full")
                }
                finishRetry.await()
                saved.complete(session)
            }
        }
        val writer = CapturedReviewWriter { _, _, _ -> reviewWrites++ }
        val vm = CaptureViewModel(Skill.Setting, "test", { detector }, { detector }, repository,
            SessionScorer(), false, assignmentId = "assignment-1", capturedReviewWriter = writer)
        val store = ViewModelStore().apply { put("capture", vm) }
        runCurrent()
        try {
            vm.setGuidedAngles(true)
            val feed = Feed(this, detector, vm)
            feed.stand(180)
            val id = (vm.uiState.value as CaptureUiState.Recording).sessionId
            feed.stand(35)
            repeat(2) { feed.rep() }
            vm.onVideoFinalized(id, "recording.mp4")
            vm.onStopRequested(force = true)
            firstAttempt.await()
            runCurrent()
            assertEquals(CaptureUiState.SaveFailed, vm.uiState.value)
            assertEquals(1, reviewWrites)
            val prepared = attempts.single()
            assertTrue(prepared.repCount > 0)
            assertEquals("assignment-1", prepared.assignmentId)
            assertEquals("recording.mp4", prepared.videoPath)
            assertTrue(prepared.angleStages.isNotEmpty())

            feed.rep()
            vm.onRetrySaveRequested()
            vm.onRetrySaveRequested()
            runCurrent()
            assertEquals(CaptureUiState.Analysing, vm.uiState.value)
            assertEquals(2, attempts.size)
            finishRetry.complete(Unit)
            assertEquals(prepared, saved.await())
            runCurrent()
            assertEquals(CaptureUiState.Finished(id, prepared.repCount), vm.uiState.value)
            assertEquals(1, reviewWrites)
            vm.onRetrySaveRequested()
            runCurrent()
            assertEquals(2, attempts.size)
        } finally { store.clear(); runCurrent() }
    }

    @Test fun `cancelled storage does not become a retryable save failure`() = runTest(dispatcher) {
        val detector = FakeDetector()
        val saveAttempted = CompletableDeferred<Unit>()
        val repository = object : SessionRepository by NoOpRepository {
            override suspend fun save(session: SessionResult) {
                saveAttempted.complete(Unit)
                throw kotlinx.coroutines.CancellationException("Capture closed")
            }
        }
        val vm = CaptureViewModel(Skill.Setting, "test", { detector }, { detector }, repository, SessionScorer(), false)
        val store = ViewModelStore().apply { put("capture", vm) }
        runCurrent()
        try {
            val feed = Feed(this, detector, vm)
            feed.stand(180)
            val id = (vm.uiState.value as CaptureUiState.Recording).sessionId
            vm.onVideoFinalized(id, null)
            vm.onStopRequested(force = true)
            saveAttempted.await()
            runCurrent()
            assertFalse(vm.uiState.value is CaptureUiState.SaveFailed)
            assertFalse(vm.uiState.value is CaptureUiState.Finished)
        } finally { store.clear(); runCurrent() }
    }

    @Test fun `finish front requires side and short side can only save through early exit`() = runTest(dispatcher) {
        withCapture { vm, feed ->
            feed.stand(180); feed.stand(35); feed.rep()
            vm.onStopRequested()
            assertEquals(CameraAngle.Side, (vm.uiState.value as CaptureUiState.Recording).rotation?.requestedAngle)
            vm.onSkipAngle()
            assertEquals(CameraAngle.Side, (vm.uiState.value as CaptureUiState.Recording).rotation?.requestedAngle)
            feed.stand(60, CameraAngle.Side)
            vm.onStopRequested()
            val blocked = vm.uiState.value as CaptureUiState.Recording
            assertEquals(CameraAngle.Side, blocked.angle)
            assertNotNull(blocked.blockedReason)
        }
    }

    private suspend fun TestScope.withCapture(detailed: Boolean = false, ai: LiveCoachingRepository? = null,
        profile: com.volleyballlab.player.domain.model.AthleteProfile = com.volleyballlab.player.domain.model.AthleteProfile.Anonymous,
        skill: Skill = Skill.Setting,
        poseGuide: Boolean = true,
        dominantArm: com.volleyballlab.player.domain.repository.DominantArm = com.volleyballlab.player.domain.repository.DominantArm.Right,
        body: suspend TestScope.(CaptureViewModel, Feed) -> Unit) {
        val detector = FakeDetector()
        val prefs = object : CoachingPreferencesRepository {
            override val preferences = flowOf(CoachingPreferences(detailedAiCoachingEnabled = detailed,
                poseGuideEnabled = poseGuide, dominantArm = dominantArm))
            override suspend fun setVoiceEnabled(enabled: Boolean) = Unit
            override suspend fun setHapticsEnabled(enabled: Boolean) = Unit
            override suspend fun setPoseGuideEnabled(enabled: Boolean) = Unit
            override suspend fun setDetailedAiCoachingEnabled(enabled: Boolean) = Unit
            override suspend fun setDominantArm(arm: com.volleyballlab.player.domain.repository.DominantArm) = Unit
        }
        val profiles = object : com.volleyballlab.player.domain.repository.ProfileRepository {
            override val profile = flowOf(profile)
            override suspend fun save(profile: com.volleyballlab.player.domain.model.AthleteProfile) = Unit
        }
        val vm = CaptureViewModel(skill, "test", { detector }, { detector }, NoOpRepository, SessionScorer(), false,
            coachingPreferencesRepository = prefs, liveCoachingRepository = ai, profileRepository = profiles)
        val store = ViewModelStore().apply { put("capture", vm) }
        runCurrent()
        try { body(vm, Feed(this, detector, vm)) } finally { store.clear(); runCurrent() }
    }

    private class Feed(val scope: TestScope, val detector: FakeDetector, val vm: CaptureViewModel) {
        var timestamp = 0L
        var hideFeet = false
        /** Runs once each frame has been fully handled, for tests that watch what is on screen as it happens. */
        var afterFrame: (() -> Unit)? = null
        suspend fun stand(frames: Int, angle: CameraAngle = CameraAngle.Front, occluded: Boolean = false) {
            repeat(frames) { emit(pose(angle, occluded)) }
        }
        /**
         * One raise and lowering of the hands: the elbows bend as the hands come
         * up to the face and open again in the push. They stop short of straight,
         * which is counted and leaves the coach something to refine.
         *
         * @param peak how far the wrists travel up the picture. The default ends
         *   above the face, which is a set; a taller one clears the head.
         * @param hands the wrists that move. Both is the skill; one is not.
         */
        suspend fun rep(
            angle: CameraAngle = CameraAngle.Front,
            occluded: Boolean = false,
            peak: Float = .35f,
            hands: Set<BodyPoint> = setOf(BodyPoint.LEFT_WRIST, BodyPoint.RIGHT_WRIST),
        ) {
            val steps = listOf(0f to 175f, .05f to 160f, .15f to 110f, .25f to 95f, .35f to 90f, .35f to 115f, .35f to 135f,
                .25f to 110f, .15f to 120f, .05f to 160f, 0f to 175f, 0f to 175f)
            steps.forEach { (step, elbow) ->
                val lift = step / .35f * peak
                emit(hands.fold(pose(angle, occluded)) { frame, wrist -> raise(frame, wrist, lift, elbow) })
            }
        }

        /** An overhand serve: the left arm guides, then the right is drawn back and swings overhead as it comes down. */
        suspend fun serve(angle: CameraAngle = CameraAngle.Front) {
            val steps = listOf(
                0f to 0f, .12f to .05f, .25f to .14f, .30f to .24f, .30f to .30f, .20f to .36f, .10f to .43f,
                .05f to .36f, 0f to .20f, 0f to .05f, 0f to 0f, 0f to 0f,
            )
            val elbows = listOf(175f, 150f, 110f, 90f, 85f, 120f, 162f, 150f, 150f, 165f, 175f, 175f)
            steps.forEachIndexed { index, (guide, hit) ->
                emit(raise(raise(pose(angle), BodyPoint.LEFT_WRIST, guide, 160f), BodyPoint.RIGHT_WRIST, hit, elbows[index]))
            }
        }

        /** [wrist] lifted by [lift] of the picture, with its elbow placed so the arm bends by [elbow] degrees. */
        private fun raise(frame: PoseFrame, wrist: BodyPoint, lift: Float, elbow: Float): PoseFrame {
            if (lift == 0f) return frame
            val left = wrist == BodyPoint.LEFT_WRIST
            val shoulderPoint = if (left) BodyPoint.LEFT_SHOULDER else BodyPoint.RIGHT_SHOULDER
            val elbowPoint = if (left) BodyPoint.LEFT_ELBOW else BodyPoint.RIGHT_ELBOW
            fun bend(points: List<com.volleyballlab.player.pose.model.Landmark>, up: Float, aspect: Float) = points.toMutableList().also { out ->
                val shoulder = points[shoulderPoint.ordinal]
                val hand = points[wrist.ordinal].let { it.copy(y = it.y - up) }
                out[wrist.ordinal] = hand
                // The elbow sits off the middle of the shoulder-to-hand line, out to the side.
                val dx = (hand.x - shoulder.x) * aspect
                val dy = hand.y - shoulder.y
                val dz = hand.z - shoulder.z
                val length = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(.02f)
                val off = length / 2f / kotlin.math.tan(Math.toRadians(elbow / 2.0)).toFloat()
                val outward = if (points[shoulderPoint.ordinal].x >= points[(if (left) BodyPoint.RIGHT_SHOULDER else BodyPoint.LEFT_SHOULDER).ordinal].x) 1f else -1f
                // Perpendicular to the line within the picture plane, on the outer side.
                var nx = -dy / length
                var ny = dx / length
                if (nx * outward < 0f) { nx = -nx; ny = -ny }
                val old = points[elbowPoint.ordinal]
                out[elbowPoint.ordinal] = old.copy(x = (shoulder.x + hand.x) / 2f + nx * off / aspect,
                    y = (shoulder.y + hand.y) / 2f + ny * off, z = (shoulder.z + hand.z) / 2f)
            }
            return frame.copy(image = bend(frame.image, lift, frame.imageWidth.toFloat() / frame.imageHeight), world = bend(frame.world, lift * 2f, 1f))
        }
        private fun pose(angle: CameraAngle, occluded: Boolean = false): PoseFrame {
            val frame = when (angle) {
                CameraAngle.Side -> GuidedPoseFixtures.side(timestamp)
                CameraAngle.Rear -> GuidedPoseFixtures.rear(timestamp)
                else -> GuidedPoseFixtures.front(timestamp)
            }
            if (!occluded && !hideFeet) return frame
            val far = (if (occluded) setOf(BodyPoint.RIGHT_ELBOW.ordinal, BodyPoint.RIGHT_WRIST.ordinal,
                BodyPoint.RIGHT_KNEE.ordinal, BodyPoint.RIGHT_ANKLE.ordinal) else emptySet()) +
                (if (hideFeet) setOf(BodyPoint.LEFT_ANKLE.ordinal, BodyPoint.RIGHT_ANKLE.ordinal) else emptySet())
            fun confidence(points: List<com.volleyballlab.player.pose.model.Landmark>) = points.mapIndexed { i, p ->
                p.copy(visibility = if (i in far) .1f else .8f, presence = if (i in far) .1f else .8f)
            }
            return frame.copy(image = confidence(frame.image), world = confidence(frame.world))
        }
        private suspend fun emit(frame: PoseFrame) {
            detector.poses.emit(frame)
            scope.runCurrent()
            if ((vm.uiState.value as? CaptureUiState.CountingDown)?.instruction != null) {
                vm.onOpeningFinished()
                scope.runCurrent()
            }
            afterFrame?.invoke()
            timestamp += 80
            scope.advanceTimeBy(80)
            scope.runCurrent()
        }
    }

    private class FakeDetector : PoseDetector {
        override val poses = MutableSharedFlow<PoseFrame>(extraBufferCapacity = 1)
        override fun submit(bitmap: Bitmap, timestampMs: Long, mirrored: Boolean) = Unit
        override fun close() = Unit
    }
    private object NoOpRepository : SessionRepository {
        override val sessions: Flow<List<SessionResult>> = flowOf(emptyList())
        override suspend fun save(session: SessionResult) = Unit
        override suspend fun byId(id: String): SessionResult? = null
        override suspend fun delete(id: String) = Unit
        override suspend fun deleteVideo(id: String) = Unit
    }
}
