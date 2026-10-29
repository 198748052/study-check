-- 安卓端所需：refresh token、幂等键、增量同步索引
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  TEXT    NOT NULL UNIQUE,   -- sha256(raw)，库里不存明文
    device      TEXT    NOT NULL DEFAULT '',
    expires_at  TEXT    NOT NULL,
    revoked_at  TEXT,
    created_at  TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_refresh_user ON refresh_tokens(user_id, expires_at);

-- 幂等：同一个 Idempotency-Key 重复提交返回首次结果，弱网重试不会写坏数据
CREATE TABLE IF NOT EXISTS idempotency (
    key        TEXT PRIMARY KEY,
    user_id    INTEGER NOT NULL,
    endpoint   TEXT    NOT NULL,
    status     INTEGER NOT NULL,
    body       TEXT    NOT NULL,
    created_at TEXT    NOT NULL
);

-- 增量同步：updated_at > since
CREATE INDEX IF NOT EXISTS ix_checkins_updated ON checkins(user_id, updated_at);
