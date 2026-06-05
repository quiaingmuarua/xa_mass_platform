CREATE TABLE IF NOT EXISTS xa_api_usage_ledger (
    usage_id VARCHAR(255) PRIMARY KEY,
    key_id VARCHAR(128) NOT NULL,
    principal_id VARCHAR(128),
    user_id VARCHAR(128),
    project VARCHAR(128),
    event_code VARCHAR(128),
    operation VARCHAR(64) NOT NULL,
    task_id VARCHAR(128),
    message_id VARCHAR(128),
    request_id VARCHAR(255),
    units BIGINT NOT NULL,
    status VARCHAR(64) NOT NULL,
    failure_reason TEXT,
    failure_status INTEGER,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_xa_api_usage_key_created
    ON xa_api_usage_ledger(key_id, created_at, usage_id);

CREATE INDEX IF NOT EXISTS idx_xa_api_usage_principal_created
    ON xa_api_usage_ledger(principal_id, created_at, usage_id);

CREATE INDEX IF NOT EXISTS idx_xa_api_usage_status_created
    ON xa_api_usage_ledger(status, created_at);

CREATE INDEX IF NOT EXISTS idx_xa_api_usage_task
    ON xa_api_usage_ledger(task_id, message_id);
