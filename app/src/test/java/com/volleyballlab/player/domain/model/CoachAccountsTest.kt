package com.volleyballlab.player.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides which accounts open the coach app.
 *
 * Worth pinning because it is the only thing standing between an athlete and a
 * screen full of other people's training data until there is a `User_Role` column
 * to read instead. A rule that is loose by one character is a rule that lets the
 * wrong person in.
 */
class CoachAccountsTest {

    @Test
    fun `the bare marker is a coach address`() {
        assertTrue(CoachAccounts.isCoachAddress("coach@volleyballlab.test"))
    }

    /**
     * The convention that makes this usable: a team member can point their own
     * inbox at a coach account without a second mailbox. Gmail delivers this to
     * `kirt@gmail.com`; Firebase treats it as a separate account.
     */
    @Test
    fun `a personal address plus-tagged with the marker is a coach address`() {
        assertTrue(CoachAccounts.isCoachAddress("kirt+coach@gmail.com"))
    }

    @Test
    fun `a coach mailbox with its own tag is still a coach address`() {
        assertTrue(CoachAccounts.isCoachAddress("coach+cebu@gmail.com"))
    }

    @Test
    fun `case and surrounding whitespace do not matter`() {
        assertTrue(CoachAccounts.isCoachAddress("  Coach@Example.COM "))
        assertTrue(CoachAccounts.isCoachAddress("Kirt+COACH@example.com"))
    }

    /** A tag that merely resembles the marker is not the marker. */
    @Test
    fun `a near-miss tag is not a coach address`() {
        assertFalse(CoachAccounts.isCoachAddress("kirt+coaching@gmail.com"))
        assertFalse(CoachAccounts.isCoachAddress("kirt+headcoach@gmail.com"))
    }

    /**
     * The near misses. Every one of these is an ordinary address that a player
     * could plausibly hold, and letting any of them through would hand that player
     * a review queue.
     */
    @Test
    fun `addresses that merely start with the marker are not coach addresses`() {
        assertFalse(CoachAccounts.isCoachAddress("coaching@example.com"))
        assertFalse(CoachAccounts.isCoachAddress("coachella@example.com"))
        assertFalse(CoachAccounts.isCoachAddress("headcoach@example.com"))
        assertFalse(CoachAccounts.isCoachAddress("coach.jumao@example.com"))
    }

    @Test
    fun `the marker must be in the local part, not the domain`() {
        assertFalse(CoachAccounts.isCoachAddress("kirt@coach.com"))
    }

    @Test
    fun `malformed input is not a coach address`() {
        assertFalse(CoachAccounts.isCoachAddress(""))
        assertFalse(CoachAccounts.isCoachAddress("coach"))
        assertFalse(CoachAccounts.isCoachAddress("@example.com"))
    }

    /**
     * The sign-up screen shows this suggestion in debug builds. If it ever stops
     * satisfying the rule it is telling people, in writing, to do something that
     * will not work.
     */
    @Test
    fun `the worked example it suggests actually satisfies the rule`() {
        val suggestion = CoachAccounts.exampleFor("kirtdenielnmagno@gmail.com")

        assertEquals("kirtdenielnmagno+coach@gmail.com", suggestion)
        assertTrue(CoachAccounts.isCoachAddress(suggestion))
    }

    @Test
    fun `the worked example degrades to something valid for junk input`() {
        assertTrue(CoachAccounts.isCoachAddress(CoachAccounts.exampleFor("")))
        assertTrue(CoachAccounts.isCoachAddress(CoachAccounts.exampleFor("not an address")))
    }
}
