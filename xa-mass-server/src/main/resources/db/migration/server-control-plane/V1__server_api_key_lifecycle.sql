CREATE TABLE IF NOT EXISTS xa_api_key_application (
    application_id VARCHAR(128) PRIMARY KEY,
    applicant_user_id VARCHAR(128) NOT NULL,
    applicant_name VARCHAR(255),
    requested_principal_id VARCHAR(128) NOT NULL,
    requested_user_id VARCHAR(128) NOT NULL,
    requested_project_scopes_json TEXT NOT NULL,
    requested_event_scopes_json TEXT NOT NULL,
    requested_permissions_json TEXT NOT NULL,
    purpose TEXT,
    status VARCHAR(32) NOT NULL,
    review_reason TEXT,
    reviewed_by VARCHAR(128),
    created_at TIMESTAMP NOT NULL,
    reviewed_at TIMESTAMP,
    attributes_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_xa_api_key_application_status
    ON xa_api_key_application(status, created_at);

CREATE TABLE IF NOT EXISTS xa_api_key_credential (
    key_id VARCHAR(128) PRIMARY KEY,
    principal_id VARCHAR(128) NOT NULL UNIQUE,
    created_for_user_id VARCHAR(128) NOT NULL,
    key_prefix VARCHAR(128) NOT NULL,
    credential_hash VARCHAR(128) NOT NULL,
    project_scope_mode VARCHAR(32) NOT NULL,
    project_scopes_json TEXT NOT NULL,
    event_scope_mode VARCHAR(32) NOT NULL,
    event_scopes_json TEXT NOT NULL,
    permissions_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    application_id VARCHAR(128),
    created_by VARCHAR(128),
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP,
    revoked_at TIMESTAMP,
    revoked_by VARCHAR(128),
    revoke_reason TEXT,
    attributes_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_xa_api_key_credential_user_status
    ON xa_api_key_credential(created_for_user_id, status);

CREATE INDEX IF NOT EXISTS idx_xa_api_key_credential_status
    ON xa_api_key_credential(status, created_at);
