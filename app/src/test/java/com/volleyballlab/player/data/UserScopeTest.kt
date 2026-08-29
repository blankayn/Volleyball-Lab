package com.volleyballlab.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the rule that two accounts on one phone cannot see each other's data.
 *
 * This is a regression test for a bug that shipped: every local store - profile,
 * assessment answers, training sessions - was keyed by name alone, so it belonged
 * to the handset rather than to the athlete. Signing in as a second account
 * inherited the first athlete's name, their questionnaire answers and their entire
 * session history, and the dashboard then gave them feedback about somebody else's
 * technique.
 *
 * The failure was invisible from inside a single account, which is what makes it
 * worth a test rather than a comment: everything looked correct until a second
 * person signed in.
 */
class UserScopeTest {

    @Test
    fun `two accounts never share a key`() {
        val first = UserScope.of("uidAAA")!!
        val second = UserScope.of("uidBBB")!!

        assertNotEquals(first.scoped("sessions"), second.scoped("sessions"))
        assertNotEquals(first.string("sessions"), second.string("sessions"))
    }

    @Test
    fun `the same account always resolves to the same key`() {
        assertEquals(
            UserScope.of("uidAAA")!!.scoped("sessions"),
            UserScope.of("uidAAA")!!.scoped("sessions"),
        )
    }

    /**
     * The scoped name must not collide with the unscoped one the buggy build wrote,
     * or a fresh account would silently adopt the orphaned device-wide data.
     */
    @Test
    fun `a scoped key is distinct from the legacy unscoped name`() {
        assertNotEquals("sessions", UserScope.of("uidAAA")!!.scoped("sessions"))
    }

    /**
     * Different key names under one account stay distinct - the separator must not
     * let `name` + uid `x` collide with `name:x` + uid ``.
     */
    @Test
    fun `distinct names stay distinct within one account`() {
        val scope = UserScope.of("uid")!!
        assertNotEquals(scope.scoped("name"), scope.scoped("position"))
        assertNotEquals(scope.scoped("rating_Serving"), scope.scoped("rating_Setting"))
    }

    /**
     * No session means no scope, and callers must treat that as "nothing to read or
     * write" rather than falling back to an unscoped key - which is precisely the
     * bug being fixed.
     */
    @Test
    fun `no signed-in account yields no scope`() {
        assertNull(UserScope.of(null))
        assertNull(UserScope.of(""))
        assertNull(UserScope.of("   "))
    }
}
