package com.volleyballlab.player.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the coach screens claim about an athlete.
 *
 * Every number a coach reads is a pure function of stored sessions, and every one
 * of them is the kind of thing that looks right on screen whether or not it is: a
 * trend computed from one session, a consistency of 100 for a beginner's first
 * set, a "correction" built on a single rep. These are the assertions that stop
 * any of that reaching a coach who is about to write advice from it.
 */
class CoachDerivationsTest {

    // ── SessionReview ───────────────────────────────────────────────────────

    @Test
    fun `a strong session lists strengths and no corrections`() {
        val review = SessionReview.from(session(reps = List(4) { rep(it, 0.95f) }))

        assertTrue(review.strengths.isNotEmpty())
        assertTrue(review.corrections.isEmpty())
        assertEquals(4, review.repCount)
    }

    @Test
    fun `a weak session lists corrections and no strengths`() {
        val review = SessionReview.from(session(reps = List(4) { rep(it, 0.2f) }))

        assertTrue(review.corrections.isNotEmpty())
        assertTrue(review.strengths.isEmpty())
    }

    @Test
    fun `corrections come worst first`() {
        val review = SessionReview.from(session(reps = List(3) { mixedRep(it) }))
        val scores = review.corrections.map { it.meanScore }

        assertEquals(scores.sorted(), scores)
    }

    /**
     * One bad rep is noise. A cue measured only once must not become a correction
     * a coach then relays to a teenager as a technique problem.
     */
    @Test
    fun `a cue measured on a single rep is not a correction`() {
        val review = SessionReview.from(
            session(
                reps = listOf(
                    rep(0, 0.1f),
                    // Second rep measures nothing, so every cue has exactly one
                    // measurement across the session.
                    unmeasurableRep(1),
                ),
            ),
        )

        assertTrue(review.corrections.isEmpty())
        assertTrue(review.strengths.isEmpty())
    }

    @Test
    fun `panels are capped so a scorecard cannot become a wall`() {
        val review = SessionReview.from(session(reps = List(5) { rep(it, 0.1f) }))

        assertTrue(review.corrections.size <= SessionReview.PANEL_SIZE)
    }

    /**
     * A session filmed from an angle that hid the athlete is a camera problem, and
     * the screen has to be able to say so rather than reporting a thin scorecard as
     * weak technique.
     */
    @Test
    fun `an entirely unmeasurable session reports poor framing and no score`() {
        val review = SessionReview.from(session(reps = List(3) { unmeasurableRep(it) }))

        assertTrue(review.framingIsPoor)
        assertEquals(1f, review.unmeasuredFraction, 0.001f)
        assertNull(review.formScore)
    }

    @Test
    fun `a fully measured session reports no missing cues`() {
        val review = SessionReview.from(session(reps = List(3) { rep(it, 0.8f) }))

        assertEquals(0f, review.unmeasuredFraction, 0.001f)
        assertFalse(review.framingIsPoor)
    }

    // ── Safety panel ────────────────────────────────────────────────────────

    @Test
    fun `a low knee-load score raises a safety watch`() {
        val review = SessionReview.from(
            session(
                reps = List(3) {
                    rep(
                        it,
                        mapOf(
                            CueId.SpikeApproachLoad to 0.1f,
                            CueId.SpikeArmExtension to 0.9f,
                            CueId.SpikeJumpHeight to 0.9f,
                            CueId.SpikeTrunkDrive to 0.9f,
                        ),
                    )
                },
            ),
        )

        assertTrue(review.hasSafetyWatch)
        val check = review.safety.single { it.cue.id == CueId.SpikeApproachLoad }
        assertEquals(SafetyVerdict.Watch, check.verdict)
    }

    @Test
    fun `a good knee-load score reads clear`() {
        val review = SessionReview.from(session(reps = List(3) { rep(it, 0.9f) }))

        assertFalse(review.hasSafetyWatch)
        assertEquals(
            SafetyVerdict.Clear,
            review.safety.single { it.cue.id == CueId.SpikeApproachLoad }.verdict,
        )
    }

    /**
     * "Not visible" and "outside the band" are different answers, and collapsing
     * them would let a camera angle read as an injury risk.
     */
    @Test
    fun `an unmeasured safety cue is unknown rather than a watch`() {
        val review = SessionReview.from(session(reps = List(3) { unmeasurableRep(it) }))

        assertFalse(review.hasSafetyWatch)
        assertTrue(review.safety.all { it.verdict == SafetyVerdict.Unknown })
    }

