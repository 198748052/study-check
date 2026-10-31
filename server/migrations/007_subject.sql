-- 科目 / 标签：给每一段自习打一个科目（数学、英语、考研政治……），
-- 统计页据此做「各科目投入时长」分布。空串表示没填。
ALTER TABLE sessions ADD COLUMN subject TEXT NOT NULL DEFAULT '';

-- 按科目聚合时按 user_id + day 范围过滤，再按 subject 分组
CREATE INDEX IF NOT EXISTS ix_sessions_user_subject ON sessions(user_id, subject, day);
