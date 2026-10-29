-- 每日详情：当天可随手拍多张照片 + 文字笔记（任务过程留存）
CREATE TABLE IF NOT EXISTS entries (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    day        TEXT    NOT NULL,           -- YYYY-MM-DD（按客户端时区）
    photo      TEXT,                       -- 相对路径，可为空（纯笔记）
    note       TEXT    NOT NULL DEFAULT '',
    created_at TEXT    NOT NULL,
    updated_at TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_entries_user_day ON entries(user_id, day, id);