    @Test
    fun `only the designated safety cues appear in the panel`() {
        val review = SessionReview.from(session(reps = List(3) { rep(it, 0.9f) }))

        assertTrue(review.safety.all { it.cue.id in SessionReview.SAFETY_CUES })
    }

    // ── PlayerSnapshot ──────────────────────────────────────────────────────

    @Test
    fun `a trend needs enough scored sessions on both sides`() {
        val threeSessions = (0 until 3).map {
            session(id = "s$it", startedAt = it * DAY, reps = List(2) { i -> rep(i, 0.5f) })
        }

        assertNull(PlayerSnapshot.trendOf(threeSessions))
    }

    @Test
    fun `a rising trend is positive and a falling one is negative`() {
        val rising = scoresOverDays(0.2f, 0.2f, 0.9f, 0.9f)
        val falling = scoresOverDays(0.9f, 0.9f, 0.2f, 0.2f)

        val up = PlayerSnapshot.trendOf(rising)
        assertNotNull(up)
        assertTrue("expected a rise, got $up", up!! > 0)

        val down = PlayerSnapshot.trendOf(falling)
        assertNotNull(down)
        assertTrue("expected a fall, got $down", down!! < 0)
    }

    /**
     * Order of the input list must not decide the answer - the repository hands
     * sessions back newest-first, and a trend that read them in list order would
     * run backwards through time while looking exactly like one that did not.
     */
    @Test
    fun `the trend is the same whichever order the sessions arrive in`() {
        val rising = scoresOverDays(0.2f, 0.2f, 0.9f, 0.9f)

        assertEquals(PlayerSnapshot.trendOf(rising), PlayerSnapshot.trendOf(rising.reversed()))
    }

    /**
     * The trend splits by count, not by date, so an athlete who trained hard for a
     * week and then stopped still gets a comparison instead of a null.
     */
    @Test
    fun `a trend survives sessions clustered on one day`() {
        val sameDay = listOf(0.3f, 0.3f, 0.8f, 0.8f).mapIndexed { index, score ->
            session(id = "s$index", startedAt = index.toLong(), reps = List(2) { rep(it, score) })
        }

        assertNotNull(PlayerSnapshot.trendOf(sameDay))
    }

    @Test
    fun `one scored session has no consistency`() {
        assertNull(PlayerSnapshot.consistencyOf(listOf(70)))
        assertNull(PlayerSnapshot.consistencyOf(emptyList()))
    }

    @Test
    fun `identical scores are perfectly consistent and scattered ones are not`() {
        assertEquals(1f, PlayerSnapshot.consistencyOf(listOf(70, 70, 70))!!, 0.001f)

        val scattered = PlayerSnapshot.consistencyOf(listOf(10, 90, 20, 95))!!
        assertTrue("expected low consistency, got $scattered", scattered < 0.3f)
    }

    @Test
    fun `consistency is clamped rather than going negative`() {
        val extreme = PlayerSnapshot.consistencyOf(listOf(0, 100, 0, 100))!!

        assertTrue(extreme >= 0f)
    }

    @Test
    fun `the weakest cue is the lowest-scoring one with enough measurements`() {
        val weakest = PlayerSnapshot.weakestCueOf(List(3) { session(id = "s$it", reps = List(3) { i -> mixedRep(i) }) })

        assertNotNull(weakest)
        assertEquals(CueId.SpikeTrunkDrive, weakest!!.first.id)
        assertEquals(Skill.Spiking, weakest.second)
    }

    @Test
    fun `there is no weakest cue without enough measurements`() {
        val thin = listOf(session(reps = listOf(rep(0, 0.1f))))

        assertNull(PlayerSnapshot.weakestCueOf(thin))
    }

    @Test
    fun `a player with no sessions reports nothing rather than zero`() {
        val snapshot = PlayerSnapshot.build(
            member = member(),
            sessions = emptyList(),
            openAssignments = 0,
            pendingSubmissions = 0,
            nowEpochMs = NOW,
        )

        assertFalse(snapshot.hasTraining)
        assertNull(snapshot.totals.averageScore)
        assertNull(snapshot.trendDelta)
        assertNull(snapshot.consistency)
        assertTrue(snapshot.standings.none { it.isMeasured })
    }

    // ── TeamSummary ─────────────────────────────────────────────────────────

