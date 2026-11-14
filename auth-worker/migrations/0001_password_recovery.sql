CREATE TABLE password_recovery (
    id TEXT PRIMARY KEY,
    email TEXT NOT NULL,
    code_hash TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    used_at INTEGER
);
CREATE INDEX password_recovery_expiry ON password_recovery(expires_at);

CREATE TABLE recovery_rate_limits (
    id TEXT PRIMARY KEY,
    requests INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);
