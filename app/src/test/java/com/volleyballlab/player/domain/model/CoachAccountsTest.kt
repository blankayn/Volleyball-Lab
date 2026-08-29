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


    /**
     * The coach fixture is the only way into the coach shell until the web portal
     * exists. If its address ever stopped satisfying the rule, the button would
     * sign in successfully and land in the *player* shell - which looks exactly
     * like the whole feature being broken.
     */
    @Test
    fun `the demo coach account is a coach address`() {
        assertTrue(CoachAccounts.isCoachAddress(DemoAccounts.Coach.email))
    }

    /**
     * And the athlete fixture must *not* be, or every sign-in through it would
     * promote it to a coach and there would be no player left to demonstrate the
     * other half of the relationship with.
     */
    @Test
    fun `the demo player account is not a coach address`() {
        assertFalse(CoachAccounts.isCoachAddress(DemoAccounts.Player.email))
    }

    /** Each fixture must open the shell it names, or the buttons lie. */
    @Test
    fun `each demo account carries the role its address implies`() {
        assertEquals(UserRole.Coach, DemoAccounts.Coach.role)
        assertEquals(UserRole.Player, DemoAccounts.Player.role)
    }

    /** Firebase rejects anything shorter, and the failure is opaque when it does. */
    @Test
    fun `every demo password clears Firebase's floor`() {
        DemoAccounts.All.forEach { assertTrue(it.password.length >= 6) }
    }

    /**
     * `.test` is reserved by RFC 2606 and can never resolve, which is what makes
     * it safe to commit these credentials: the addresses cannot belong to anyone
     * and cannot receive a password reset.
     */
    @Test
    fun `every demo address is on a reserved domain`() {
        DemoAccounts.All.forEach { assertTrue(it.email.endsWith(".test")) }
    }

    /**
     * Two fixtures that were the same account would silently turn the switch into
     * a no-op, and the relationship it exists to demonstrate needs two ends.
     */
    @Test
    fun `the demo accounts are distinct`() {
        assertEquals(DemoAccounts.All.size, DemoAccounts.All.map { it.email }.toSet().size)
    }

    /** The membership test has to agree with the list, in both directions. */
    @Test
    fun `only the fixtures are recognised as demo addresses`() {
        DemoAccounts.All.forEach { assertTrue(DemoAccounts.isDemoAddress(it.email)) }
        assertTrue(DemoAccounts.isDemoAddress("  COACH@VolleyballLab.TEST  "))
        assertFalse(DemoAccounts.isDemoAddress("coach@gmail.com"))
        assertFalse(DemoAccounts.isDemoAddress(""))
    }
}
