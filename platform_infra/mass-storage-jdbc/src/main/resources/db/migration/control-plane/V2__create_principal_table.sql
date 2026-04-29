CREATE TABLE IF NOT EXISTS xa_principal (
  principal_id VARCHAR(128) PRIMARY KEY,
  principal_type VARCHAR(64) NOT NULL,
  credential_hash VARCHAR(128) NOT NULL UNIQUE,
  key_prefix VARCHAR(128),
  user_id VARCHAR(128),
  project_scope VARCHAR(128),
  enabled BOOLEAN NOT NULL,
  json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_xa_principal_type ON xa_principal(principal_type);
CREATE INDEX IF NOT EXISTS idx_xa_principal_user ON xa_principal(user_id);
CREATE INDEX IF NOT EXISTS idx_xa_principal_project_scope ON xa_principal(project_scope);
