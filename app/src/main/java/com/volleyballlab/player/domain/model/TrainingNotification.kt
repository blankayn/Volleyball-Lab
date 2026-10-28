package com.volleyballlab.player.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class TrainingNotificationType { AnalysisComplete, FeedbackDelivered, ProgressMilestone, CoachAssignment, PlayerSubmission, WeeklyDigest, CoachConnection, CoachingQuestion, CoachingAnswer }

@Serializable
data class TrainingNotification(val id: String, val type: TrainingNotificationType, val title: String,
    val message: String, val createdAtEpochMs: Long, val referenceId: String? = null, val isRead: Boolean = false,
    val delivered: Boolean = false)

object NotificationEvents {
    fun question(question: CoachingQuestion) = TrainingNotification("question:${question.id}", TrainingNotificationType.CoachingQuestion,
        "Player coaching question", "A player asked for training guidance. Open Player questions to respond.", question.createdAt, question.id)
    fun answer(question: CoachingQuestion): TrainingNotification? = question.answeredAt?.let { at ->
        TrainingNotification("question-answer:${question.id}", TrainingNotificationType.CoachingAnswer,
            "Your coach answered", "Your coaching question has a response. Open My coach to read it.", at, question.id)
    }
    fun connection(coachUid: String, member: RosterMember): TrainingNotification? {
        val decidedAt = member.decidedAtEpochMs ?: return null
        if (member.isPending) return null
        return TrainingNotification(
            "connection:$coachUid:${member.requestedAtEpochMs}:${member.status.name}:$decidedAt",
            TrainingNotificationType.CoachConnection,
            if (member.isActive) "Coach request approved" else "Coach connection ended",
            if (member.isActive) "Your coach approved your request. Open Coach to see your team."
                else "This coach connection is closed. You can choose a coach in the Coach tab.",
            decidedAt, coachUid,
        )
    }

    /** Reject stale-account pushes before storing or displaying private text. */
    fun push(data: Map<String, String>, currentUid: String?): TrainingNotification? {
        if (currentUid == null || data["uid"] != currentUid) return null
        val type = when (data["type"]) {
            "feedback" -> TrainingNotificationType.FeedbackDelivered
            "assignment" -> TrainingNotificationType.CoachAssignment
            "submission" -> TrainingNotificationType.PlayerSubmission
            "connection" -> TrainingNotificationType.CoachConnection
            else -> return null
        }
        val id = data["eventId"]?.takeIf { it.isNotBlank() && it.length <= 512 } ?: return null
        val title = data["title"]?.takeIf { it.isNotBlank() }?.take(120) ?: return null
        val body = data["body"]?.takeIf { it.isNotBlank() }?.take(240) ?: return null
        val createdAt = data["createdAt"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return TrainingNotification(id, type, title, body, createdAt, data["referenceId"])
    }

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
