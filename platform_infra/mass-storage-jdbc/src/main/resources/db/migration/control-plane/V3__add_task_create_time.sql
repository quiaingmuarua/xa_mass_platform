ALTER TABLE xa_task ADD COLUMN create_time TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_xa_task_create_time ON xa_task(create_time);
