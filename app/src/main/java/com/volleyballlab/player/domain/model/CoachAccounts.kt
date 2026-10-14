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
