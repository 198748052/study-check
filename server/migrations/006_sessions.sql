-- 一天多段自习：每次进馆/出馆是一段（session），一天可以有任意多段。
-- checkins 保留为「按天聚合」的缓存行（最早进馆、最晚出馆、总时长），
-- 统计、报表、导出等只读 checkins 的旧代码无需改动。
CREATE TABLE IF NOT EXISTS sessions (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    day          TEXT    NOT NULL,           -- YYYY-MM-DD（进馆时间所属日期）
    in_at        TEXT,                       -- RFC3339
    out_at       TEXT,
    in_photo     TEXT,
    out_photo    TEXT,
    in_note      TEXT    NOT NULL DEFAULT '',
    out_note     TEXT    NOT NULL DEFAULT '',
    in_place     TEXT    NOT NULL DEFAULT '',
    out_place    TEXT    NOT NULL DEFAULT '',
    duration_sec INTEGER NOT NULL DEFAULT 0,
    created_at   TEXT    NOT NULL,
    updated_at   TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_sessions_user_day ON sessions(user_id, day, in_at);
CREATE INDEX IF NOT EXISTS ix_sessions_user_updated ON sessions(user_id, updated_at);

-- 把已有的单日记录原样搬成第一段，历史数据不丢
INSERT INTO sessions(user_id, day, in_at, out_at, in_photo, out_photo,
                     in_note, out_note, in_place, out_place,
                     duration_sec, created_at, updated_at)
SELECT user_id, day, in_at, out_at, in_photo, out_photo,
       in_note, out_note, in_place, out_place,
       duration_sec, created_at, updated_at
FROM checkins
WHERE in_at IS NOT NULL;
