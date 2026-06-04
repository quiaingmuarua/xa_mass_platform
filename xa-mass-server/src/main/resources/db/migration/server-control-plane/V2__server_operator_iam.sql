CREATE TABLE IF NOT EXISTS xa_iam_user (
    user_id VARCHAR(128) PRIMARY KEY,
    display_name VARCHAR(255),
    email VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    attributes_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_xa_iam_user_status
    ON xa_iam_user(status, user_id);

CREATE TABLE IF NOT EXISTS xa_iam_role (
    role_id VARCHAR(128) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    permissions_json TEXT NOT NULL,
    system_role BOOLEAN NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS xa_iam_user_role (
    user_id VARCHAR(128) NOT NULL,
    role_id VARCHAR(128) NOT NULL,
    granted_by VARCHAR(128),
    granted_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX IF NOT EXISTS idx_xa_iam_user_role_role
    ON xa_iam_user_role(role_id, user_id);
