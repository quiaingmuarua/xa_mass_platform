CREATE TABLE IF NOT EXISTS xa_task (
  task_id VARCHAR(128) PRIMARY KEY,
  status VARCHAR(64),
  project VARCHAR(128),
  schedulable BOOLEAN,
  max_runtime_deadline TIMESTAMP,
  json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_xa_task_status ON xa_task(status);
CREATE INDEX IF NOT EXISTS idx_xa_task_project ON xa_task(project);
CREATE INDEX IF NOT EXISTS idx_xa_task_deadline ON xa_task(max_runtime_deadline);

CREATE TABLE IF NOT EXISTS xa_worker (
  worker_id VARCHAR(128) PRIMARY KEY,
  worker_group_id VARCHAR(128),
  json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_xa_worker_group ON xa_worker(worker_group_id);

CREATE TABLE IF NOT EXISTS xa_rule (
  rule_id VARCHAR(128) PRIMARY KEY,
  rule_type VARCHAR(64),
  json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_xa_rule_type ON xa_rule(rule_type);
