/** All identity references are one-way hashes; no ID tokens or source sessions are stored. */
export const CACHE_TTL_MS = 24 * 60 * 60 * 1000;
const LEASE_MS = 65_000;

export class ExplanationStore {
  constructor(db) { this.db = db; }
  async get(key) {
    return this.db.prepare("SELECT * FROM ai_explanations WHERE session_key = ?").bind(key).first();
  }

  async reserve({ key, owner, fingerprint, actor, lease, now }) {
    const day = new Date(now).toISOString().slice(0, 10);
    // D1 batch executes these conditionals as one SQLite transaction. The unique
    // request id links a quota grant to exactly this lease, even in the same ms.
    const result = await this.db.batch([
      this.db.prepare(`INSERT INTO ai_explanations
        (session_key, owner_key, fingerprint, payload, generated_at, expires_at, lease, lease_until)
        VALUES (?, ?, ?, NULL, NULL, ?, ?, ?)
        ON CONFLICT(session_key) DO UPDATE SET owner_key=excluded.owner_key,
          fingerprint=excluded.fingerprint, payload=NULL, generated_at=NULL,
          expires_at=excluded.expires_at, lease=excluded.lease, lease_until=excluded.lease_until
        WHERE ai_explanations.lease_until <= ?
          AND NOT (ai_explanations.payload IS NOT NULL AND ai_explanations.fingerprint = ? AND ai_explanations.expires_at > ?)`)
        .bind(key, owner, fingerprint, now + CACHE_TTL_MS, lease, now + LEASE_MS, now, fingerprint, now),
      this.db.prepare(`INSERT INTO ai_usage(actor, day, count, last_requested_at, last_request_id)
        SELECT ?, ?, 1, ?, ? WHERE EXISTS
          (SELECT 1 FROM ai_explanations WHERE session_key = ? AND lease = ?)
        ON CONFLICT(actor) DO UPDATE SET day=excluded.day,
          count=CASE WHEN ai_usage.day=excluded.day THEN ai_usage.count+1 ELSE 1 END,
          last_requested_at=excluded.last_requested_at, last_request_id=excluded.last_request_id
        WHERE (ai_usage.day != excluded.day OR ai_usage.count < 20)
          AND ai_usage.last_requested_at <= ?`)
        .bind(actor, day, now, lease, key, lease, now - 15_000),
      this.db.prepare(`DELETE FROM ai_explanations
        WHERE session_key=? AND lease=? AND payload IS NULL AND NOT EXISTS
          (SELECT 1 FROM ai_usage WHERE actor=? AND last_request_id=?)`)
        .bind(key, lease, actor, lease),
      this.db.prepare("SELECT * FROM ai_explanations WHERE session_key=?").bind(key),
    ]);
    const row = result[3].results?.[0] ?? null;
    if (!result[0].meta.changes) return { status: "occupied", row };
    if (!result[1].meta.changes) return { status: "limited", row };
    return { status: "reserved", row };
  }

  async complete(key, lease, fingerprint, explanation, now) {
    const result = await this.db.prepare(`UPDATE ai_explanations
      SET payload=?, generated_at=?, expires_at=?, lease=NULL, lease_until=0
      WHERE session_key=? AND lease=? AND fingerprint=?`)
      .bind(JSON.stringify(explanation), now, now + CACHE_TTL_MS, key, lease, fingerprint).run();
    return result.meta.changes === 1;
  }

  async release(key, lease) {
    await this.db.prepare("DELETE FROM ai_explanations WHERE session_key=? AND lease=? AND payload IS NULL").bind(key, lease).run();
  }

  async purge(now) {
    await this.db.batch([
      this.db.prepare("DELETE FROM ai_explanations WHERE expires_at <= ? AND lease_until <= ?").bind(now, now),
      this.db.prepare("DELETE FROM ai_usage WHERE last_requested_at <= ?").bind(now - 48 * 60 * 60 * 1000),
    ]);
  }
}
