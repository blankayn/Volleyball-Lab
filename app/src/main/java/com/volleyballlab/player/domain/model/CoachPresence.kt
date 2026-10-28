package com.volleyballlab.player.domain.model

/** Shared timing policy with the web client. Leases outlive one missed heartbeat. */
object CoachPresencePolicy {
    const val HEARTBEAT_MS = 60_000L
    const val LEASE_MS = 120_000L
    const val IDLE_MS = 300_000L
    const val CHECK_MS = 15_000L

    fun localStatus(foreground: Boolean, connected: Boolean, busy: Boolean, idleMs: Long): CoachAvailability = when {
        !foreground || !connected || idleMs >= IDLE_MS -> CoachAvailability.Offline
        busy -> CoachAvailability.Busy
        else -> CoachAvailability.Online
    }

    fun resolve(leases: List<CoachPresenceLease>, nowMs: Long): CoachAvailability {
        val live = leases.filter { it.expiresAt > nowMs && it.updatedAt > 0 &&
            it.updatedAt <= nowMs + 30_000 && nowMs - it.updatedAt < LEASE_MS }
        return when {
            live.any { it.status == CoachAvailability.Busy } -> CoachAvailability.Busy
            live.any { it.status == CoachAvailability.Online } -> CoachAvailability.Online
            else -> CoachAvailability.Offline
        }
    }
}

data class CoachPresenceLease(val status: CoachAvailability, val updatedAt: Long, val expiresAt: Long)