    /**
     * The team average is the mean of each player's own mean, not of every session.
     * Otherwise the athlete who films thirty sets a week decides the squad's number
     * and the four who film two each barely register.
     */
    @Test
    fun `the team average weights players equally regardless of volume`() {
        val prolific = snapshotOf("busy", scores = List(20) { 0.4f })
        val occasional = snapshotOf("quiet", scores = List(2) { 1.0f })

        val summary = TeamSummary.from(
            snapshots = listOf(prolific, occasional),
            pendingReviews = 0,
            nowEpochMs = NOW,
        )

        // 40 and 100 average to 70. Weighted by session count it would be near 45.
        assertEquals(70, summary.averageFormScore)
    }

    @Test
    fun `an unscored squad has no team form rather than zero`() {
        val summary = TeamSummary.from(
            snapshots = listOf(
                PlayerSnapshot.build(member(), emptyList(), 0, 0, NOW),
            ),
            pendingReviews = 0,
            nowEpochMs = NOW,
        )

        assertNull(summary.averageFormScore)
        assertEquals(1, summary.activePlayers)
    }

    /**
     * A removed player keeps their row so the Past players list has something to
     * show, but they must not go on counting toward the squad's size or its form.
     */
    @Test
    fun `ended members are not counted as active players`() {
        val ended = PlayerSnapshot.build(
            member = member(uid = "gone", status = MembershipStatus.Ended),
            sessions = scoresOverDays(0.9f, 0.9f),
            openAssignments = 0,
            pendingSubmissions = 0,
            nowEpochMs = NOW,
        )

        val summary = TeamSummary.from(listOf(ended), pendingReviews = 0, nowEpochMs = NOW)

        assertEquals(0, summary.activePlayers)
        assertNull(summary.averageFormScore)
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    /** One session per day, oldest first, each rep scored uniformly. */
    private fun scoresOverDays(vararg scores: Float) = scores.mapIndexed { index, score ->
        session(id = "s$index", startedAt = index * DAY, reps = List(2) { rep(it, score) })
    }

    private fun snapshotOf(uid: String, scores: List<Float>) = PlayerSnapshot.build(
        member = member(uid = uid),
        sessions = scores.mapIndexed { index, score ->
            session(id = "$uid-$index", startedAt = NOW - index * DAY, reps = List(2) { rep(it, score) })
        },
        openAssignments = 0,
        pendingSubmissions = 0,
        nowEpochMs = NOW,
    )

    private fun member(
        uid: String = "player",
        status: MembershipStatus = MembershipStatus.Active,
    ) = RosterMember(
        playerUid = uid,
        playerName = "Test Player",
        position = null,
        status = status,
        requestedAtEpochMs = 0L,
    )

    private fun session(
        id: String = "session",
        skill: Skill = Skill.Spiking,
        startedAt: Long = 0L,
        reps: List<RepResult>,
    ) = SessionResult(
        id = id,
        skill = skill,
        drillId = "drill",
        startedAtEpochMs = startedAt,
        durationMs = 10_000L,
        reps = reps,
    )

    private fun rep(index: Int, scores: Map<CueId, Float?>) = RepResult(
        index = index,
        startMs = 0L,
        contactMs = 0L,
        endMs = 0L,
        cueScores = scores.map { (cueId, score) ->
            CueScore(cueId = cueId, measured = score?.let { 0f }, score = score)
        },
    )

    private fun rep(index: Int, uniformScore: Float) =
        rep(index, SPIKE_CUES.associateWith { uniformScore })

    private fun unmeasurableRep(index: Int) = rep(index, SPIKE_CUES.associateWith { null })

    /** One clearly worst cue, so the ordering assertions have one right answer. */
    private fun mixedRep(index: Int) = rep(
        index,
        mapOf(
            CueId.SpikeApproachLoad to 0.9f,
            CueId.SpikeArmExtension to 0.8f,
            CueId.SpikeJumpHeight to 0.6f,
            CueId.SpikeTrunkDrive to 0.2f,
        ),
    )

    private companion object {
        val SPIKE_CUES = listOf(
            CueId.SpikeApproachLoad,
            CueId.SpikeArmExtension,
            CueId.SpikeJumpHeight,
            CueId.SpikeTrunkDrive,
        )

        const val DAY = 24L * 60L * 60L * 1000L

        /** A fixed clock, so nothing here depends on when the suite runs. */
        const val NOW = 1_800_000_000_000L
    }
}
