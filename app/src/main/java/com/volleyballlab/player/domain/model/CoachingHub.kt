package com.volleyballlab.player.domain.model

enum class CoachAvailability(val label: String) {
    Available("Available"), Busy("Busy"), Offline("Offline"), FullyBooked("Fully booked")
}

enum class CoachingTopic(val label: String, val introductory: Boolean = false) {
    FirstDrill("First drill", true), TrainingFocus("Training focus", true), RecordingHelp("Recording help", true),
    Technique("Technique"), DrillInstructions("Drill instructions"), FeedbackClarification("Feedback clarification")
}

data class CoachingQuestion(
    val id: String, val coachUid: String, val playerUid: String, val sessionId: String?,
    val topic: CoachingTopic, val text: String, val createdAt: Long,
    val answer: String? = null, val answeredAt: Long? = null,
)

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

fun coachingResponseLabel(milliseconds: Long): String = when {
    milliseconds < 60_000 -> "under a minute"
    milliseconds < 3_600_000 -> "${(milliseconds / 60_000).coerceAtLeast(1)} min"
    milliseconds < 86_400_000 -> "${milliseconds / 3_600_000} hr"
    else -> "${milliseconds / 86_400_000} days"
}
