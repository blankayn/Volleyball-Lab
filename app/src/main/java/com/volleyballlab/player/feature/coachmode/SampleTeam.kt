package com.volleyballlab.player.feature.coachmode

import com.volleyballlab.player.domain.model.CourtPosition
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.CueScore
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.MembershipStatus
import com.volleyballlab.player.domain.model.RosterMember
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.SessionSubmission
import com.volleyballlab.player.domain.model.RepResult
import com.volleyballlab.player.domain.model.Skill

/**
 * Two stand-in athletes, so a brand-new coach account has something to look at.
 *
 * ## Why this exists at all, given the rest of the app refuses to do it
 *
 * The README is emphatic that there is no sample data anywhere in this project,
 * and the reasoning is good: during a defence, mock UI is indistinguishable from
 * working UI, so a chart with nothing behind it is worse than an empty state.
 *
 * This is the exception, and it survives that objection on one condition - it is
 * *not* indistinguishable. Every row these two appear on carries a Sample chip,
 * the dashboard says in plain words that they are not real, and the whole set
 * vanishes the instant a real player joins the roster. What the README is against
 * is UI that lies about having data. Labelled scaffolding that removes itself is a
 * different thing.
 *
 * The reason it is worth having: coach mode is device-local, so an examiner
 * opening it on a fresh install sees a roster of nobody, a queue of nothing and
 * four dashes where the team figures go. That demonstrates nothing about a screen
 * whose entire job is reading a squad.
 *
 * ## What is deliberately *not* faked
 *
 * The numbers on screen are still computed, not written down. These sessions carry
 * real per-rep cue scores, and `PlayerSnapshot`, `TeamSummary` and `SessionReview`
 * derive the form scores, the trend, the consistency and the safety flags from them
 * exactly as they would for a real athlete. Nothing here hardcodes a "78" - the 78
 * comes out of the same arithmetic a real session goes through, which is the part
 * a panel is actually asking about.
 */
object SampleTeam {

    /**
     * Marks a uid as belonging to this fixture.
     *
     * A prefix rather than a flag on [RosterMember], because the marker has to
     * survive being passed around as a bare uid - the review screen, the assign
     * form and the player screen all address an athlete by uid alone, and each has
     * to be able to tell that it is looking at scaffolding.
     *
     * Deliberately contains no [com.volleyballlab.player.data.UserScope.SEPARATOR],
     * so that if one of these ever did reach a DataStore key it would still split
     * cleanly rather than corrupting the scope it landed in.
     */
    const val UID_PREFIX = "sample-"

    fun isSample(uid: String): Boolean = uid.startsWith(UID_PREFIX)

    private const val MAYA = UID_PREFIX + "maya"
    private const val ANDREI = UID_PREFIX + "andrei"

    private const val DAY = 24L * 60L * 60L * 1000L

    /**
     * The two athletes.
     *
     * Two rather than a full squad. It is enough to show a roster with more than
     * one row, a team average that is genuinely an average, and a queue with
     * something in it - and few enough that nobody could mistake the set for a real
     * team someone forgot to clear out.
     */
    fun roster(nowEpochMs: Long): List<RosterMember> = listOf(
        RosterMember(
            playerUid = MAYA,
            playerName = "Maya Reyes",
            position = CourtPosition.OutsideHitter,
            status = MembershipStatus.Active,
            requestedAtEpochMs = nowEpochMs - 24 * DAY,
            decidedAtEpochMs = nowEpochMs - 24 * DAY,
        ),
        RosterMember(
            playerUid = ANDREI,
            playerName = "Andrei Cruz",
            position = CourtPosition.MiddleBlocker,
            status = MembershipStatus.Active,
            requestedAtEpochMs = nowEpochMs - 11 * DAY,
            decidedAtEpochMs = nowEpochMs - 11 * DAY,
        ),
    )

    /**
     * Their training, shaped so the derived screens have something to say.
     *
     * The two are deliberately different athletes rather than two copies of one:
     *
     * - **Maya** improves. Her spiking climbs across the month, so the thirty-day
     *   trend reads positive and her consistency is middling - which is what a
     *   player who is actually changing looks like.
     * - **Andrei** is steady but weak on knee load, so his blocking sits lower, his
     *   consistency is high, and he trips the safety watch. That is the only way to
     *   see the safety panel do anything without filming a bad landing.
     */
    fun sessions(nowEpochMs: Long): Map<String, List<SessionResult>> = mapOf(
        MAYA to listOf(
            spike(MAYA, 1, nowEpochMs - 26 * DAY, quality = 0.52f),
            spike(MAYA, 2, nowEpochMs - 21 * DAY, quality = 0.58f),
            serve(MAYA, 3, nowEpochMs - 15 * DAY, quality = 0.63f),
            spike(MAYA, 4, nowEpochMs - 9 * DAY, quality = 0.71f),
            spike(MAYA, 5, nowEpochMs - 4 * DAY, quality = 0.79f),
            serve(MAYA, 6, nowEpochMs - 1 * DAY, quality = 0.83f),
        ),
        ANDREI to listOf(
            block(ANDREI, 1, nowEpochMs - 10 * DAY, quality = 0.66f, kneeLoad = 0.30f),
            block(ANDREI, 2, nowEpochMs - 6 * DAY, quality = 0.68f, kneeLoad = 0.26f),
            block(ANDREI, 3, nowEpochMs - 3 * DAY, quality = 0.65f, kneeLoad = 0.31f),
            serve(ANDREI, 4, nowEpochMs - 2 * DAY, quality = 0.70f),
        ),
    )

