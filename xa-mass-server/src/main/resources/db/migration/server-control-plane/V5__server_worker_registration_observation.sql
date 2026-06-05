CREATE TABLE IF NOT EXISTS xa_worker_registration_observation (
    observation_id VARCHAR(255) PRIMARY KEY,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(255) NOT NULL,
    action VARCHAR(64) NOT NULL,
    principal_id VARCHAR(128),
    principal_type VARCHAR(64),
    request_hash VARCHAR(128) NOT NULL,
    payload_json TEXT,
    occurred_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_xa_worker_registration_resource
    ON xa_worker_registration_observation(resource_type, resource_id, occurred_at);

CREATE INDEX IF NOT EXISTS idx_xa_worker_registration_principal
    ON xa_worker_registration_observation(principal_id, occurred_at);
