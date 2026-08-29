package com.volleyballlab.player.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Builds DataStore keys that belong to one signed-in athlete.
 *
 * ## Why this type exists rather than a bare string concatenation
 *
 * Every local store in this app was originally keyed by name alone - `name`,
 * `sessions`, `rating_Serving`. That made all of it device-scoped, so two accounts
 * signing in on the same phone shared one profile, one set of assessment answers
 * and one training history. On a phone that gets handed round a team, one athlete's
 * sessions showed up under another athlete's account.
 *
 * Routing every key through this type is what stops that recurring. A new
 * preference cannot be added without a [UserScope] to build its key from, and there
 * is no [UserScope] without a uid, so "I forgot to scope this one" is not a mistake
 * that compiles.
 *
 * ## Data is kept per account, not wiped on sign-out
 *
 * Scoping rather than clearing means signing out and back in returns the athlete's
 * own history intact, and two athletes sharing a phone each keep theirs. Clearing
 * on sign-out would be simpler and would destroy training data every time someone
 * checked whether login worked.
 *
 * @param uid the Firebase uid. Firebase uids are alphanumeric, so [SEPARATOR]
 *   cannot appear in one and cannot appear in the hand-written names above either,
 *   which is what makes the split unambiguous.
 */
@JvmInline
value class UserScope(private val uid: String) {

    fun string(name: String) = stringPreferencesKey(scoped(name))

    fun boolean(name: String) = booleanPreferencesKey(scoped(name))

    fun long(name: String) = longPreferencesKey(scoped(name))

    /** Exposed for tests; the key builders above are the intended callers. */
    fun scoped(name: String): String = "$name$SEPARATOR$uid"

    companion object {
        const val SEPARATOR = ":"

        /**
         * A scope for the signed-in athlete, or null when nobody is.
         *
         * Null is a real state - the login screen is reachable with no session - and
         * callers are expected to read it as "there is nothing to show and nothing
         * to save", never to fall back to an unscoped key. That fallback is exactly
         * the bug this file exists to prevent.
         */
        fun of(uid: String?): UserScope? = uid?.takeIf { it.isNotBlank() }?.let(::UserScope)
    }
}
