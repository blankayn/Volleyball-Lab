package com.volleyballlab.player.domain.model

/**
 * Decides, from an email address alone, whether an account is a coach account.
 *
 * ## Why this exists, and why the mobile app never asks
 *
 * Coaches register on the **web portal**, not here. The mobile app only ever
 * *receives* a coach account that already exists, which is why there is no coach
 * option anywhere on the sign-up form and no plan to add one: a chip there would
 * put a question on the registration screen that this app is not the one to ask.
 *
 * The capstone document puts `User_Role` on the User table (Table 7), so at login
 * the app should simply read what the portal wrote. There is no server here yet to
 * read it from - conflict 4 is still open and conflict 5 notes coach accounts have
 * no home in the ERD at all - so this address convention stands in, and disappears
 * entirely the moment there is a column to read instead.
 *
 * ## The convention
 *
 * An address is a coach address when the local part - the part before the `@` - is
 * exactly `coach`, or carries `coach` as a plus-tag: `you+coach@gmail.com`.
 *
 * The plus-tag half is the useful one. Gmail and most providers deliver
 * `someone+coach@gmail.com` to `someone@gmail.com`, while Firebase treats it as a
 * separate account, so a team member can create a real, verifiable coach account
 * against their own inbox in one step - no second mailbox, and no seeded
 * credentials committed to the repo.
 *
 * The rule is deliberately exact rather than a prefix match. `coaching@`,
 * `headcoach@` and `coach.jumao@` are all ordinary addresses a player could
 * plausibly hold, and letting any of them through would hand that player a screen
 * full of other people's training.
 *
 * ## This only ever promotes
 *
 * Sign-in applies this rule to *grant* the coach role, never to take it away. An
 * account that was switched to coach by hand in the Profile tab keeps that role
 * whatever its address looks like, and switching back is likewise a deliberate act
 * - see [com.volleyballlab.player.domain.repository.RoleRepository]. A rule that
 * could demote would silently undo the switch on the next launch.
 */
object CoachAccounts {

    /** The word that marks a coach address, bare or as a plus-tag. */
    const val MARKER = "coach"

    /**
     * True when [email] follows the coach convention.
     *
     * Case- and whitespace-insensitive, because an address typed on a phone
     * keyboard arrives capitalised about half the time and nobody means anything
     * by it.
     */
    fun isCoachAddress(email: String): Boolean {
        val localPart = email.trim().lowercase().substringBefore('@', missingDelimiterValue = "")
        if (localPart.isEmpty()) return false
        val segments = localPart.split('+')
        // The first segment is the mailbox itself; everything after it is a tag.
        // Either the mailbox *is* `coach`, or one of its tags is.
        return segments.first() == MARKER || segments.drop(1).any { it == MARKER }
    }

    /**
     * What to tell someone who wants a coach account.
     *
     * Kept next to the rule rather than in `strings.xml` because it is a worked
     * example of [isCoachAddress] and the two have to change together.
     */
    fun exampleFor(email: String): String {
        val trimmed = email.trim()
        val at = trimmed.indexOf('@')
        if (at <= 0) return "$MARKER@example.com"
        return trimmed.substring(0, at) + "+" + MARKER + trimmed.substring(at)
    }
}


/**
 * One standing test account, for debug builds.
 *
 * @param role which shell a tap on this account opens. Written to
 *   [com.volleyballlab.player.domain.repository.RoleRepository] on every demo
 *   sign-in, in both directions - see [DemoAccounts] for why that is allowed to
 *   demote where the address rule is not.
 * @param name the display name the account is created with, and the name seeded
 *   into the empty profile so the *other* side of the link has something to render.
 *   A roster row reading "(no name)" demonstrates nothing.
 */
data class DemoAccount(
    val email: String,
    val password: String,
    val name: String,
    val role: UserRole,
)

/**
 * The two standing test accounts: one athlete, one coach.
 *
 * ## Why the app provisions these rather than shipping seeded credentials
 *
 * Coaches register on the web portal. Until that portal exists there is no way to
 * get a coach account onto a phone at all, which makes the entire coach half of
 * this app unreachable - including for whoever has to demonstrate it at a defence.
 *
 * The athlete side has no such excuse; anyone can sign up. It is here for a
 * different reason: coach mode is a *relationship*, and a relationship cannot be
 * shown with one account. Demonstrating it means being the player who asks and the
 * coach who answers, in that order, which is four account switches - and typing an
 * address and a password four times in front of a panel is where a demo goes to
 * die.
 *
 * These are deliberately *not* fake sessions. Each goes through the ordinary
 * [com.volleyballlab.player.domain.repository.AuthRepository], so both are real
 * Firebase accounts with real uids, and everything scoped by uid - roster,
 * assignments, notes, sessions, plan - behaves exactly as it will for a real user.
 * A mocked-out session would demo a code path that ships to nobody.
 *
 * ## Why the credentials can live in the source
 *
 * Both addresses are on the reserved `.test` TLD, which by RFC 2606 can never
 * resolve, so neither can collide with anyone's inbox and neither can receive a
 * password reset. The accounts exist only inside whichever Firebase project the
 * build points at, hold nothing but what someone typed while testing, and the
 * buttons that use them are compiled out of release builds. They are fixtures, not
 * secrets.
 *
 * Delete an account from the Firebase console to reset it; the next tap makes a
 * fresh one.
 *
 * ## These write the role in both directions
 *
 * [CoachAccounts] is emphatic that its address rule only ever *promotes*, because a
 * rule that could demote would silently undo a deliberate switch on the next
 * launch. A demo button is not that rule. It names the shell it opens on its face,
 * so tapping it is exactly as deliberate as the switch in the Profile tab, and a
 * button labelled "player" that lands in the coach shell - because this uid was
 * flipped to coach an hour ago - would be the more surprising behaviour by far.
 */
object DemoAccounts {

    val Player = DemoAccount(
        email = "player@volleyballlab.test",
        password = "VolleyLab2026",
        name = "Demo Player",
        role = UserRole.Player,
    )

    val Coach = DemoAccount(
        email = "coach@volleyballlab.test",
        password = "VolleyLab2026",
        name = "Demo Coach",
        role = UserRole.Coach,
    )

    /** Athlete first: it is the order the coach flow has to be demonstrated in. */
    val All: List<DemoAccount> = listOf(Player, Coach)

    /** True when [email] is one of these fixtures. */
    fun isDemoAddress(email: String): Boolean {
        val normalised = email.trim().lowercase()
        return All.any { it.email == normalised }
    }

    init {
        // Two ways these could be wrong that would each look like the whole feature
        // is broken rather than like a typo, so both are asserted at class-load.
        //
        // The coach address must satisfy the address rule, or the button would sign
        // in and land in the player shell. The player address must *not*, or every
        // sign-in would promote the athlete fixture to a coach and there would be
        // no player left to demonstrate with.
        require(CoachAccounts.isCoachAddress(Coach.email)) {
            "DemoAccounts.Coach.email must satisfy CoachAccounts.isCoachAddress"
        }
        require(!CoachAccounts.isCoachAddress(Player.email)) {
            "DemoAccounts.Player.email must not satisfy CoachAccounts.isCoachAddress"
        }
    }
}
