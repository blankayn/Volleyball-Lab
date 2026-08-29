package com.volleyballlab.player.domain.model

import androidx.annotation.StringRes
import com.volleyballlab.player.R

/**
 * Where a player stands with a coach. `Assn_Status` in Table 15.
 *
 * The document's own example values are "active, completed". Three are needed for
 * the flow Figures 21 and 27 describe: the player sends a request, so there is a
 * state before active, and the coach can decline it, so there is a state that is
 * not merely "ended".
 */
enum class MembershipStatus(@param:StringRes val displayName: Int) {
    /** The player has asked and the coach has not answered. Figure 27's queue. */
    Pending(R.string.membership_pending),

    /** Approved and on the roster. */
    Active(R.string.membership_active),

    /** The coach declined, or either side ended it. */
    Ended(R.string.membership_ended),
}

/**
 * One coach-to-player link. `AthleteAssignment` in Table 15.
 *
 * ## The player's details are copied here, not looked up
 *
 * [playerName] and [position] are a snapshot taken when the player asked to join,
 * rather than a live read of that player's profile. Two reasons. The profile store
 * is scoped per uid and a coach has no business reading another account's
 * preferences wholesale - the consent the player gave was to share their *training*
 * with this coach, which is a narrower thing. And a roster that renders blank rows
 * because the other account's DataStore has not been touched since boot is worse
 * than one showing a name from last week.
 *
 * @param playerUid the athlete's Firebase uid. The join key for everything else -
 *   sessions, assignments, feedback - and the reason the coach can read this
 *   player's session history at all.
 * @param requestedAtEpochMs when the player asked. Sorts the join queue oldest
 *   first, because a request that has been waiting three days should not sink
 *   below one that arrived this morning.
 * @param decidedAtEpochMs when the coach answered, or null while it is still
 *   pending.
 */
data class RosterMember(
    val playerUid: String,
    val playerName: String,
    val position: CourtPosition?,
    val status: MembershipStatus,
    val requestedAtEpochMs: Long,
    val decidedAtEpochMs: Long? = null,
) {
    val isActive: Boolean get() = status == MembershipStatus.Active

    val isPending: Boolean get() = status == MembershipStatus.Pending

    /** Initials for the avatar, or null when the player never set a name. */
    fun initials(): String? = AthleteProfile(name = playerName).initials()
}

/**
 * A player's link to a coach, as it appears on the *player's* side.
 *
 * The player needs to know which coach they asked, whether the answer has come
 * back, and how to get out. None of that is on [RosterMember], which is the
 * coach's view of the same link. Keeping the two apart means neither screen has to
 * filter a list of things that are not about it.
 */
data class CoachLink(
    val coachUid: String,
    val coachName: String,
    val teamName: String,
    val status: MembershipStatus,
    val requestedAtEpochMs: Long,
) {
    val isActive: Boolean get() = status == MembershipStatus.Active
    val isPending: Boolean get() = status == MembershipStatus.Pending
}
