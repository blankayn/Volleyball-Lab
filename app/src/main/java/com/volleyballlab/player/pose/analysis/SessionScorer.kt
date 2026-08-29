package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CueScore
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.RepResult
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.model.PoseFrame

/**
 * Turns a recorded capture into a scored session.
 *
 * This is the whole post-hoc pipeline in one place, and it is deliberately a
 * plain class with no Android dependencies: given frames, it returns results. No
 * context, no coroutines, no camera. That is what makes the scoring path
 * testable against synthetic poses without an instrumented test.
 *
 * Ordering matters and is fixed:
 *
 *   frames -> segment into reps -> measure each cue on each rep -> score
 *
 * Measurement and scoring stay separate throughout. [SkillAnalyzer] produces raw
 * values in real units; [com.volleyballlab.player.domain.model.FormCue.score]
 * turns them into 0..1. Keeping the raw measurement on every [CueScore] is what
 * lets the results screen say "your elbow was at 132 degrees, aim for 155 to 180"
 * rather than only "you scored 60%", and it means a threshold change can be
 * re-applied to stored sessions without re-analysing the video.
 */
class SessionScorer(
    private val segmenter: RepSegmenter = RepSegmenter(),
) {

    /**
     * @param frames every pose captured during recording, in timestamp order.
     * @return a [SessionResult] whose `reps` may be empty. An empty list is a
     *   legitimate outcome, not an error: the athlete may have set up, recorded
     *   four seconds of standing still, and stopped. The results screen has a
     *   dedicated state for it rather than showing a score of zero.
     */
    fun score(
        sessionId: String,
        skill: Skill,
        drillId: String,
        startedAtEpochMs: Long,
        frames: List<PoseFrame>,
    ): SessionResult {
        val analyzer = SkillAnalyzers.forSkill(skill)
        val cues = FormCueCatalog.forSkill(skill)

        val reps = segmenter.segment(frames, analyzer).map { window ->
            RepResult(
                index = window.index,
                startMs = window.startMs,
                contactMs = window.contactMs,
                endMs = window.endMs,
                cueScores = cues.map { cue ->
                    val measured = analyzer.measure(cue, window)
                    CueScore(
                        cueId = cue.id,
                        measured = measured,
                        score = cue.score(measured),
                    )
                },
            )
        }

        // Duration comes from the frames rather than from a wall clock so that it
        // matches the data actually analysed. If the first second of a capture
        // produced no trackable pose, that second is not part of the session.
        val durationMs = if (frames.size < 2) {
            0L
        } else {
            frames.last().timestampMs - frames.first().timestampMs
        }

        return SessionResult(
            id = sessionId,
            skill = skill,
            drillId = drillId,
            startedAtEpochMs = startedAtEpochMs,
            durationMs = durationMs,
            reps = reps,
        )
    }
}
