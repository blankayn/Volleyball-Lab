CREATE TABLE IF NOT EXISTS ai_usage (
  actor TEXT PRIMARY KEY,
  day TEXT NOT NULL,
  count INTEGER NOT NULL CHECK(count >= 1 AND count <= 20),
  last_requested_at INTEGER NOT NULL,
  last_request_id TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS ai_explanations (
  session_key TEXT PRIMARY KEY,
  owner_key TEXT NOT NULL,
  fingerprint TEXT NOT NULL,
  payload TEXT,
  generated_at INTEGER,
  expires_at INTEGER NOT NULL,
  lease TEXT,
  lease_until INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS ai_explanations_expiry ON ai_explanations(expires_at);
CREATE INDEX IF NOT EXISTS ai_usage_expiry ON ai_usage(last_requested_at);
