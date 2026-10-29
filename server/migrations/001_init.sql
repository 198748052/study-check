-- 用户
CREATE TABLE IF NOT EXISTS users (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    username        TEXT    NOT NULL UNIQUE COLLATE NOCASE,
    nickname        TEXT    NOT NULL DEFAULT '',
    password_hash   TEXT    NOT NULL,
    daily_goal_min  INTEGER NOT NULL DEFAULT 120,
    created_at      TEXT    NOT NULL
);

-- 每日打卡：一天一条，进馆一次 / 出馆一次
CREATE TABLE IF NOT EXISTS checkins (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    day          TEXT    NOT NULL,           -- YYYY-MM-DD（按配置时区）
    in_at        TEXT,                       -- RFC3339
    out_at       TEXT,
    in_photo     TEXT,                       -- 相对路径，如 2025/10/26/ab12.jpg
    out_photo    TEXT,
    in_note      TEXT    NOT NULL DEFAULT '',
    out_note     TEXT    NOT NULL DEFAULT '',
    in_place     TEXT    NOT NULL DEFAULT '',
    out_place    TEXT    NOT NULL DEFAULT '',
    duration_sec INTEGER NOT NULL DEFAULT 0,
    created_at   TEXT    NOT NULL,
    updated_at   TEXT    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_checkins_user_day ON checkins(user_id, day);
CREATE INDEX IF NOT EXISTS ix_checkins_user_day ON checkins(user_id, day DESC);
