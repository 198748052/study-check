-- 增量同步：entries?since= 按 user_id + updated_at 过滤，补索引避免全表扫
CREATE INDEX IF NOT EXISTS ix_entries_user_updated ON entries(user_id, updated_at, id);
