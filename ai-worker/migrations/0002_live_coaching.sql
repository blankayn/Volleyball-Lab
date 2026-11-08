CREATE TABLE IF NOT EXISTS live_coaching_usage (
  actor TEXT NOT NULL,
  kind TEXT NOT NULL,
  day TEXT NOT NULL,
  count INTEGER NOT NULL,
  last_requested_at INTEGER NOT NULL,
  PRIMARY KEY (actor, kind)
);
