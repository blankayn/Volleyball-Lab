export type CoachPresence = "Online" | "Busy" | "Offline";
export const PRESENCE_HEARTBEAT_MS = 60_000;
export const PRESENCE_LEASE_MS = 120_000;
export const PRESENCE_IDLE_MS = 300_000;
export const PRESENCE_CHECK_MS = 15_000;
export interface PresenceLease { status: CoachPresence; updatedAt: number; expiresAt: number }

export function localCoachPresence(foreground: boolean, connected: boolean, busy: boolean, idleMs: number): CoachPresence {
  return !foreground || !connected || idleMs >= PRESENCE_IDLE_MS ? "Offline" : busy ? "Busy" : "Online";
}

export function resolveCoachPresence(leases: PresenceLease[], now: number): CoachPresence {
  const live = leases.filter((lease) => lease.expiresAt > now && lease.updatedAt > 0 &&
    lease.updatedAt <= now + 30_000 && now - lease.updatedAt < PRESENCE_LEASE_MS);
  return live.some((lease) => lease.status === "Busy") ? "Busy"
    : live.some((lease) => lease.status === "Online") ? "Online" : "Offline";
}

export interface PresenceInputs { foreground: boolean; connected: boolean; busy: boolean }

/** Serializes heartbeat writes and coalesces activity changes during a pending write. */
export class CoachPresencePublisher {
  private lastActivity: number;
  private lastStatus: CoachPresence | null = null;
  private lastAcknowledged = -Infinity;
  private nextAttempt = -Infinity;
  private pending = false;
  private stopped = false;

  constructor(private readonly inputs: () => PresenceInputs,
    private readonly write: (status: CoachPresence, expiresAt: number) => Promise<void>,
    private readonly clock: () => number = Date.now, private readonly elapsed: () => number = clock) {
    this.lastActivity = elapsed();
  }
  touch(): void { this.lastActivity = this.elapsed(); void this.tick(); }
  stop(): void { this.stopped = true; }
  async tick(): Promise<void> {
    const state = this.inputs();
    const now = this.elapsed();
    if (this.stopped || this.pending || !state.connected || now < this.nextAttempt) return;
    const status = localCoachPresence(state.foreground, state.connected, state.busy, now - this.lastActivity);
    if (status === this.lastStatus && (status === "Offline" || now - this.lastAcknowledged < PRESENCE_HEARTBEAT_MS)) return;
    this.pending = true;
    try {
      await this.write(status, this.clock() + PRESENCE_LEASE_MS);
      if (!this.stopped) { this.lastStatus = status; this.lastAcknowledged = this.elapsed(); }
    } catch { this.nextAttempt = this.elapsed() + PRESENCE_CHECK_MS; }
    finally {
      this.pending = false;
      const current = this.inputs();
      if (!this.stopped && localCoachPresence(current.foreground, current.connected, current.busy, this.elapsed() - this.lastActivity) !== this.lastStatus) void this.tick();
    }
  }
}
