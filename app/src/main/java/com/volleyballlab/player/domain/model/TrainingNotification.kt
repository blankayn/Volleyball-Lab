package com.volleyballlab.player.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class TrainingNotificationType { AnalysisComplete, FeedbackDelivered, ProgressMilestone, CoachAssignment, PlayerSubmission, WeeklyDigest }

@Serializable
data class TrainingNotification(val id: String, val type: TrainingNotificationType, val title: String,
    val message: String, val createdAtEpochMs: Long, val referenceId: String? = null, val isRead: Boolean = false,
    val delivered: Boolean = false)

object NotificationEvents {
    fun session(s: SessionResult) = TrainingNotification("analysis:${s.id}", TrainingNotificationType.AnalysisComplete,
        "Analysis complete", "${s.skill.name} session analyzed · ${s.repCount} completed reps", s.startedAtEpochMs + s.durationMs, s.id)
    fun feedback(n: CoachNote) = TrainingNotification("feedback:${n.id}", TrainingNotificationType.FeedbackDelivered,
        "Coach feedback", n.text.take(240), n.createdAtEpochMs, n.sessionId)
    fun assignment(a: CoachAssignment) = TrainingNotification("assignment:${a.id}", TrainingNotificationType.CoachAssignment,
        "New training assignment", a.title, a.createdAtEpochMs, a.id)
    fun submission(s: SessionSubmission) = TrainingNotification("submission:${s.playerUid}:${s.sessionId}:${s.submittedAtEpochMs}", TrainingNotificationType.PlayerSubmission,
        "Session ready to review", "A player submitted a training session.", s.submittedAtEpochMs, s.sessionId)
    fun milestones(sessions: List<SessionResult>): List<TrainingNotification> {
        val sorted = sessions.sortedBy { it.startedAtEpochMs }
        return listOf(5, 10, 25, 50, 100).mapNotNull { count -> sorted.getOrNull(count - 1)?.let { s ->
            TrainingNotification("milestone:sessions:$count", TrainingNotificationType.ProgressMilestone,
                "Training milestone", "You have completed $count training sessions.", s.startedAtEpochMs + s.durationMs, s.id)
        } }
    }
    fun merge(existing: List<TrainingNotification>, incoming: List<TrainingNotification>): List<TrainingNotification> {
        val current = existing.associateBy { it.id }
        return (existing + incoming.map { n -> current[n.id]?.let { n.copy(isRead = it.isRead || n.isRead, delivered = it.delivered || n.delivered) } ?: n })
            .associateBy { it.id }.values.sortedByDescending { it.createdAtEpochMs }.take(200)
    }
}
