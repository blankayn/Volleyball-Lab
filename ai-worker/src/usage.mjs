/** Atomic, per-kind daily limits; stores only hashed actor references and counters. */
export async function reserveUsage(db, actor, kind, now, limit, interval) {
  const day = new Date(now).toISOString().slice(0, 10);
  const result = await db.prepare(`INSERT INTO live_coaching_usage(actor,kind,day,count,last_requested_at)
    VALUES (?,?,?,1,?) ON CONFLICT(actor,kind) DO UPDATE SET day=excluded.day,
    count=CASE WHEN live_coaching_usage.day=excluded.day THEN live_coaching_usage.count+1 ELSE 1 END,
    last_requested_at=excluded.last_requested_at
    WHERE (live_coaching_usage.day!=excluded.day OR live_coaching_usage.count<?)
      AND live_coaching_usage.last_requested_at<=?`)
    .bind(actor, kind, day, now, limit, now - interval).run();
  return result.meta.changes === 1;
}
