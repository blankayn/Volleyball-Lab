package com.volleyballlab.player.feature.train.capture

import android.util.Log
import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.SessionAngleStage
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.repository.SessionRepository
import com.volleyballlab.player.pose.analysis.ReceivingReadyPostureEvaluator
import com.volleyballlab.player.pose.analysis.SessionScorer
import com.volleyballlab.player.pose.analysis.ScoredSession
import com.volleyballlab.player.pose.model.PoseFrame
import com.volleyballlab.player.pose.review.CapturedReviewInput
import com.volleyballlab.player.pose.review.CapturedReviewWriter
import com.volleyballlab.player.pose.review.ReviewIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class CaptureChunk(val stageIndex: Int, val angle: CameraAngle, val frames: List<PoseFrame>)

/** Frozen when recording stops; live detector events cannot change a pending save. */
internal data class CaptureCompletionInput(
    val sessionId: String,
    val skill: Skill,
    val drillId: String,
    val assignmentId: String?,
    val startedAtEpochMs: Long,
    val chunks: List<CaptureChunk>,
    val guidedAngles: Boolean,
    val angleStages: List<SessionAngleStage>,
    val replayFrames: List<PoseFrame>,
)

/**
 * Completes one stopped recording. Preparation is retained across storage retries,
 * so retrying never records again, rescans poses, or rewrites captured feedback.
 * The caller serializes attempts and owns the screen's success/failure states.
 */
internal class CaptureCompletion(
    private val input: CaptureCompletionInput,
    private val scorer: SessionScorer,
    private val sessions: SessionRepository,
    private val videoFinalization: CompletableDeferred<String?>?,
    private val videoOrigin: () -> Long?,
    private val reviewWriter: CapturedReviewWriter?,
    private val receivingPostureEvaluator: ReceivingReadyPostureEvaluator?,
) {
    private var prepared: SessionResult? = null

    suspend fun complete(): SessionResult {
        val result = prepared ?: prepare().also { prepared = it }
        // Empty sessions are also real results; they must be saved before navigation.
        sessions.save(result)
        return result
    }

    private suspend fun prepare(): SessionResult {
        val scored = withContext(Dispatchers.Default) {
            val parts = input.chunks.map { chunk ->
                scorer.scoreWithReview(
                    sessionId = input.sessionId, skill = input.skill, drillId = input.drillId,
                    startedAtEpochMs = input.startedAtEpochMs, frames = chunk.frames,
                    receivingPostureEvaluator = if (chunk.angle == CameraAngle.Front) receivingPostureEvaluator else null,
                    angle = if (input.guidedAngles) chunk.angle else null,
                )
            }
            var offset = 0
            val issues = mutableListOf<ReviewIssue>()
            parts.forEach { part ->
                issues += part.issues.map { it.copy(repNumber = it.repNumber + offset) }
                offset += part.session.repCount
            }
            val result = SessionResult(
                id = input.sessionId, skill = input.skill, drillId = input.drillId,
                assignmentId = input.assignmentId, startedAtEpochMs = input.startedAtEpochMs,
                durationMs = parts.sumOf { it.session.durationMs },
                reps = parts.flatMap { it.session.reps }.mapIndexed { index, rep -> rep.copy(index = index) },
                angleStages = if (input.guidedAngles) input.angleStages else emptyList(),
            )
            ScoredSession(result, issues.toList(), parts.sumOf { it.assessedCueCount })
        }
        val videoPath = withTimeoutOrNull(VIDEO_FINALIZE_TIMEOUT_MS) { videoFinalization?.await() }
        videoFinalization?.cancel()
        if (videoPath != null && reviewWriter != null) {
            val origin = videoOrigin() ?: input.replayFrames.firstOrNull()?.cameraTimestampMs
            if (origin != null) {
                try {
                    reviewWriter.save(videoPath, input.skill,
                        CapturedReviewInput(input.replayFrames, scored.issues, scored.assessedCueCount, origin))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Feedback is optional; the scored session and video remain usable.
                    Log.w("CaptureCompletion", "Could not save captured video feedback", error)
                }
            }
        }
        return scored.session.copy(videoPath = videoPath)
    }

    private companion object {
        const val VIDEO_FINALIZE_TIMEOUT_MS = 10_000L
    }
}
