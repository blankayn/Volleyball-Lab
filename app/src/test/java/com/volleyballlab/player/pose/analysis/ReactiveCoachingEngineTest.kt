package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.FormCueCatalog
import org.junit.Assert.*
import org.junit.Test

class ReactiveCoachingEngineTest {
    private val cue = FormCueCatalog.byId(CueId.PassPlatformStraight)!!
    private fun bad() = LivePostureUi(displayedCue = cue, score = 0f,
        statuses = listOf(CueLiveStatus(cue, 130f, 0f)))
    private fun good() = LiveRepAssessment(100f, 1f, true, true, listOf(CueLiveStatus(cue, 180f, 1f)))

    @Test fun `correction cooldown four seconds and duplicate twelve seconds`() {
        val engine = ReactiveCoachingEngine()
        assertNotNull(engine.onPosture(bad(), 0))
        assertNull(engine.onPosture(bad(), 3999))
        assertNull(engine.onPosture(bad(), 11999))
        assertNotNull(engine.onPosture(bad(), 12000))
    }

    @Test fun `specific correction improves after assessable good rep`() {
        val engine = ReactiveCoachingEngine()
        engine.onPosture(bad(), 0)
        val improved = engine.onRep(good(), 2, 1, 4000)
        assertEquals(CoachingEventType.Improvement, improved?.type)
        assertTrue(improved!!.text.contains("platform"))
        assertNull(engine.onRep(good().copy(isAssessable = false, isGood = false), 5, 3, 8000))
    }

    @Test fun `rotation queues at higher priority and can be canceled when view accepted`() {
        val engine = ReactiveCoachingEngine()
        engine.onPosture(bad(), 0)
        assertNull(engine.onRotation("Now face away.", 1000))
        assertNull(engine.drainDue(3999))
        assertEquals(CoachingEventType.Rotation, engine.drainDue(4000)?.type)
        assertNull(engine.drainDue(8000))
        engine.onRotation("Turn sideways.", 5000)
        engine.cancelPendingGuidance()
        assertNull(engine.drainDue(9000))
    }

    @Test fun `tracking prompt is dropped when reliable measured posture resumes`() {
        val engine = ReactiveCoachingEngine()
        engine.onPosture(bad(), 0)
        engine.onTracking(true, 1000)
        engine.onPosture(LivePostureUi(), 2000)
        assertNull(engine.drainDue(4000))
    }
}
