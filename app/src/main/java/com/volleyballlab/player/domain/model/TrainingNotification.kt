package com.volleyballlab.player.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class TrainingNotificationType { AnalysisComplete, FeedbackDelivered, ProgressMilestone, CoachAssignment, PlayerSubmission, WeeklyDigest, CoachConnection, CoachingQuestion, CoachingAnswer }

@Serializable
data class TrainingNotification(val id: String, val type: TrainingNotificationType, val title: String,
    val message: String, val createdAtEpochMs: Long, val referenceId: String? = null, val isRead: Boolean = false,
    val delivered: Boolean = false)

object NotificationEvents {
    fun question(question: CoachingQuestion) = TrainingNotification(if (question.followUpCount == 0) "question:${question.id}" else "question:${question.id}:${question.followUpCount}", TrainingNotificationType.CoachingQuestion,
        "Player coaching question", "A player asked for training guidance. Open Player questions to respond.", question.messages.last { !it.fromCoach }.at, question.id)
    fun answer(question: CoachingQuestion): TrainingNotification? = question.messages.lastOrNull { it.fromCoach && it.at > 0 }?.let { message ->
        TrainingNotification(if (message.id == "answer") "question-answer:${question.id}" else "question-answer:${question.id}:${message.id}", TrainingNotificationType.CoachingAnswer,
            "Your coach answered", "Your coaching question has a response. Open My coach to read it.", message.at, question.id)
    }
    /** Unread incoming chat only, so signing in on a new phone does not announce old messages. */
    fun chat(messages: List<CoachingChatMessage>, reads: List<CoachingRead>, forCoach: Boolean): List<TrainingNotification> =
        messages.filter { message -> message.fromCoach != forCoach && message.createdAt >
            (reads.firstOrNull { it.coachUid == message.coachUid && it.playerUid == message.playerUid }?.at(forCoach) ?: 0L) }
            .map { TrainingNotification("chat:${it.id}",
                if (forCoach) TrainingNotificationType.CoachingQuestion else TrainingNotificationType.CoachingAnswer,
                if (forCoach) "Player message" else "Message from your coach",
                if (forCoach) "A player sent you a message. Open your inbox to reply." else "Your coach sent you a message. Open My coach to read it.",
                it.createdAt, it.id) }
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
    fun feedback(n: CoachNote) = TrainingNotification(feedbackId(n.id), TrainingNotificationType.FeedbackDelivered,
        "Coach feedback", n.text.take(240), n.createdAtEpochMs, n.sessionId)
    /** Ids of the notes still unread in [inbox]. A note that was deleted cannot hold the number up. */
    fun unreadFeedback(inbox: List<TrainingNotification>, notes: List<CoachNote>): Set<String> {
        val unread = inbox.filter { it.type == TrainingNotificationType.FeedbackDelivered && !it.isRead }.mapTo(mutableSetOf()) { it.id }
        return notes.filter { feedbackId(it.id) in unread }.mapTo(mutableSetOf()) { it.id }
    }
    fun feedbackId(noteId: String) = "feedback:$noteId"
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