    /**
     * What is sitting in the coach's queue.
     *
     * One pending and one already answered, so the dashboard shows a queue with a
     * job in it *and* the reviewed state is reachable without the examiner having
     * to answer one first.
     *
     * The coach uid on these is [SAMPLE_COACH], not the signed-in coach's. Nothing
     * on this side reads it - the queue is already scoped to whoever is looking -
     * and stamping a real uid onto a fabricated submission is exactly the kind of
     * half-real record that later makes someone ask whether it was ever real.
     */
    fun submissions(nowEpochMs: Long): List<SessionSubmission> = listOf(
        SessionSubmission(
            sessionId = sessionId(MAYA, 6),
            coachUid = SAMPLE_COACH,
            playerUid = MAYA,
            submittedAtEpochMs = nowEpochMs - 20 * 60 * 60 * 1000L,
        ),
        SessionSubmission(
            sessionId = sessionId(ANDREI, 3),
            coachUid = SAMPLE_COACH,
            playerUid = ANDREI,
            submittedAtEpochMs = nowEpochMs - 3 * DAY,
            reviewedAtEpochMs = nowEpochMs - 2 * DAY,
        ),
    )

    private const val SAMPLE_COACH = UID_PREFIX + "coach"

    private fun sessionId(uid: String, index: Int) = "$uid-session-$index"

    // ---- Session construction ---------------------------------------------

    /**
     * Builds a session whose cues sit around [quality].
     *
     * Each rep nudges every cue by a small fixed offset rather than a random one,
     * so the same fixture renders identically on every launch. A rep-to-rep wobble
     * is still wanted - a session where every rep scores the same is the one shape
     * a real recording never has, and it would make the consistency figure
     * meaningless - but it has to be reproducible, or two screenshots of the same
     * demo would disagree.
     */
    private fun session(
        uid: String,
        index: Int,
        startedAt: Long,
        skill: Skill,
        repCount: Int,
        quality: Float,
        overrides: Map<CueId, Float> = emptyMap(),
    ): SessionResult {
        val cues = FormCueCatalog.forSkill(skill)
        val reps = (0 until repCount).map { rep ->
            // A repeating +/- ripple across the reps, offset per cue so the whole
            // rep does not rise and fall in lockstep.
            RepResult(
                index = rep,
                startMs = rep * 3_000L,
                contactMs = rep * 3_000L + 1_400L,
                endMs = rep * 3_000L + 2_800L,
                cueScores = cues.mapIndexed { cueIndex, cue ->
                    val ripple = WOBBLE[(rep + cueIndex) % WOBBLE.size]
                    val base = overrides[cue.id] ?: quality
                    CueScore(
                        cueId = cue.id,
                        // The raw measurement is not modelled: these fixtures exist
                        // to exercise the scoring and display path, and inventing a
                        // plausible knee angle in degrees would be inventing far
                        // more than the screens actually read.
                        measured = null,
                        score = (base + ripple).coerceIn(0.05f, 1f),
                    )
                },
            )
        }
        return SessionResult(
            id = sessionId(uid, index),
            skill = skill,
            drillId = "sample-drill",
            startedAtEpochMs = startedAt,
            durationMs = repCount * 3_000L,
            reps = reps,
        )
    }

    private fun spike(uid: String, index: Int, at: Long, quality: Float) =
        session(uid, index, at, Skill.Spiking, repCount = 8, quality = quality)

    private fun serve(uid: String, index: Int, at: Long, quality: Float) =
        session(uid, index, at, Skill.Serving, repCount = 10, quality = quality)

    /**
     * A blocking session with knee load pinned low.
     *
     * `BlockKneeLoad` is one of the cues `SessionReview.SAFETY_CUES` watches, so
     * holding it under the watch threshold is what makes the safety panel and the
     * dashboard's safety prompt show a real state rather than a permanent all-clear.
     */
    private fun block(uid: String, index: Int, at: Long, quality: Float, kneeLoad: Float) =
        session(
            uid = uid,
            index = index,
            startedAt = at,
            skill = Skill.Blocking,
            repCount = 9,
            quality = quality,
            overrides = mapOf(CueId.BlockKneeLoad to kneeLoad),
        )

    /** Fixed rep-to-rep variation. Prime-ish length so it does not align to 8 or 10. */
    private val WOBBLE = listOf(0.06f, -0.04f, 0.02f, -0.07f, 0.05f, -0.02f, 0.08f)
}
