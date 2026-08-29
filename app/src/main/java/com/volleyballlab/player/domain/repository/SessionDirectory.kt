package com.volleyballlab.player.domain.repository

import com.volleyballlab.player.domain.model.SessionResult
import kotlinx.coroutines.flow.Flow

/**
 * Reads a *named* account's training, rather than the signed-in one's.
 *
 * ## Why this is a separate interface from [SessionRepository]
 *
 * [SessionRepository] is deliberately incapable of naming an account: its
 * `sessions` flow is whoever is signed in, and that is what stops a screen from
 * accidentally rendering someone else's history. The coach shell genuinely needs
 * the other capability, and the honest way to give it that is a second, narrower
 * interface that says so in its name - not a `uid` parameter bolted onto the first
 * one, where every existing caller would then be one typo away from reading the
 * wrong athlete.
 *
 * ## This is a privacy boundary, so treat it as one
 *
 * The only legitimate caller is the coach shell, and only for a uid that appears on
 * that coach's roster with [com.volleyballlab.player.domain.model.MembershipStatus.Active].
 * That status is the athlete's own consent: they browsed the directory, chose this
 * coach and sent the request, and the coach could only accept one that was offered.
 * It is also revocable in one tap - their Coach tab names the team and offers Leave,
 * which ends the membership and closes this door again.
 *
 * There is no enforcement here, because on-device there is nothing to enforce
 * against - the file is readable by the process either way. What keeps it correct
 * is that the coach ViewModels never construct a uid: every one they pass came out
 * of the roster flow they are already reading. Keep it that way. The moment a
 * screen accepts a uid from anywhere else, this becomes a hole rather than a seam,
 * and once there is a server the check belongs there in earnest.
 */
interface SessionDirectory {

    /**
     * One named account's sessions, newest first.
     *
     * Emits an empty list for a uid that has never trained on this device, which is
     * indistinguishable from a uid that does not exist. That is fine and slightly
     * desirable: it means this cannot be used to test whether an account exists.
     */
    fun sessionsOf(uid: String): Flow<List<SessionResult>>
}
