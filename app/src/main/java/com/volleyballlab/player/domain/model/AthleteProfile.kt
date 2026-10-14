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

/** Same wire-format caution as [CourtPosition]. */
enum class DominantHand(@param:StringRes val displayName: Int) {
    Left(R.string.hand_left),
    Right(R.string.hand_right),
}

/**
 * Who the athlete is, as far as the app is concerned.
 *
 * ## Everything here is optional
 *
 * Every field can be absent, and the app is fully usable with all of them empty.
 * This is deliberate: nothing in the capture or scoring pipeline reads any of it.
 * A profile that gated training behind a form would be collecting data to no end,
 * on a device whose whole privacy claim is that it keeps to itself.
 *
 * ## Why position and hand are stored at all
 *
 * They are the two facts a coach asks first, and both are due to become real
 * inputs rather than labels. Dominant hand decides which arm the spike and serve
 * analyzers should be reading - currently they infer it per rep - and position
 * decides which skills are worth recommending, since asking a libero to drill
 * blocking is wasted training. Neither is wired to that yet, and this file is
 * where it will attach when it is.
 */
data class AthleteProfile(
    val name: String = "",
    val position: CourtPosition? = null,
    val dominantHand: DominantHand? = null,
    val goalSkill: Skill? = null,
    val weeklySessionGoal: Int = 3,
    val goalText: String = "",
    val photoPath: String? = null,
    val firstName: String = "",
    val lastName: String = "",
    val address: ProfileAddress? = null,
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

        private val WHITESPACE = Regex("\\s+")
    }
}
