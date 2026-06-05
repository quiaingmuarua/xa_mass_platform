CREATE TABLE IF NOT EXISTS xa_operator_credential (
    user_id VARCHAR(128) PRIMARY KEY,
    password_hash TEXT NOT NULL,
    hash_algorithm VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_xa_operator_credential_status
    ON xa_operator_credential(status, user_id);
