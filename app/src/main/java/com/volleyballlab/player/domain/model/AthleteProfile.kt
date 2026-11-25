package com.volleyballlab.player.domain.model

import androidx.annotation.StringRes
import com.volleyballlab.player.R

/**
 * Court positions, in rotation-order rather than alphabetical order.
 *
 * The names are persisted as-is, so they are a wire format: renaming an entry
 * silently drops the stored value of anyone who had picked it, exactly as with
 * [CueId]. Add entries, do not repurpose them.
 */
enum class CourtPosition(@param:StringRes val displayName: Int) {
    Setter(R.string.position_setter),
    OutsideHitter(R.string.position_outside),
    MiddleBlocker(R.string.position_middle),
    Opposite(R.string.position_opposite),
    Libero(R.string.position_libero),
}

/**
 * Who the athlete is, as far as the app is concerned.
 *
 * ## Everything here is optional
 *
 * Every field can be absent, and the app is fully usable with all of them empty.
 * Capture uses the name and confirmed training preferences for local coaching
 * wording and cue priorities. Scoring does not depend on these preferences, and
 * training remains usable without a profile.
 *
 * ## Why position is stored at all
 *
 * It is the first fact a coach asks, and it decides which skills are worth
 * recommending, since asking a libero to drill blocking is wasted training.
 *
 * Dominant hand was removed on 19 Nov 2026: the serve and spike analyzers find
 * the hitting arm from each rep, so a stored hand only ever acted as a fallback.
 * Which arm faces the camera in a side view is a training preference instead, chosen
 * on the session setup screen (CoachingPreferences.dominantArm).
 */
data class AthleteProfile(
    val name: String = "",
    val position: CourtPosition? = null,
    val goalSkill: Skill? = null,
    val weeklySessionGoal: Int = 3,
    val goalText: String = "",
    val photoPath: String? = null,
    val firstName: String = "",
    val lastName: String = "",
    val middleName: String = "",
    val address: ProfileAddress? = null,
    val trainingSetup: SavedTrainingSetup? = null,
) {
    val hasName: Boolean get() = name.isNotBlank()

    /**
     * Up to two initials for the avatar, or null when there is no name.
     *
     * Takes the first and last word so "Kirt Deniel Magno" gives KM rather than
     * KD - a middle name is not what anyone means by their initials.
     */
    fun initials(): String? {
        val words = name.trim().split(WHITESPACE).filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        val first = words.first().first().uppercaseChar()
        val last = words.last().takeIf { words.size > 1 }?.first()?.uppercaseChar()
        return if (last == null) first.toString() else "$first$last"
    }

    companion object {
        /** Empty rather than a placeholder name, so "unset" is representable. */
        val Anonymous = AthleteProfile()

        /**
         * Long enough for a full name, short enough that the avatar and the
         * header cannot be used to store an essay.
         */
        const val MAX_NAME_PART_LENGTH = 40
        const val MAX_NAME_LENGTH = MAX_NAME_PART_LENGTH * 2 + 1

        /** "Maria C. Santos": the middle name is kept in full but shown as an initial. */
        fun displayName(first: String, middle: String, last: String): String = listOfNotNull(
            first.trim().takeIf { it.isNotEmpty() },
            middle.trim().firstOrNull()?.let { "${it.uppercaseChar()}." },
            last.trim().takeIf { it.isNotEmpty() },
        ).joinToString(" ").take(MAX_NAME_LENGTH)

        private val WHITESPACE = Regex("\\s+")
    }
}
