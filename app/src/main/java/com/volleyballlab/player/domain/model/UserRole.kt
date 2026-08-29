package com.volleyballlab.player.domain.model

import androidx.annotation.Keep
import androidx.annotation.StringRes
import com.volleyballlab.player.R

/**
 * Which half of the app an account belongs to.
 *
 * This is the capstone document's `User_Role` column (Table 7) made into a type.
 * The document gives the coach a separate ReactJS portal; here both roles live in
 * one binary behind one login form, and this enum is the only thing that decides
 * which shell an account sees after authenticating.
 *
 * ## Why one app rather than two
 *
 * A second Android module would duplicate the auth stack, the theme, the domain
 * models and the session store, and would leave a coach and a player unable to
 * hand one phone between them - which is exactly the demo the storyboard
 * describes, where a player joins a coach's team from a shared handset.
 *
 * ## Where the role actually lives, and the limitation that follows
 *
 * On this device, per uid - see
 * [com.volleyballlab.player.data.role.DataStoreRoleRepository]. The document puts
 * `User_Role` on the User table, but conflict 4 (Firestore vs. MySQL) is still open
 * and conflict 5 notes coach accounts have no home in the ERD at all, so there is
 * no remote column to read yet. The consequence is honest and worth stating: an
 * account signing in on a *fresh* install lands as a [Player] because nothing on
 * that device remembers otherwise, and the Profile screen carries an explicit
 * switch for that case. When the backend lands, this reads from the account and
 * the switch becomes an admin concern instead.
 *
 * [Keep] because these are persisted by [Enum.name] and are a navigation argument;
 * R8 renaming them in a release build would strand every stored role.
 */
@Keep
enum class UserRole(@param:StringRes val displayName: Int, @param:StringRes val blurb: Int) {
    /** Records their own training. The original app. */
    Player(R.string.role_player, R.string.role_player_blurb),

    /** Reviews other people's training. Sees the coach shell instead. */
    Coach(R.string.role_coach, R.string.role_coach_blurb);

    companion object {
        val All: List<UserRole> = entries

        /**
         * Reads a stored name back, treating anything unrecognised as [Player].
         *
         * Same tolerance the session store shows: a role written by a later build
         * should degrade to the ordinary app rather than crash this one.
         */
        fun fromNameOrPlayer(name: String?): UserRole =
            entries.firstOrNull { it.name == name } ?: Player
    }
}
