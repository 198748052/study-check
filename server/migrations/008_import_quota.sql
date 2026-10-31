-- 导入配额：按用户 + 天记录当天已经导入的行数，重启也不丢。
-- 用来防止有人拿导入接口当「批量写库」用（换机恢复一次几百行很正常，
-- 一次几万行就不太对劲了），超了直接拒绝，不影响正常使用。
CREATE TABLE IF NOT EXISTS import_quota (
    user_id    INTEGER NOT NULL,
    day        TEXT    NOT NULL,
    rows_done  INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT    NOT NULL,
    PRIMARY KEY (user_id, day)
);
