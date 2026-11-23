package com.volleyballlab.player.domain.model

enum class CoachAvailability(val label: String) {
    Online("Online"), Busy("Busy"), Offline("Offline")
}

enum class CoachingTopic(val label: String, val introductory: Boolean = false) {
    FirstDrill("First drill", true), TrainingFocus("Training focus", true), RecordingHelp("Recording help", true),
    Technique("Technique"), DrillInstructions("Drill instructions"), FeedbackClarification("Feedback clarification")
}

data class CoachingQuestion(
    val id: String, val coachUid: String, val playerUid: String, val sessionId: String?,
    val topic: CoachingTopic, val text: String, val createdAt: Long,
    val answer: String? = null, val answeredAt: Long? = null,
    val followUp1: CoachingFollowUp? = null, val followUp2: CoachingFollowUp? = null,
    val resolvedAt: Long? = null, val coachReadAt: Long? = null, val playerReadAt: Long? = null,
) {
    val messages: List<CoachingMessage> get() = buildList {
        add(CoachingMessage("question", text, createdAt, false))
        if (answer != null) add(CoachingMessage("answer", answer, answeredAt ?: 0, true))
        listOfNotNull(followUp1, followUp2).forEachIndexed { index, turn ->
            add(CoachingMessage("followup-$index", turn.text, turn.createdAt, false))
            if (turn.answer != null) add(CoachingMessage("reply-$index", turn.answer, turn.answeredAt ?: 0, true))
        }
    }
    val awaitingCoach: Boolean get() = resolvedAt == null && messages.last().fromCoach.not()
    val followUpCount: Int get() = listOfNotNull(followUp1, followUp2).size
    fun unread(forCoach: Boolean): Int {
        val read = (if (forCoach) coachReadAt else playerReadAt) ?: 0L
        return messages.count { it.fromCoach != forCoach && it.at > read }
    }
}
data class CoachingFollowUp(val text: String, val createdAt: Long, val answer: String? = null, val answeredAt: Long? = null)
data class CoachingMessage(val id: String, val text: String, val at: Long, val fromCoach: Boolean)

/** One open chat message. [createdAt] is 0 until the server has accepted it. */
data class CoachingChatMessage(val id: String, val coachUid: String, val playerUid: String, val senderUid: String,
    val text: String, val createdAt: Long) {
    val fromCoach: Boolean get() = senderUid == coachUid
}
data class CoachingRead(val coachUid: String, val playerUid: String, val coachReadAt: Long = 0L, val playerReadAt: Long = 0L) {
    fun at(forCoach: Boolean) = if (forCoach) coachReadAt else playerReadAt
}

/** Earlier question threads and open chat with one coach-player pair, as a single timeline. */
data class CoachingConversation(val playerUid: String, val questions: List<CoachingQuestion>,
    val chat: List<CoachingChatMessage> = emptyList(), val read: CoachingRead? = null) {
    val messages: List<CoachingMessage> get() = (questions.flatMap { question -> question.messages.map { it.copy(id = "${question.id}:${it.id}") } } +
        chat.map { CoachingMessage("chat:${it.id}", it.text, it.createdAt, it.fromCoach) })
        .sortedBy { if (it.at <= 0L) Long.MAX_VALUE else it.at }
    val latest: CoachingMessage get() = messages.last()
    val unread: Int get() = unread(true)
    fun unread(forCoach: Boolean): Int = questions.sumOf { it.unread(forCoach) } + unreadChat(forCoach).size
    fun unreadChat(forCoach: Boolean): List<CoachingChatMessage> {
        val readAt = read?.at(forCoach) ?: 0L
        return chat.filter { it.fromCoach != forCoach && it.createdAt > readAt }
    }
    /** The oldest question still waiting. Answering it keeps older app versions in step. */
    val waitingQuestion: CoachingQuestion? get() = questions.filter { it.awaitingCoach && it.createdAt > 0 }.minByOrNull { it.createdAt }
}
fun coachingConversation(coachUid: String, playerUid: String, questions: List<CoachingQuestion>,
    chat: List<CoachingChatMessage>, reads: List<CoachingRead>) = CoachingConversation(playerUid,
    questions.filter { it.coachUid == coachUid && it.playerUid == playerUid }.sortedBy { it.createdAt },
    chat.filter { it.coachUid == coachUid && it.playerUid == playerUid },
    reads.firstOrNull { it.coachUid == coachUid && it.playerUid == playerUid })
fun coachingInbox(questions: List<CoachingQuestion>, chat: List<CoachingChatMessage> = emptyList(), reads: List<CoachingRead> = emptyList()) =
    (questions.map { it.playerUid } + chat.map { it.playerUid }).distinct()
        .map { uid -> CoachingConversation(uid, questions.filter { it.playerUid == uid }.sortedBy { it.createdAt },
            chat.filter { it.playerUid == uid }, reads.firstOrNull { it.playerUid == uid }) }
        .sortedWith(compareByDescending<CoachingConversation> { it.unread > 0 }.thenByDescending { it.latest.at }.thenBy { it.playerUid })

data class CoachReview(
    val id: String, val coachUid: String, val playerUid: String, val sessionId: String,
    val rating: Int, val text: String, val createdAt: Long,
)

data class CoachPublicSummary(
    val availability: CoachAvailability? = null,
    val reviews: List<CoachReview> = emptyList(),
    val responseTimesMs: List<Long> = emptyList(),
) {
    val averageRating: Double? get() = reviews.takeIf { it.isNotEmpty() }?.map { it.rating }?.average()
    val typicalResponseMs: Long? get() = responseTimesMs.takeIf { it.size >= 3 }?.sorted()?.let { times ->
        val middle = times.size / 2
        if (times.size % 2 == 0) times[middle - 1] + (times[middle] - times[middle - 1]) / 2 else times[middle]
    }
}

fun coachingContextId(coachUid: String, playerUid: String, sessionId: String?) =
    "${coachUid}_${playerUid}_${sessionId ?: "intro"}"

fun hasCoachingLink(text: String) = Regex("(?i)(https?://|www[.])").containsMatchIn(text)

fun hasCoachingContact(text: String) = hasCoachingLink(text) || Regex(
    "(?i)(@[a-z0-9_]|[a-z0-9-]+[.][a-z]{2,}\\b|[+]?[0-9][0-9 ()-]{6,}[0-9]|instagram|snapchat|telegram|whatsapp|tiktok|discord|facebook)"
).containsMatchIn(text)

fun coachingResponseLabel(milliseconds: Long): String = when {
    milliseconds < 60_000 -> "under a minute"
    milliseconds < 3_600_000 -> "${(milliseconds / 60_000).coerceAtLeast(1)} min"
    milliseconds < 86_400_000 -> "${milliseconds / 3_600_000} hr"
    else -> "${milliseconds / 86_400_000} days"
}
