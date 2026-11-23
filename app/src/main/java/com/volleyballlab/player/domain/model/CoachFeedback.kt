package com.volleyballlab.player.domain.model

/**
 * A session a player has sent to their coach for review.
 *
 * ## Submission is explicit, and that is the point
 *
 * Nothing is shared automatically. A linked player records as many sessions as
 * they like and the coach sees none of them until the player taps submit, which is
 * what Figure 22 describes and what makes the consent in the storyboard mean
 * something. A coach reading every rep an athlete has ever filmed, including the
 * ones they deleted the app over, is a different product from the one the document
 * specifies.
 *
 * @param sessionId the id of a [SessionResult] in the *player's* session store.
 *   The coach resolves it through
 *   [com.volleyballlab.player.domain.repository.SessionDirectory], which is the
 *   only path by which one account's training reaches another account's screen.
 * @param reviewedAtEpochMs null until the coach has left a note on it. What makes
 *   the dashboard's review queue a queue rather than a list.
 */
data class SessionSubmission(
    val sessionId: String,
    val coachUid: String,
    val playerUid: String,
    val submittedAtEpochMs: Long,
    val reviewedAtEpochMs: Long? = null,
) {
    val isPending: Boolean get() = reviewedAtEpochMs == null
}

/**
 * A written note from a coach to a player. `CoachFeedback`, Table 17.
 *
 * @param sessionId `CFdb_SessionId`, nullable in the table and nullable here: a
 *   coach may leave general advice that is not about any one recording.
 */
data class CoachNote(
    val id: String,
    val coachUid: String,
    val playerUid: String,
    val sessionId: String?,
    val text: String,
    val createdAtEpochMs: Long,
    /** A moment in the session's own recording this note is about. Never set without [sessionId]. */
    val videoMs: Long? = null,
    /** The repetition at [videoMs], when the recording's review names one. */
    val repNumber: Int? = null,
    /** A photo was sent with this note. The coach may have removed it since. */
    val hasPhoto: Boolean = false,
) {
    init {
        require(videoMs == null || sessionId != null && videoMs in 0..MAX_VIDEO_MS) { "A moment needs its session" }
        require(repNumber == null || videoMs != null && repNumber in 1..MAX_REP) { "A rep number needs its moment" }
    }

    companion object {
        const val MAX_LENGTH = 800
        const val MAX_PHOTO_LENGTH = 300_000
        const val MAX_VIDEO_MS = 3_600_000L
        const val MAX_REP = 1000

        /** "Rep 4 · 0:12", or the time alone when no rep is known. */
        fun momentLabel(videoMs: Long, repNumber: Int?): String {
            val time = "%d:%02d".format(java.util.Locale.ROOT, videoMs / 60_000, videoMs / 1000 % 60)
            return if (repNumber == null) "At $time" else "Rep $repNumber · $time"
        }
    }
}
